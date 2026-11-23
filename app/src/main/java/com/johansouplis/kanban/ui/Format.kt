package com.johansouplis.kanban.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormat = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH)

fun formatDateTime(millis: Long): String =
    dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** « Aujourd'hui 14:00 », « Demain 09:00 », otherwise the full date. */
fun formatRelative(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (time.toLocalDate()) {
        today -> "Aujourd'hui ${timeFormat.format(time)}"
        today.plusDays(1) -> "Demain ${timeFormat.format(time)}"
        else -> dateTimeFormat.format(time)
    }
}

fun leadTimeLabel(minutes: Int): String = when {
    minutes == 0 -> "À l'heure de l'événement"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} jour avant"
    minutes % 60 == 0 -> "${minutes / 60} h avant"
    else -> "$minutes min avant"
}
