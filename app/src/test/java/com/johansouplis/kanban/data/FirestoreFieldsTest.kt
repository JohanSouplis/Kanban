package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.EventPlacement
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FirestoreFieldsTest {
    private val task = Task(
        id = "t1",
        title = "Appeler le garage",
        description = "Pneus hiver",
        column = Column.DOING,
        reminder = Reminder(1_700_000_000_000, 15, "parigots@group.calendar.google.com"),
        event = EventPlacement("parigots@group.calendar.google.com", "uid-johan"),
        createdAt = 1_699_000_000_000,
    )

    /** Firestore hands every integer back as a Long. */
    private fun Map<String, Any?>.asStored(): Map<String, Any?> = mapValues { (_, value) ->
        when (value) {
            is Int -> value.toLong()
            is Map<*, *> -> value.mapKeys { it.key as String }.asStored()
            else -> value
        }
    }

    @Test
    fun `a task survives the round trip through Firestore`() {
        assertEquals(task, taskFromFields("t1", taskFields(task).asStored()))
    }

    @Test
    fun `a task without reminder nor event survives the round trip`() {
        val plain = task.copy(reminder = null, event = null)

        assertEquals(plain, taskFromFields("t1", taskFields(plain).asStored()))
    }

    @Test
    fun `a document this version cannot read is skipped`() {
        assertNull(taskFromFields("t1", taskFields(task) + ("column" to "ARCHIVED")))
        assertNull(taskFromFields("t1", taskFields(task) - "title"))
        assertNull(taskFromFields("t1", taskFields(task) + ("reminder" to mapOf("startMillis" to 1L))))
    }

    @Test
    fun `join codes use six unambiguous characters`() {
        val codes = List(200) { newJoinCode(Random(it)) }

        assertTrue(codes.all { it.length == 6 && it.none { c -> c in "01IOL" } && it == normalizeJoinCode(it) })
    }

    @Test
    fun `a typed join code is normalized`() {
        assertEquals("ABC234", normalizeJoinCode(" abc 234 "))
    }
}
