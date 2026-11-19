package com.johansouplis.kanban.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_000_000L
private const val HOUR = 3_600_000L
private const val PARIGOTS = "parigots@group.calendar.google.com"
private const val PERSO = "johan@gmail.com"

/** One phone's calendar provider. [sharedWith] stands in for Google sync, which copies events between phones later. */
private class FakeCalendar(private val keys: Set<String> = setOf(PARIGOTS, PERSO)) : CalendarGateway {
    data class Stored(val calendarKey: String, val taskId: String, val spec: EventSpec)

    val events = mutableMapOf<Long, Stored>()
    var available = true
    private var nextId = 100L

    fun syncFrom(other: FakeCalendar) {
        events.clear()
        other.events.values.forEach { events[nextId++] = it }
    }

    fun of(taskId: String) = events.values.filter { it.taskId == taskId }

    override fun find(taskId: String): List<FoundEvent>? {
        if (!available) return null
        return events.filterValues { it.taskId == taskId }.map { (id, stored) -> FoundEvent(id, stored.calendarKey) }
    }

    override fun insert(calendarKey: String, taskId: String, spec: EventSpec): Insertion {
        if (!available) return Insertion.UNAVAILABLE
        if (calendarKey !in keys) return Insertion.NO_CALENDAR
        events[nextId++] = Stored(calendarKey, taskId, spec)
        return Insertion.INSERTED
    }

    override fun update(eventId: Long, taskId: String, spec: EventSpec): Boolean {
        if (!available) return false
        val stored = events[eventId] ?: return false
        events[eventId] = stored.copy(spec = spec)
        return true
    }

    override fun delete(eventId: Long): Boolean {
        if (!available) return false
        events.remove(eventId)
        return true
    }
}

/** The shared board in Firestore. Phones read it back through [Board.receive]. */
private class FakeCloud : TaskRepository {
    val tasks = linkedMapOf<String, Task>()

    override fun put(task: Task) {
        tasks[task.id] = task
    }

    override fun delete(id: String) {
        tasks.remove(id)
    }
}

class BoardTest {
    private val cloud = FakeCloud()
    private val johanCalendar = FakeCalendar()
    private val partnerCalendar = FakeCalendar()
    private val johan = Board(johanCalendar, cloud, me = "johan") { NOW }
    private val partner = Board(partnerCalendar, cloud, me = "partner") { NOW }

    private fun task(reminderAt: Long? = NOW + HOUR, calendarKey: String = PARIGOTS) = Task(
        id = "t1",
        title = "Appeler le garage",
        description = "Pneus hiver",
        reminder = reminderAt?.let { Reminder(it, leadMinutes = 15, calendarKey = calendarKey) },
    )

    private fun Board.pull() = receive(cloud.tasks.values.toList())

    private fun Board.only() = tasks.single()

