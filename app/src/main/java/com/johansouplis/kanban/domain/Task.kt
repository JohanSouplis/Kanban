package com.johansouplis.kanban.domain

enum class Column(val label: String) {
    TODO("À faire"),
    DOING("En cours"),
    DONE("Fait"),
}

/**
 * [calendarKey] names the calendar the same way on every phone (a Google calendar's id), unlike its local row id.
 */
data class Reminder(val startMillis: Long, val leadMinutes: Int, val calendarKey: String)

/**
 * Which calendar holds the task's event and which member created it there.
 * Another phone may not have received the event from Google yet, so only [placedBy] treats its absence as a deletion.
 */
data class EventPlacement(val calendarKey: String, val placedBy: String)

/** A file joined to a task. Its bytes live apart from the task, in the board's `files`, and are read on demand. */
data class Attachment(val id: String, val name: String, val mimeType: String, val size: Int)

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val column: Column = Column.TODO,
    val reminder: Reminder? = null,
    val event: EventPlacement? = null,
    val createdAt: Long = 0,
    /** Position in the backlog, lowest first. Only meaningful while the task is in [Lane.BACKLOG]. */
    val rank: Double = 0.0,
    val attachments: List<Attachment> = emptyList(),
)

val LEAD_TIMES_MINUTES = listOf(0, 5, 15, 30, 60, 24 * 60)
