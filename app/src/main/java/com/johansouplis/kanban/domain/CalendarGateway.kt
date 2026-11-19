package com.johansouplis.kanban.domain

data class EventSpec(
    val title: String,
    val description: String,
    val startMillis: Long,
    val leadMinutes: Int,
)

/** An event on this phone that carries a task's marker. [id] is local to this phone. */
data class FoundEvent(val id: Long, val calendarKey: String)

enum class Insertion { INSERTED, NO_CALENDAR, UNAVAILABLE }

/** Events are tied to a task by a marker written in the event itself, so Google sync carries the link to every phone. */
interface CalendarGateway {
    /** Null when the calendar cannot be reached (no permission). */
    fun find(taskId: String): List<FoundEvent>?

    fun insert(calendarKey: String, taskId: String, spec: EventSpec): Insertion

    fun update(eventId: Long, taskId: String, spec: EventSpec): Boolean

    fun delete(eventId: Long): Boolean
}

interface TaskRepository {
    fun put(task: Task)

    fun delete(id: String)
}
