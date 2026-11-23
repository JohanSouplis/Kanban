package com.johansouplis.kanban.data

import com.johansouplis.kanban.domain.Attachment
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
        rank = 2.5,
        attachments = listOf(
            Attachment("f1", "Facture.pdf", "application/pdf", 120_000),
            Attachment("f2", "Pneu.jpg", "image/jpeg", 640_000),
        ),
    )

    /** Firestore hands every integer back as a Long. */
    private fun Map<String, Any?>.asStored(): Map<String, Any?> = mapValues { (_, value) ->
        when (value) {
            is Int -> value.toLong()
            is Map<*, *> -> value.mapKeys { it.key as String }.asStored()
            is List<*> -> value.map { item -> if (item is Map<*, *>) item.mapKeys { it.key as String }.asStored() else item }
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
    fun `a whole-number rank read back as a Long is still a rank`() {
        val stored = taskFields(task).asStored() + ("rank" to 3L)

        assertEquals(3.0, taskFromFields("t1", stored)!!.rank, 0.0)
    }

    @Test
    fun `a task written before ranks and attachments existed is read with none`() {
        val stored = taskFields(task).asStored() - "rank" - "attachments"

        assertEquals(task.copy(rank = 0.0, attachments = emptyList()), taskFromFields("t1", stored))
    }

    @Test
    fun `an attachment this version cannot read is dropped and the task kept`() {
        val stored = taskFields(task).asStored() + ("attachments" to listOf(
            mapOf("id" to "f1", "name" to "Facture.pdf", "mimeType" to "application/pdf", "size" to 120_000L),
            mapOf("id" to "f3", "name" to "Sans taille.txt", "mimeType" to "text/plain"),
            "pas une pièce jointe",
        ))

        assertEquals(listOf(Attachment("f1", "Facture.pdf", "application/pdf", 120_000)), taskFromFields("t1", stored)!!.attachments)
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
