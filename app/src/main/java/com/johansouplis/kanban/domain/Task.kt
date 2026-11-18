package com.johansouplis.kanban.domain

enum class Column(val label: String) {
    TODO("À faire"),
    DOING("En cours"),
    DONE("Fait"),
}

data class Reminder(val startMillis: Long, val leadMinutes: Int, val calendarId: Long)

data class CalendarEvent(val id: Long, val calendarId: Long)

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val column: Column = Column.TODO,
    val reminder: Reminder? = null,
    val event: CalendarEvent? = null,
)

val LEAD_TIMES_MINUTES = listOf(0, 5, 15, 30, 60, 24 * 60)
