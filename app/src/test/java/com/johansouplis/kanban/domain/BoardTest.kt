package com.johansouplis.kanban.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000L
private const val HOUR = 3_600_000L
private const val PERSO = 1L
private const val PARTAGE = 2L

private class FakeCalendar : CalendarGateway {
    data class Stored(val calendarId: Long, val spec: EventSpec)

    val events = mutableMapOf<Long, Stored>()
    var available = true
    private var nextId = 100L

    override fun insert(calendarId: Long, spec: EventSpec): Long? {
        if (!available) return null
        val id = nextId++
        events[id] = Stored(calendarId, spec)
        return id
    }

    override fun update(eventId: Long, spec: EventSpec): CalendarWrite {
        if (!available) return CalendarWrite.UNAVAILABLE
        val stored = events[eventId] ?: return CalendarWrite.GONE
        events[eventId] = stored.copy(spec = spec)
        return CalendarWrite.APPLIED
    }

    override fun delete(eventId: Long): CalendarWrite {
        if (!available) return CalendarWrite.UNAVAILABLE
        return if (events.remove(eventId) != null) CalendarWrite.APPLIED else CalendarWrite.GONE
    }
}

class BoardTest {
    private val calendar = FakeCalendar()
    private val board = Board(calendar) { NOW }

    private fun task(reminderAt: Long? = NOW + HOUR, calendarId: Long = PERSO) = Task(
        id = "t1",
        title = "Appeler le garage",
        description = "Pneus hiver",
        reminder = reminderAt?.let { Reminder(it, leadMinutes = 15, calendarId = calendarId) },
    )

    private fun List<Task>.only() = single()

    @Test
    fun `saving a task with a future reminder creates the event in the chosen calendar`() {
        val saved = board.save(emptyList(), task()).only()

        val stored = calendar.events.getValue(saved.event!!.id)
        assertEquals(PERSO, stored.calendarId)
        assertEquals(EventSpec("Appeler le garage", "Pneus hiver", NOW + HOUR, 15), stored.spec)
    }

