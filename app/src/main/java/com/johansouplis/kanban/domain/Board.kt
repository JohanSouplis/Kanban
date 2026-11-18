package com.johansouplis.kanban.domain

/**
 * A task owns a calendar event exactly when it has a reminder and is not done.
 * Every change reconciles the event with that rule, so a missing or stale event heals itself.
 */
class Board(
    private val calendar: CalendarGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun save(tasks: List<Task>, task: Task): List<Task> {
        val synced = sync(task)
        return if (tasks.any { it.id == task.id }) {
            tasks.map { if (it.id == task.id) synced else it }
        } else {
            tasks + synced
        }
    }

    fun move(tasks: List<Task>, id: String, column: Column): List<Task> =
        tasks.map { if (it.id == id) sync(it.copy(column = column)) else it }

    fun delete(tasks: List<Task>, id: String): List<Task> {
        tasks.find { it.id == id }?.event?.let { calendar.delete(it.id) }
        return tasks.filterNot { it.id == id }
    }

    private fun sync(task: Task): Task {
        val reminder = task.reminder
        val event = task.event
        if (reminder == null || task.column == Column.DONE || (event == null && reminder.startMillis <= now())) {
            event?.let { calendar.delete(it.id) }
            return task.copy(event = null)
        }
        val spec = EventSpec(task.title, task.description, reminder.startMillis, reminder.leadMinutes)
        if (event != null) {
            if (event.calendarId == reminder.calendarId && calendar.update(event.id, spec)) return task
            if (event.calendarId != reminder.calendarId) calendar.delete(event.id)
        }
        val id = calendar.insert(reminder.calendarId, spec)
        return task.copy(event = id?.let { CalendarEvent(it, reminder.calendarId) })
    }
}
