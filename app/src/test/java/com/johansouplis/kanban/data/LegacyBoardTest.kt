package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyBoardTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val version10File = """
        {"tasks":[
          {"id":"a","title":"Appeler le garage","description":"Pneus hiver","column":"DOING",
           "reminder":{"startMillis":1700000000000,"leadMinutes":15,"calendarId":7},
           "event":{"id":42,"calendarId":7,"pending":false}},
          {"id":"b","title":"Courses","description":"","column":"TODO","reminder":null,"event":null}
        ],"lastCalendarId":7}
    """

    @Test
    fun `reads the tasks saved by version 1_0`() {
        val file = folder.newFile("board.json").apply { writeText(version10File) }

        val tasks = LegacyBoard(file).load()

        assertEquals(
            listOf(
                LegacyTask("a", "Appeler le garage", "Pneus hiver", Column.DOING, LegacyReminder(1700000000000, 15, 7), 42),
                LegacyTask("b", "Courses", "", Column.TODO, null, null),
            ),
            tasks,
        )
    }

    @Test
    fun `no file means nothing to migrate`() {
        assertTrue(LegacyBoard(folder.root.resolve("board.json")).load().isEmpty())
    }

    @Test
    fun `migration swaps the local calendar id for the shared calendar key`() {
        val old = LegacyTask("a", "Appeler le garage", "", Column.TODO, LegacyReminder(1700000000000, 15, 7), 42)

        val task = old.migrate(mapOf(7L to "parigots@group.calendar.google.com")::get, createdAt = 5)

        assertEquals(
            Task("a", "Appeler le garage", "", Column.TODO, Reminder(1700000000000, 15, "parigots@group.calendar.google.com"), createdAt = 5),
            task,
        )
    }

    @Test
    fun `migration drops a reminder whose calendar is not found on this phone`() {
        val old = LegacyTask("a", "Appeler le garage", "", Column.TODO, LegacyReminder(1700000000000, 15, 7), 42)

        assertEquals(null, old.migrate({ null }, createdAt = 5).reminder)
    }
}
