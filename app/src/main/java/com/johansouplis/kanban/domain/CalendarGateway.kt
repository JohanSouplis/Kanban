package com.johansouplis.kanban.domain

data class EventSpec(
    val title: String,
    val description: String,
    val startMillis: Long,
    val leadMinutes: Int,
)

interface CalendarGateway {
    fun insert(calendarId: Long, spec: EventSpec): Long?

    /** Returns false when the event no longer exists. */
    fun update(eventId: Long, spec: EventSpec): Boolean

    fun delete(eventId: Long)
}
