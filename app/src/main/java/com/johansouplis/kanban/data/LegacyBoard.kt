package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import org.json.JSONObject
import java.io.File

/** A task as version 1.0 stored it on one phone, with local calendar and event ids. */
data class LegacyTask(
    val id: String,
    val title: String,
    val description: String,
    val column: Column,
    val reminder: LegacyReminder?,
    val eventId: Long?,
) {
    /** The reminder is dropped when its calendar is not found here: its event has no shared key. */
    fun migrate(keyOf: (calendarId: Long) -> String?, createdAt: Long) = Task(
        id = id,
        title = title,
        description = description,
        column = column,
        reminder = reminder?.let { r -> keyOf(r.calendarId)?.let { Reminder(r.startMillis, r.leadMinutes, it) } },
        createdAt = createdAt,
    )
}

data class LegacyReminder(val startMillis: Long, val leadMinutes: Int, val calendarId: Long)

/** The `board.json` of version 1.0, read once to move its tasks into the shared board. */
class LegacyBoard(private val file: File) {
    fun load(): List<LegacyTask> {
        if (!file.exists()) return emptyList()
        val tasks = JSONObject(file.readText()).getJSONArray("tasks")
        return List(tasks.length()) { legacyTaskFromJson(tasks.getJSONObject(it)) }
    }

    fun discard() {
        file.delete()
    }
}

fun legacyTaskFromJson(json: JSONObject) = LegacyTask(
    id = json.getString("id"),
    title = json.getString("title"),
    description = json.optString("description"),
    column = Column.valueOf(json.getString("column")),
    reminder = json.optJSONObject("reminder")?.let {
        LegacyReminder(it.getLong("startMillis"), it.getInt("leadMinutes"), it.getLong("calendarId"))
    },
    eventId = json.optJSONObject("event")?.getLong("id"),
)