    @Test
    fun `saving a task without reminder creates no event`() {
        val saved = board.save(emptyList(), task(reminderAt = null)).only()

        assertNull(saved.event)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `validating a task deletes its event`() {
        val tasks = board.save(emptyList(), task())

        val done = board.move(tasks, "t1", Column.DONE).only()

        assertEquals(Column.DONE, done.column)
        assertNull(done.event)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `moving between open columns keeps the same event`() {
        val tasks = board.save(emptyList(), task())
        val eventId = tasks.only().event!!.id

        val doing = board.move(tasks, "t1", Column.DOING).only()

        assertEquals(eventId, doing.event!!.id)
        assertEquals(setOf(eventId), calendar.events.keys)
    }

    @Test
    fun `reopening a done task with a future reminder recreates the event`() {
        val done = board.move(board.save(emptyList(), task()), "t1", Column.DONE)

        val reopened = board.move(done, "t1", Column.TODO).only()

        assertNotNull(reopened.event)
        assertEquals(setOf(reopened.event!!.id), calendar.events.keys)
    }

    @Test
    fun `reopening a done task whose reminder is past creates no event`() {
        val done = listOf(task(reminderAt = NOW - HOUR).copy(column = Column.DONE))

        val reopened = board.move(done, "t1", Column.TODO).only()

        assertNull(reopened.event)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `editing a task updates its event`() {
        val tasks = board.save(emptyList(), task())
        val edited = tasks.only().copy(title = "Appeler le garage Renault")

        val saved = board.save(tasks, edited).only()

        assertEquals(tasks.only().event, saved.event)
        assertEquals("Appeler le garage Renault", calendar.events.getValue(saved.event!!.id).spec.title)
    }

    @Test
    fun `changing the calendar moves the event`() {
        val tasks = board.save(emptyList(), task())
        val oldId = tasks.only().event!!.id

        val moved = board.save(tasks, task(calendarId = PARTAGE).copy(event = tasks.only().event)).only()

        assertEquals(PARTAGE, moved.event!!.calendarId)
        assertEquals(setOf(moved.event!!.id), calendar.events.keys)
        assertTrue(moved.event!!.id != oldId)
        assertEquals(PARTAGE, calendar.events.getValue(moved.event!!.id).calendarId)
    }

    @Test
    fun `removing the reminder deletes the event`() {
        val tasks = board.save(emptyList(), task())

        val saved = board.save(tasks, tasks.only().copy(reminder = null)).only()

        assertNull(saved.event)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `an event deleted outside the app is recreated on edit`() {
        val tasks = board.save(emptyList(), task())
        calendar.events.clear()

        val saved = board.save(tasks, tasks.only().copy(title = "Nouveau titre")).only()

        assertEquals("Nouveau titre", calendar.events.getValue(saved.event!!.id).spec.title)
    }

    @Test
    fun `validating a task whose event was deleted outside the app just clears it`() {
        val tasks = board.save(emptyList(), task())
        calendar.events.clear()

        val done = board.move(tasks, "t1", Column.DONE).only()

        assertNull(done.event)
    }

    @Test
    fun `deleting a task deletes its event`() {
        val tasks = board.save(emptyList(), task())

        val remaining = board.delete(tasks, "t1")

        assertTrue(remaining.isEmpty())
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `without calendar access the task is still saved`() {
        calendar.available = false

        val saved = board.save(emptyList(), task()).only()

        assertEquals("Appeler le garage", saved.title)
        assertNull(saved.event)
    }

    @Test
    fun `validating without calendar access leaves the event to delete once access is back`() {
        val tasks = board.save(emptyList(), task())
        calendar.available = false

        val done = board.move(tasks, "t1", Column.DONE)
        assertEquals(1, calendar.events.size)

        calendar.available = true
        val reconciled = board.reconcile(done).only()

        assertNull(reconciled.event)
        assertTrue(calendar.events.isEmpty())
    }

    @Test
    fun `reopening a task validated without access reuses its event instead of duplicating it`() {
        val tasks = board.save(emptyList(), task())
        val eventId = tasks.only().event!!.id
        calendar.available = false
        val done = board.move(tasks, "t1", Column.DONE)

        calendar.available = true
        val reopened = board.move(done, "t1", Column.TODO).only()

        assertEquals(setOf(eventId), calendar.events.keys)
        assertEquals(eventId, reopened.event!!.id)
    }

    @Test
    fun `an edit made without access reaches the same event once access is back`() {
        val tasks = board.save(emptyList(), task())
        val eventId = tasks.only().event!!.id
        calendar.available = false

        val edited = board.save(tasks, tasks.only().copy(title = "Appeler le garage Renault"))
        assertEquals("Appeler le garage", calendar.events.getValue(eventId).spec.title)

        calendar.available = true
        board.reconcile(edited)

        assertEquals(setOf(eventId), calendar.events.keys)
        assertEquals("Appeler le garage Renault", calendar.events.getValue(eventId).spec.title)
    }

    @Test
    fun `a calendar change made without access moves the event once access is back`() {
        val tasks = board.save(emptyList(), task())
        calendar.available = false
        val moved = board.save(tasks, task(calendarId = PARTAGE).copy(event = tasks.only().event))

        calendar.available = true
        val reconciled = board.reconcile(moved).only()

        assertEquals(listOf(PARTAGE), calendar.events.values.map { it.calendarId })
        assertEquals(PARTAGE, reconciled.event!!.calendarId)
    }

    @Test
    fun `reconciling without access changes nothing`() {
        val tasks = board.save(emptyList(), task())
        calendar.available = false
        val done = board.move(tasks, "t1", Column.DONE)

        assertEquals(done, board.reconcile(done))
        assertEquals(1, calendar.events.size)
    }

    @Test
    fun `deleting a task without access removes the task but not its event`() {
        val tasks = board.save(emptyList(), task())
        calendar.available = false

        val remaining = board.delete(tasks, "t1")

        assertTrue(remaining.isEmpty())
        assertEquals(1, calendar.events.size)
    }
}
