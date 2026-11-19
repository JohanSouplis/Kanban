package com.johansouplis.kanban.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.johansouplis.kanban.domain.CalendarGateway
import com.johansouplis.kanban.domain.EventSpec
import com.johansouplis.kanban.domain.FoundEvent
import com.johansouplis.kanban.domain.Insertion
import java.util.TimeZone

/** [id] is this phone's row id. [key] names the same calendar on every phone. */
data class CalendarInfo(val id: Long, val key: String, val name: String, val account: String, val color: Int)

private const val EVENT_DURATION_MILLIS = 30 * 60 * 1000L

fun eventMarker(taskId: String) = "kanban:$taskId"

fun eventDescription(description: String, taskId: String) =
    listOf(description, eventMarker(taskId)).filter { it.isNotBlank() }.joinToString("\n\n")

class CalendarRepository(private val context: Context) : CalendarGateway {
    private val resolver get() = context.contentResolver

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun writableCalendars(): List<CalendarInfo> = guarded { calendars(visibleOnly = true) }.orEmpty()

    /** Only calendars this phone may modify: an event it cannot delete would stay owed forever. */
    override fun find(taskId: String): List<FoundEvent>? = guarded {
        val keys = calendars(visibleOnly = false).associate { it.id to it.key }
        resolver.query(
            Events.CONTENT_URI,
            arrayOf(Events._ID, Events.CALENDAR_ID),
            "${Events.DESCRIPTION} LIKE ? AND ${Events.DELETED} = 0",
            arrayOf("%${eventMarker(taskId)}%"),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    keys[cursor.getLong(1)]?.let { add(FoundEvent(cursor.getLong(0), it)) }
                }
            }
        }
    }

    override fun insert(calendarKey: String, taskId: String, spec: EventSpec): Insertion = guarded {
        val calendar = writableCalendars().find { it.key == calendarKey } ?: return@guarded Insertion.NO_CALENDAR
        val values = eventValues(taskId, spec).apply { put(Events.CALENDAR_ID, calendar.id) }
        val id = resolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return@guarded Insertion.UNAVAILABLE
        insertReminder(id, spec.leadMinutes)
        Insertion.INSERTED
    } ?: Insertion.UNAVAILABLE

    override fun update(eventId: Long, taskId: String, spec: EventSpec): Boolean = guarded {
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), eventValues(taskId, spec), null, null)
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        insertReminder(eventId, spec.leadMinutes)
    } != null

    override fun delete(eventId: Long): Boolean = guarded {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    } != null

    /**
     * A Google calendar's address (`xxx@group.calendar.google.com`) is the same on every phone that syncs it,
     * so it keys the shared calendar. A calendar that is not synced keeps a per-phone key.
     */
    private fun calendars(visibleOnly: Boolean): List<CalendarInfo> =
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars._SYNC_ID),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?" + if (visibleOnly) " AND ${Calendars.VISIBLE} = 1" else "",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val account = cursor.getString(3).orEmpty()
                    val key = listOf(cursor.getString(1), cursor.getString(5)).firstOrNull { it?.contains('@') == true } ?: "local:$account:$id"
                    add(CalendarInfo(id, key, cursor.getString(2).orEmpty(), account, cursor.getInt(4)))
                }
            }
        }.orEmpty()

    private fun eventValues(taskId: String, spec: EventSpec) = ContentValues().apply {
        put(Events.TITLE, spec.title)
        put(Events.DESCRIPTION, eventDescription(spec.description, taskId))
        put(Events.DTSTART, spec.startMillis)
        put(Events.DTEND, spec.startMillis + EVENT_DURATION_MILLIS)
        put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(Events.HAS_ALARM, 1)
    }

    private fun insertReminder(eventId: Long, minutes: Int) {
        resolver.insert(
            Reminders.CONTENT_URI,
            ContentValues().apply {
                put(Reminders.EVENT_ID, eventId)
                put(Reminders.MINUTES, minutes)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            },
        )
    }

    private fun <T> guarded(block: () -> T): T? {
        if (!hasPermission()) return null
        return try {
            block()
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
