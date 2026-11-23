package com.johansouplis.kanban.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.johansouplis.kanban.domain.Attachment
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.EventPlacement
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import com.johansouplis.kanban.domain.TaskRepository
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

private const val NETWORK_TIMEOUT_MILLIS = 15_000L

/** No 0/O, 1/I/L: the code is read aloud or copied by hand. */
private const val JOIN_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

fun newJoinCode(random: Random = Random.Default) = String(CharArray(6) { JOIN_CODE_ALPHABET[random.nextInt(JOIN_CODE_ALPHABET.length)] })

fun normalizeJoinCode(input: String) = input.uppercase().filter { it in JOIN_CODE_ALPHABET }

/**
 * Layout, mirrored by `firestore.rules`:
 * `boards/{boardId}` {joinCode, members, createdAt}, `joinCodes/{code}` {boardId}, `boards/{boardId}/tasks/{taskId}`,
 * `boards/{boardId}/files/{attachmentId}` {taskId, data}. Files sit apart so that listening to the tasks never downloads them.
 * Firestore keeps its own offline cache: writes apply locally at once and reach the server when the network allows.
 */
class FirestoreBoard {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val uid: String? get() = auth.currentUser?.uid

    suspend fun signIn(): String = uid ?: network { auth.signInAnonymously().await() }.user!!.uid

    suspend fun create(uid: String): BoardLink {
        val board = db.collection("boards").document()
        val code = newJoinCode()
        network {
            db.batch()
                .set(board, mapOf("joinCode" to code, "members" to listOf(uid), "createdAt" to FieldValue.serverTimestamp()))
                .set(db.collection("joinCodes").document(code), mapOf("boardId" to board.id))
                .commit()
                .await()
        }
        return BoardLink(board.id, code)
    }

    /** Null when no board has this code. */
    suspend fun join(code: String, uid: String): BoardLink? {
        val boardId = network { db.collection("joinCodes").document(code).get().await() }.getString("boardId") ?: return null
        network { db.collection("boards").document(boardId).update("members", FieldValue.arrayUnion(uid)).await() }
        return BoardLink(boardId, code)
    }

    fun listen(boardId: String, onTasks: (List<Task>) -> Unit): ListenerRegistration =
        tasks(boardId).addSnapshotListener { snapshot, _ ->
            snapshot?.let { onTasks(it.documents.mapNotNull { doc -> doc.data?.let { data -> taskFromFields(doc.id, data) } }) }
        }

    fun repository(boardId: String, uid: String) = object : TaskRepository {
        override fun put(task: Task) {
            tasks(boardId).document(task.id)
                .set(taskFields(task) + mapOf("updatedAt" to FieldValue.serverTimestamp(), "updatedBy" to uid))
        }

        override fun delete(id: String) {
            tasks(boardId).document(id).delete()
        }
    }

    fun putFile(boardId: String, taskId: String, attachmentId: String, bytes: ByteArray) {
        files(boardId).document(attachmentId).set(mapOf("taskId" to taskId, "data" to Blob.fromBytes(bytes)))
    }

    fun deleteFile(boardId: String, attachmentId: String) {
        files(boardId).document(attachmentId).delete()
    }

    /** Null when the file is neither cached on this phone nor reachable. */
    suspend fun readFile(boardId: String, attachmentId: String): ByteArray? = runCatching {
        network { files(boardId).document(attachmentId).get().await() }.getBlob("data")?.toBytes()
    }.getOrNull()

    private fun tasks(boardId: String) = db.collection("boards").document(boardId).collection("tasks")

    private fun files(boardId: String) = db.collection("boards").document(boardId).collection("files")

    private suspend fun <T> network(block: suspend () -> T): T = withTimeout(NETWORK_TIMEOUT_MILLIS) { block() }
}

fun taskFields(task: Task): Map<String, Any?> = mapOf(
    "title" to task.title,
    "description" to task.description,
    "column" to task.column.name,
    "reminder" to task.reminder?.let {
        mapOf("startMillis" to it.startMillis, "leadMinutes" to it.leadMinutes, "calendarKey" to it.calendarKey)
    },
    "event" to task.event?.let { mapOf("calendarKey" to it.calendarKey, "placedBy" to it.placedBy) },
    "createdAt" to task.createdAt,
    "rank" to task.rank,
    "attachments" to task.attachments.map { mapOf("id" to it.id, "name" to it.name, "mimeType" to it.mimeType, "size" to it.size) },
)

/** Null for a document this version cannot read, so one bad task does not hide the board. */
fun taskFromFields(id: String, fields: Map<String, Any?>): Task? {
    val title = fields["title"] as? String ?: return null
    val column = Column.entries.find { it.name == fields["column"] } ?: return null
    val reminder = (fields["reminder"] as? Map<*, *>)?.let {
        Reminder(
            startMillis = (it["startMillis"] as? Number)?.toLong() ?: return null,
            leadMinutes = (it["leadMinutes"] as? Number)?.toInt() ?: return null,
            calendarKey = it["calendarKey"] as? String ?: return null,
        )
    }
    val event = (fields["event"] as? Map<*, *>)?.let {
        EventPlacement(it["calendarKey"] as? String ?: return null, it["placedBy"] as? String ?: return null)
    }
    return Task(
        id = id,
        title = title,
        description = fields["description"] as? String ?: "",
        column = column,
        reminder = reminder,
        event = event,
        createdAt = (fields["createdAt"] as? Number)?.toLong() ?: 0,
        rank = (fields["rank"] as? Number)?.toDouble() ?: 0.0,
        attachments = (fields["attachments"] as? List<*>).orEmpty().mapNotNull(::attachmentFromFields),
    )
}

/** Null for an entry this version cannot read: the task stays, without it. */
private fun attachmentFromFields(fields: Any?): Attachment? {
    val map = fields as? Map<*, *> ?: return null
    return Attachment(
        id = map["id"] as? String ?: return null,
        name = map["name"] as? String ?: return null,
        mimeType = map["mimeType"] as? String ?: return null,
        size = (map["size"] as? Number)?.toInt() ?: return null,
    )
}
