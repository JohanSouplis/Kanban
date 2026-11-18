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
import com.johansouplis.kanban.domain.CalendarWrite
import com.johansouplis.kanban.domain.EventSpec
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

private const val EVENT_DURATION_MILLIS = 30 * 60 * 1000L

class CalendarRepository(private val context: Context) : CalendarGateway {
    private val resolver get() = context.contentResolver

    fun hasPermission(): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    fun writableCalendars(): List<CalendarInfo> = guarded {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(CalendarInfo(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getInt(3)))
                }
            }
        }
    }.orEmpty()

    override fun insert(calendarId: Long, spec: EventSpec): Long? = guarded {
        val values = eventValues(spec).apply { put(Events.CALENDAR_ID, calendarId) }
        val id = resolver.insert(Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        id?.also { insertReminder(it, spec.leadMinutes) }
    }

    override fun update(eventId: Long, spec: EventSpec): CalendarWrite = guarded {
        val rows = resolver.update(
            Events.CONTENT_URI,
            eventValues(spec),
            "${Events._ID} = ? AND ${Events.DELETED} = 0",
            arrayOf(eventId.toString()),
        )
        if (rows > 0) {
            resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
            insertReminder(eventId, spec.leadMinutes)
        }
        written(rows)
    } ?: CalendarWrite.UNAVAILABLE

    override fun delete(eventId: Long): CalendarWrite = guarded {
        written(resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null))
    } ?: CalendarWrite.UNAVAILABLE

    private fun written(rows: Int) = if (rows > 0) CalendarWrite.APPLIED else CalendarWrite.GONE

    private fun eventValues(spec: EventSpec) = ContentValues().apply {
        put(Events.TITLE, spec.title)
        put(Events.DESCRIPTION, spec.description)
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
