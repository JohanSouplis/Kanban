package com.johansouplis.kanban.domain

/**
 * What the board shows, derived from the stored [Column]: the planned date is the reminder,
 * so a TODO task without one waits in the backlog.
 */
enum class Lane(val label: String) {
    TODO("À faire"),
    BACKLOG("Backlog"),
    DOING("En cours"),
    DONE("Fait"),
}

val Task.lane: Lane
    get() = when (column) {
        Column.TODO -> if (reminder == null) Lane.BACKLOG else Lane.TODO
        Column.DOING -> Lane.DOING
        Column.DONE -> Lane.DONE
    }

fun List<Task>.inLane(lane: Lane): List<Task> {
    val tasks = filter { it.lane == lane }
    return when (lane) {
        Lane.TODO -> tasks.sortedBy { it.reminder?.startMillis }
        Lane.BACKLOG -> tasks.sortedWith(compareBy(Task::rank, Task::createdAt))
        Lane.DOING -> tasks.sortedBy(Task::createdAt)
        Lane.DONE -> tasks.sortedByDescending(Task::createdAt)
    }
}

/**
 * New ranks after moving the backlog's task at [from] to [to], both indices in [backlog] as displayed.
 * Usually only the moved task changes, to the middle of its new neighbours. Tasks from before 1.2 all share rank 0,
 * and no middle separates equal neighbours, so then the whole backlog is renumbered in its new order.
 */
fun reorderBacklog(backlog: List<Task>, from: Int, to: Int): Map<String, Double> {
    if (from == to) return emptyMap()
    val moved = backlog[from]
    val order = backlog.toMutableList().apply { add(to, removeAt(from)) }
    val before = order.getOrNull(to - 1)?.rank
    val after = order.getOrNull(to + 1)?.rank
    val rank = when {
        before != null && after != null -> (before + after) / 2
        before != null -> before + 1
        after != null -> after - 1
        else -> 0.0
    }
    val separated = (before == null || before < rank) && (after == null || rank < after)
    if (separated) return mapOf(moved.id to rank)
    return order.mapIndexedNotNull { index, task -> (task.id to index.toDouble()).takeIf { task.rank != it.second } }.toMap()
}

/** A task entering the backlog goes to its bottom; one already there keeps its place. */
fun enterBacklog(task: Task, board: List<Task>): Task {
    if (task.lane != Lane.BACKLOG) return task
    val previous = board.find { it.id == task.id }
    if (previous?.lane == Lane.BACKLOG) return task
    val last = board.filter { it.id != task.id && it.lane == Lane.BACKLOG }.maxOfOrNull(Task::rank) ?: return task.copy(rank = 0.0)
    return task.copy(rank = last + 1)
}
