package com.johansouplis.kanban.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTimeFormat = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH:mm", Locale.FRENCH)

fun formatDateTime(millis: Long): String =
    dateTimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun leadTimeLabel(minutes: Int): String = when {
    minutes == 0 -> "À l'heure de l'événement"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} jour avant"
    minutes % 60 == 0 -> "${minutes / 60} h avant"
    else -> "$minutes min avant"
}
