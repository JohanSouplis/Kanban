package com.johansouplis.kanban.domain

import org.junit.Assert.assertEquals
import org.junit.Test

private val reminder = Reminder(1_000L, 15, "parigots@group.calendar.google.com")

class LaneTest {
    private fun task(id: String, column: Column = Column.TODO, reminder: Reminder? = null, rank: Double = 0.0, createdAt: Long = 0) =
        Task(id = id, title = id, column = column, reminder = reminder, rank = rank, createdAt = createdAt)

    /** Applies [ranks] to [backlog] and reads the backlog back as the board shows it. */
    private fun shownAfter(backlog: List<Task>, ranks: Map<String, Double>) =
        backlog.map { it.copy(rank = ranks[it.id] ?: it.rank) }.inLane(Lane.BACKLOG).map(Task::id)

    @Test
    fun `a task to do without a planned date waits in the backlog`() {
        assertEquals(Lane.BACKLOG, task("a").lane)
        assertEquals(Lane.TODO, task("a", reminder = reminder).lane)
    }

    @Test
    fun `tasks in progress or done keep their column whatever their date`() {
        assertEquals(Lane.DOING, task("a", Column.DOING).lane)
        assertEquals(Lane.DOING, task("a", Column.DOING, reminder).lane)
        assertEquals(Lane.DONE, task("a", Column.DONE).lane)
        assertEquals(Lane.DONE, task("a", Column.DONE, reminder).lane)
    }

    @Test
    fun `each lane has its own order`() {
        val board = listOf(
            task("late", reminder = reminder.copy(startMillis = 3_000), createdAt = 1),
            task("soon", reminder = reminder.copy(startMillis = 2_000), createdAt = 2),
            task("second", rank = 1.0, createdAt = 1),
            task("first", rank = 0.0, createdAt = 3),
            task("tie", rank = 1.0, createdAt = 2),
            task("old", Column.DONE, createdAt = 1),
            task("recent", Column.DONE, createdAt = 2),
            task("started-late", Column.DOING, createdAt = 2),
            task("started-early", Column.DOING, createdAt = 1),
        )

        assertEquals(listOf("soon", "late"), board.inLane(Lane.TODO).map(Task::id))
        assertEquals(listOf("first", "second", "tie"), board.inLane(Lane.BACKLOG).map(Task::id))
        assertEquals(listOf("started-early", "started-late"), board.inLane(Lane.DOING).map(Task::id))
        assertEquals(listOf("recent", "old"), board.inLane(Lane.DONE).map(Task::id))
    }

    @Test
    fun `a task dragged between two others lands between them and only it changes`() {
        val backlog = listOf(task("a", rank = 0.0), task("b", rank = 1.0), task("c", rank = 2.0))

        val ranks = reorderBacklog(backlog, from = 2, to = 1)

        assertEquals(setOf("c"), ranks.keys)
        assertEquals(listOf("a", "c", "b"), shownAfter(backlog, ranks))
    }

    @Test
    fun `a task dragged to an end goes past the task there`() {
        val backlog = listOf(task("a", rank = 0.0), task("b", rank = 1.0), task("c", rank = 2.0))

        assertEquals(listOf("c", "a", "b"), shownAfter(backlog, reorderBacklog(backlog, from = 2, to = 0)))
        assertEquals(listOf("b", "c", "a"), shownAfter(backlog, reorderBacklog(backlog, from = 0, to = 2)))
    }

    @Test
    fun `a task dropped where it was changes nothing`() {
        val backlog = listOf(task("a", rank = 0.0), task("b", rank = 1.0))

        assertEquals(emptyMap<String, Double>(), reorderBacklog(backlog, from = 1, to = 1))
    }

    @Test
    fun `tasks from before the backlog all share one rank and still reorder`() {
        val backlog = listOf(task("a", createdAt = 1), task("b", createdAt = 2), task("c", createdAt = 3))

        assertEquals(listOf("a", "c", "b"), shownAfter(backlog, reorderBacklog(backlog, from = 2, to = 1)))
    }

    @Test
    fun `a new task without date goes to the bottom of the backlog`() {
        val board = listOf(task("a", rank = 4.0), task("b", rank = 7.5), task("planned", reminder = reminder, rank = 40.0))

        assertEquals(8.5, enterBacklog(task("new"), board).rank, 0.0)
    }

    @Test
    fun `the first task of an empty backlog takes rank 0`() {
        assertEquals(0.0, enterBacklog(task("new", rank = 3.0), emptyList()).rank, 0.0)
    }

    @Test
    fun `a task losing its date or coming back undone goes to the bottom of the backlog`() {
        val planned = task("planned", reminder = reminder, rank = 0.0)
        val done = task("done", Column.DONE, rank = 0.0)
        val board = listOf(task("a", rank = 5.0), planned, done)

        assertEquals(6.0, enterBacklog(planned.copy(reminder = null), board).rank, 0.0)
        assertEquals(6.0, enterBacklog(done.copy(column = Column.TODO), board).rank, 0.0)
    }

    @Test
    fun `a task already in the backlog keeps its place when edited`() {
        val board = listOf(task("a", rank = 0.0), task("b", rank = 1.0))

        assertEquals(0.0, enterBacklog(board[0].copy(title = "Renamed"), board).rank, 0.0)
    }

    @Test
    fun `a task outside the backlog is left as is`() {
        val started = task("a", Column.DOING, rank = 3.0)

        assertEquals(started, enterBacklog(started, listOf(task("b", rank = 9.0))))
    }
}
