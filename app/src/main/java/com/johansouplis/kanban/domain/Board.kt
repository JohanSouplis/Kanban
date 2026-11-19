package com.johansouplis.kanban.domain

/**
 * One phone's view of the shared board.
 *
 * Rule: a task has one event when it has a reminder and is not done, and none otherwise.
 * The phone where the user acts applies the rule in full and records in the task where the event lives.
 * A change received from the other phone only updates or deletes the events already on this phone and never inserts one:
 * Google sync is slow, so the other phone may simply not have received the event yet, and inserting there would duplicate it.
 * Task ids whose events could not be reached (no calendar permission) stay [pending] until [reconcile] succeeds.
 */
class Board(
    private val calendar: CalendarGateway,
    private val repository: TaskRepository,
    private val me: String,
    pending: Set<String> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    var tasks: List<Task> = emptyList()
        private set

    var pending: Set<String> = pending
        private set

    fun save(task: Task) = act(task.id, task)

    fun move(id: String, column: Column) {
        tasks.find { it.id == id }?.let { act(id, it.copy(column = column)) }
    }

    fun delete(id: String) = act(id, null)

    fun reconcile() = pending.forEach { id -> act(id, tasks.find { it.id == id }) }

    /** Takes the board as stored remotely. Includes this phone's own writes, which are already known and so skipped. */
    fun receive(remote: List<Task>) {
        val before = tasks.associateBy { it.id }
        tasks = remote
        remote.filter { before[it.id] != it }.forEach { converge(it.id, it) }
        (before.keys - remote.map { it.id }.toSet()).forEach { converge(it, null) }
    }

    private fun act(id: String, task: Task?) {
        pending = pending - id
        val kept = converge(id, task)
        val result = when {
            task == null -> null
            kept == null -> task
            else -> place(task, kept)
        }
        val previous = tasks.find { it.id == id }
        if (result == previous) return
        if (result == null) {
            tasks = tasks.filterNot { it.id == id }
            repository.delete(id)
        } else {
            tasks = if (previous == null) tasks + result else tasks.map { if (it.id == id) result else it }
            repository.put(result)
        }
    }

    /** Updates or deletes this phone's events for the task. Returns whether one was kept, or null when unreachable. */
    private fun converge(id: String, task: Task?): Boolean? {
        val found = calendar.find(id) ?: return unreachable(id)
        val wanted = task?.wantedReminder()
        val spec = if (task != null && wanted != null) task.spec(wanted) else null
        var reached = true
        var kept = false
        for (event in found) {
            val applied = if (spec != null && event.calendarKey == wanted?.calendarKey) {
                kept = true
                calendar.update(event.id, id, spec)
            } else {
                calendar.delete(event.id)
            }
            reached = reached && applied
        }
        return if (reached) kept else unreachable(id)
    }

    private fun place(task: Task, kept: Boolean): Task {
        val wanted = task.wantedReminder() ?: return task.copy(event = null)
        val placement = task.event?.takeIf { it.calendarKey == wanted.calendarKey }
        return when {
            kept -> task.copy(event = placement ?: EventPlacement(wanted.calendarKey, me))
            placement != null && placement.placedBy != me -> task
            wanted.startMillis <= now() -> task.copy(event = null)
            else -> when (calendar.insert(wanted.calendarKey, task.id, task.spec(wanted))) {
                Insertion.INSERTED -> task.copy(event = EventPlacement(wanted.calendarKey, me))
                Insertion.NO_CALENDAR -> task.copy(event = null)
                Insertion.UNAVAILABLE -> task.also { unreachable(task.id) }
            }
        }
    }

    private fun unreachable(id: String): Nothing? {
        pending = pending + id
        return null
    }
}

private fun Task.wantedReminder(): Reminder? = reminder?.takeIf { column != Column.DONE }

private fun Task.spec(reminder: Reminder) = EventSpec(title, description, reminder.startMillis, reminder.leadMinutes)
