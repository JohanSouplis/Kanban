package com.johansouplis.kanban.domain

/**
 * A task owns a calendar event exactly when it has a reminder and is not done.
 * Every change reconciles the event with that rule, so a missing or stale event heals itself.
 * When the calendar is unreachable the task keeps its event, marked pending, and [reconcile] finishes the job later.
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

    /** The task goes away even when its event cannot be removed. The caller warns the user before asking for that. */
    fun delete(tasks: List<Task>, id: String): List<Task> {
        tasks.find { it.id == id }?.event?.let { calendar.delete(it.id) }
        return tasks.filterNot { it.id == id }
    }

    fun reconcile(tasks: List<Task>): List<Task> =
        tasks.map { if (it.event?.pending == true) sync(it) else it }

    private fun sync(task: Task): Task {
        val reminder = task.reminder
        val event = task.event
        if (reminder == null || task.column == Column.DONE || (event == null && reminder.startMillis <= now())) {
            if (event == null || calendar.delete(event.id) != CalendarWrite.UNAVAILABLE) return task.copy(event = null)
            return task.copy(event = event.copy(pending = true))
        }
        val spec = EventSpec(task.title, task.description, reminder.startMillis, reminder.leadMinutes)
        if (event != null) {
            if (event.calendarId == reminder.calendarId) {
                when (calendar.update(event.id, spec)) {
                    CalendarWrite.APPLIED -> return task.copy(event = event.copy(pending = false))
                    CalendarWrite.UNAVAILABLE -> return task.copy(event = event.copy(pending = true))
                    CalendarWrite.GONE -> Unit
                }
            } else if (calendar.delete(event.id) == CalendarWrite.UNAVAILABLE) {
                return task.copy(event = event.copy(pending = true))
            }
        }
        val id = calendar.insert(reminder.calendarId, spec)
        return task.copy(event = id?.let { CalendarEvent(it, reminder.calendarId) })
    }
}
