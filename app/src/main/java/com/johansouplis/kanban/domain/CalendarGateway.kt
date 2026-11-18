package com.johansouplis.kanban.domain

data class EventSpec(
    val title: String,
    val description: String,
    val startMillis: Long,
    val leadMinutes: Int,
)

/** What a write to an existing event did. UNAVAILABLE means the calendar was not reached, so the event is untouched. */
enum class CalendarWrite { APPLIED, GONE, UNAVAILABLE }

interface CalendarGateway {
    fun insert(calendarId: Long, spec: EventSpec): Long?

    fun update(eventId: Long, spec: EventSpec): CalendarWrite

    fun delete(eventId: Long): CalendarWrite
}
