package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.CalendarEvent
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an empty store loads an empty board`() {
        assertEquals(Snapshot(emptyList(), null), TaskStore(File(folder.root, "board.json")).load())
    }

    @Test
    fun `saved tasks load back identically`() {
        val snapshot = Snapshot(
            tasks = listOf(
                Task("a", "Courses"),
                Task(
                    id = "b",
                    title = "Dentiste",
                    description = "Apporter la carte vitale",
                    column = Column.DOING,
                    reminder = Reminder(1_763_000_000_000, 1440, 7),
                    event = CalendarEvent(42, 7),
                ),
                Task(
                    id = "c",
                    title = "Impôts",
                    column = Column.DONE,
                    reminder = Reminder(1_700_000_000_000, 0, 3),
                    event = CalendarEvent(43, 3, pending = true),
                ),
            ),
            lastCalendarId = 7,
        )
        val file = File(folder.root, "board.json")

        TaskStore(file).save(snapshot)

        assertEquals(snapshot, TaskStore(file).load())
    }
}
