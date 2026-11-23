package com.johansouplis.kanban.ui

import android.app.Application
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.johansouplis.kanban.data.BoardLink
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.data.CalendarRepository
import com.johansouplis.kanban.data.FirestoreBoard
import com.johansouplis.kanban.data.Import
import com.johansouplis.kanban.data.LegacyBoard
import com.johansouplis.kanban.data.LocalSettings
import com.johansouplis.kanban.data.importAttachment
import com.johansouplis.kanban.data.normalizeJoinCode
import com.johansouplis.kanban.domain.Attachment
import com.johansouplis.kanban.domain.Board
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.EventSpec
import com.johansouplis.kanban.domain.Task
import com.johansouplis.kanban.domain.enterBacklog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** STARTING until the saved board is known, WELCOME to create or join one, BOARD once linked. */
enum class Phase { STARTING, WELCOME, BOARD }

data class BoardUiState(
    val phase: Phase = Phase.STARTING,
    val joinCode: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val legacyHasReminders: Boolean = false,
    val tasks: List<Task> = emptyList(),
    val pending: Set<String> = emptySet(),
    val calendars: List<CalendarInfo> = emptyList(),
    val lastCalendarKey: String? = null,
    val hasCalendarPermission: Boolean = false,
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val calendar = CalendarRepository(app)
    private val remote = FirestoreBoard()
    private val settings = LocalSettings(app)
    private val legacy = LegacyBoard(File(app.filesDir, "board.json"))
    private val lock = Mutex()
    private var board: Board? = null
    private var boardId: String? = null
    private var listener: ListenerRegistration? = null

    /** Bytes of attachments added in the open editor, written only when the task is saved. */
    private val drafts = ConcurrentHashMap<String, ByteArray>()
    private val files = object : LruCache<String, ByteArray>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    private val _state = MutableStateFlow(BoardUiState(lastCalendarKey = settings.lastCalendarKey))
    val state = _state.asStateFlow()

    init {
        val link = settings.board
        val uid = remote.uid
        if (link != null && uid != null) {
            open(link, uid)
        } else {
            serially {
                val hasReminders = legacy.load().any { it.reminder != null }
                _state.update { it.copy(phase = Phase.WELCOME, legacyHasReminders = hasReminders) }
            }
        }
    }

    fun create() = link { uid -> remote.create(uid) }

    fun join(code: String) = link { uid -> remote.join(normalizeJoinCode(code), uid) }

    fun refreshCalendars() {
        serially { loadCalendars() }
        onBoard { it.reconcile() }
    }

    fun save(task: Task) {
        task.reminder?.let { settings.lastCalendarKey = it.calendarKey }
        _state.update { it.copy(lastCalendarKey = settings.lastCalendarKey) }
        val added = task.attachments.mapNotNull { a -> drafts[a.id]?.let { a.id to it } }
        discardDrafts()
        onBoard { board ->
            val boardId = boardId ?: return@onBoard
            added.forEach { (id, bytes) ->
                files.put(id, bytes)
                remote.putFile(boardId, task.id, id, bytes)
            }
            val before = board.tasks.find { it.id == task.id }?.attachments.orEmpty()
            (before - task.attachments.toSet()).forEach { remote.deleteFile(boardId, it.id) }
            val created = if (task.createdAt == 0L) task.copy(createdAt = System.currentTimeMillis()) else task
            board.save(enterBacklog(created, board.tasks))
        }
    }

    fun move(id: String, column: Column) = onBoard { board ->
        board.tasks.find { it.id == id }?.let { board.save(enterBacklog(it.copy(column = column), board.tasks)) }
    }

    /** Undoes a move: back to [previous]'s column and backlog place. */
    fun restore(previous: Task) = onBoard { board ->
        board.tasks.find { it.id == previous.id }?.let { board.save(it.copy(column = previous.column, rank = previous.rank)) }
    }

    fun reorder(ranks: Map<String, Double>) = onBoard { board ->
        ranks.forEach { (id, rank) -> board.tasks.find { it.id == id }?.let { board.save(it.copy(rank = rank)) } }
    }

    fun delete(id: String) = onBoard { board ->
        val boardId = boardId ?: return@onBoard
        board.tasks.find { it.id == id }?.attachments?.forEach { remote.deleteFile(boardId, it.id) }
        board.delete(id)
    }

    suspend fun importAttachment(uri: Uri): Import = withContext(Dispatchers.IO) {
        importAttachment(getApplication(), uri).also { if (it is Import.Done) drafts[it.attachment.id] = it.bytes }
    }

    suspend fun attachmentBytes(attachment: Attachment): ByteArray? {
        drafts[attachment.id]?.let { return it }
        files.get(attachment.id)?.let { return it }
        val boardId = boardId ?: return null
        return remote.readFile(boardId, attachment.id)?.also { files.put(attachment.id, it) }
    }

    fun discardDrafts() = drafts.clear()

    override fun onCleared() {
        listener?.remove()
    }

    private fun link(connect: suspend (uid: String) -> BoardLink?) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val uid = remote.signIn()
                connect(uid)?.let { it to uid }
            }
            val linked = result.getOrNull()
            if (linked == null) {
                val error = if (result.isSuccess) "Aucun tableau avec ce code." else "Connexion impossible. Vérifiez le réseau et réessayez."
                _state.update { it.copy(busy = false, error = error) }
                return@launch
            }
            settings.board = linked.first
            open(linked.first, linked.second)
            serially { migrateLegacyTasks() }
        }
    }

    private fun open(link: BoardLink, uid: String) {
        val board = Board(calendar, remote.repository(link.boardId, uid), uid, settings.pending)
        this.board = board
        boardId = link.boardId
        _state.update { it.copy(phase = Phase.BOARD, joinCode = link.joinCode, busy = false, error = null) }
        listener = remote.listen(link.boardId) { tasks -> onBoard { it.receive(tasks) } }
    }

    /** Moves version 1.0's local tasks into the shared board. Their events get the task marker so the board finds them. */
    private fun migrateLegacyTasks() {
        val board = board ?: return
        val tasks = legacy.load()
        if (tasks.isEmpty()) return
        val keys = calendar.writableCalendars().associate { it.id to it.key }
        val now = System.currentTimeMillis()
        tasks.forEachIndexed { index, old ->
            val task = old.migrate(keys::get, createdAt = now + index)
            val reminder = task.reminder
            if (old.eventId != null && reminder != null) {
                calendar.update(old.eventId, task.id, EventSpec(task.title, task.description, reminder.startMillis, reminder.leadMinutes))
            }
            board.save(task)
        }
        legacy.discard()
        publish(board)
    }

    private fun loadCalendars() {
        val granted = calendar.hasPermission()
        _state.update {
            it.copy(hasCalendarPermission = granted, calendars = if (granted) calendar.writableCalendars() else emptyList())
        }
    }

    private fun onBoard(change: (Board) -> Unit) = serially {
        board?.let {
            change(it)
            publish(it)
        }
    }

    private fun publish(board: Board) {
        if (board.pending != settings.pending) settings.pending = board.pending
        _state.update { it.copy(tasks = board.tasks.sortedBy(Task::createdAt), pending = board.pending) }
    }

    private fun serially(block: () -> Unit) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock { withContext(Dispatchers.IO) { block() } }
        }
    }
}
