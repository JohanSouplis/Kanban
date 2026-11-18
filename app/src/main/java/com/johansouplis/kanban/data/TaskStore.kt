package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.CalendarEvent
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Snapshot(val tasks: List<Task>, val lastCalendarId: Long?)

class TaskStore(private val file: File) {
    fun load(): Snapshot {
        if (!file.exists()) return Snapshot(emptyList(), null)
        val json = JSONObject(file.readText())
        val tasks = json.getJSONArray("tasks").let { array -> List(array.length()) { taskFromJson(array.getJSONObject(it)) } }
        return Snapshot(tasks, json.optLongOrNull("lastCalendarId"))
    }

    fun save(snapshot: Snapshot) {
        val json = JSONObject()
            .put("tasks", JSONArray(snapshot.tasks.map(::taskToJson)))
            .put("lastCalendarId", snapshot.lastCalendarId)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
}

fun taskFromJson(json: JSONObject) = Task(
    id = json.getString("id"),
    title = json.getString("title"),
    description = json.optString("description"),
    column = Column.valueOf(json.getString("column")),
    reminder = json.optJSONObject("reminder")?.let {
        Reminder(it.getLong("startMillis"), it.getInt("leadMinutes"), it.getLong("calendarId"))
    },
    event = json.optJSONObject("event")?.let { CalendarEvent(it.getLong("id"), it.getLong("calendarId"), it.optBoolean("pending")) },
)

fun taskToJson(task: Task) = JSONObject()
    .put("id", task.id)
    .put("title", task.title)
    .put("description", task.description)
    .put("column", task.column.name)
    .put("reminder", task.reminder?.let {
        JSONObject().put("startMillis", it.startMillis).put("leadMinutes", it.leadMinutes).put("calendarId", it.calendarId)
    })
    .put("event", task.event?.let { JSONObject().put("id", it.id).put("calendarId", it.calendarId).put("pending", it.pending) })
