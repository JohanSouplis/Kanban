package com.johansouplis.kanban.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class FormatTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val now = millis(2026, 11, 23, 10, 30)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `today and tomorrow are named, other days are dated`() {
        assertEquals("Aujourd'hui 14:00", formatRelative(millis(2026, 11, 23, 14, 0), now, zone))
        assertEquals("Aujourd'hui 08:00", formatRelative(millis(2026, 11, 23, 8, 0), now, zone))
        assertEquals("Demain 09:05", formatRelative(millis(2026, 11, 24, 9, 5), now, zone))
        assertEquals("mer. 25 nov. à 09:05", formatRelative(millis(2026, 11, 25, 9, 5), now, zone))
    }
}