    @Test
    fun `saving a task with a future reminder creates its event in the chosen calendar`() {
        johan.save(task())

        val stored = johanCalendar.of("t1").single()
        assertEquals(PARIGOTS, stored.calendarKey)
        assertEquals(EventSpec("Appeler le garage", "Pneus hiver", NOW + HOUR, 15), stored.spec)
        assertEquals(EventPlacement(PARIGOTS, "johan"), cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `saving a task without reminder creates no event`() {
        johan.save(task(reminderAt = null))

        assertEquals("Appeler le garage", cloud.tasks.getValue("t1").title)
        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `validating a task deletes its event`() {
        johan.save(task())

        johan.move("t1", Column.DONE)

        assertEquals(Column.DONE, cloud.tasks.getValue("t1").column)
        assertNull(cloud.tasks.getValue("t1").event)
        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `moving between open columns keeps the same event`() {
        johan.save(task())
        val eventIds = johanCalendar.events.keys.toSet()

        johan.move("t1", Column.DOING)

        assertEquals(eventIds, johanCalendar.events.keys)
    }

    @Test
    fun `reopening a done task with a future reminder recreates the event`() {
        johan.save(task())
        johan.move("t1", Column.DONE)

        johan.move("t1", Column.TODO)

        assertEquals(1, johanCalendar.of("t1").size)
    }

    @Test
    fun `reopening a done task whose reminder is past creates no event`() {
        johan.save(task(reminderAt = NOW - HOUR).copy(column = Column.DONE))

        johan.move("t1", Column.TODO)

        assertTrue(johanCalendar.events.isEmpty())
        assertNull(cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `editing a task updates its event`() {
        johan.save(task())

        johan.save(johan.only().copy(title = "Appeler le garage Renault"))

        assertEquals("Appeler le garage Renault", johanCalendar.of("t1").single().spec.title)
    }

    @Test
    fun `changing the calendar moves the event`() {
        johan.save(task())

        johan.save(task(calendarKey = PERSO).copy(event = johan.only().event))

        assertEquals(listOf(PERSO), johanCalendar.of("t1").map { it.calendarKey })
        assertEquals(EventPlacement(PERSO, "johan"), cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `removing the reminder deletes the event`() {
        johan.save(task())

        johan.save(johan.only().copy(reminder = null))

        assertTrue(johanCalendar.events.isEmpty())
        assertNull(cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `an event deleted by hand on the phone that created it is recreated on edit`() {
        johan.save(task())
        johanCalendar.events.clear()

        johan.save(johan.only().copy(title = "Nouveau titre"))

        assertEquals("Nouveau titre", johanCalendar.of("t1").single().spec.title)
    }

    @Test
    fun `deleting a task deletes its event`() {
        johan.save(task())

        johan.delete("t1")

        assertTrue(cloud.tasks.isEmpty())
        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `a task validated on the other phone before Google synced the event still loses its event`() {
        johan.save(task())
        partner.pull()

        partner.move("t1", Column.DONE)
        johan.pull()

        assertTrue(johanCalendar.events.isEmpty())
        assertEquals(Column.DONE, johan.only().column)
    }

    @Test
    fun `a task validated on the other phone after Google synced the event deletes it on both phones`() {
        johan.save(task())
        partnerCalendar.syncFrom(johanCalendar)
        partner.pull()

        partner.move("t1", Column.DONE)
        johan.pull()

        assertTrue(partnerCalendar.events.isEmpty())
        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `a task deleted on the other phone loses its event here`() {
        johan.save(task())
        partner.pull()

        partner.delete("t1")
        johan.pull()

        assertTrue(johan.tasks.isEmpty())
        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `receiving a task with a reminder never inserts an event`() {
        johan.save(task())

        partner.pull()

        assertTrue(partnerCalendar.events.isEmpty())
    }

    @Test
    fun `receiving a task the other phone could not put in the calendar inserts nothing here`() {
        johanCalendar.available = false
        johan.save(task())

        partner.pull()

        assertTrue(partnerCalendar.events.isEmpty())
        assertNull(cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `editing on the other phone before Google synced the event updates it here without duplicating it`() {
        johan.save(task())
        partner.pull()

        partner.save(partner.only().copy(title = "Appeler le garage Renault"))
        johan.pull()

        assertTrue(partnerCalendar.events.isEmpty())
        assertEquals("Appeler le garage Renault", johanCalendar.of("t1").single().spec.title)
        assertEquals(EventPlacement(PARIGOTS, "johan"), cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `reopening on the other phone a task validated here gives it one event`() {
        johan.save(task())
        johan.move("t1", Column.DONE)
        partner.pull()

        partner.move("t1", Column.TODO)
        johan.pull()

        assertEquals(1, partnerCalendar.of("t1").size)
        assertTrue(johanCalendar.events.isEmpty())
        assertEquals(EventPlacement(PARIGOTS, "partner"), cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `a calendar missing on this phone leaves the task without event and nothing pending`() {
        val phone = Board(FakeCalendar(keys = setOf(PERSO)), cloud, me = "partner") { NOW }

        phone.save(task())

        assertNull(cloud.tasks.getValue("t1").event)
        assertTrue(phone.pending.isEmpty())
    }

    @Test
    fun `without calendar access the task is saved and its event created once access is back`() {
        johanCalendar.available = false

        johan.save(task())
        assertEquals("Appeler le garage", cloud.tasks.getValue("t1").title)
        assertEquals(setOf("t1"), johan.pending)

        johanCalendar.available = true
        johan.reconcile()

        assertEquals(1, johanCalendar.of("t1").size)
        assertTrue(johan.pending.isEmpty())
    }

    @Test
    fun `validating without calendar access deletes the event once access is back`() {
        johan.save(task())
        johanCalendar.available = false

        johan.move("t1", Column.DONE)
        assertEquals(1, johanCalendar.events.size)

        johanCalendar.available = true
        johan.reconcile()

        assertTrue(johanCalendar.events.isEmpty())
        assertNull(cloud.tasks.getValue("t1").event)
    }

    @Test
    fun `reopening a task validated without access reuses its event instead of duplicating it`() {
        johan.save(task())
        val eventIds = johanCalendar.events.keys.toSet()
        johanCalendar.available = false
        johan.move("t1", Column.DONE)

        johanCalendar.available = true
        johan.move("t1", Column.TODO)

        assertEquals(eventIds, johanCalendar.events.keys)
    }

    @Test
    fun `reconciling without access changes nothing`() {
        johan.save(task())
        johanCalendar.available = false
        johan.move("t1", Column.DONE)
        val stored = cloud.tasks.toMap()

        johan.reconcile()

        assertEquals(stored, cloud.tasks)
        assertEquals(1, johanCalendar.events.size)
        assertEquals(setOf("t1"), johan.pending)
    }

    @Test
    fun `deleting a task without access deletes its event once access is back`() {
        johan.save(task())
        johanCalendar.available = false

        johan.delete("t1")
        assertTrue(cloud.tasks.isEmpty())
        assertEquals(1, johanCalendar.events.size)

        johanCalendar.available = true
        johan.reconcile()

        assertTrue(johanCalendar.events.isEmpty())
    }

    @Test
    fun `a change received without access is applied to this phone's event once access is back`() {
        johan.save(task())
        partner.pull()
        johanCalendar.available = false

        partner.move("t1", Column.DONE)
        johan.pull()
        assertEquals(1, johanCalendar.events.size)

        johanCalendar.available = true
        johan.reconcile()

        assertTrue(johanCalendar.events.isEmpty())
    }
}
