package com.johansouplis.kanban.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.johansouplis.kanban.data.BoardLink
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.data.CalendarRepository
import com.johansouplis.kanban.data.FirestoreBoard
import com.johansouplis.kanban.data.LegacyBoard
import com.johansouplis.kanban.data.LocalSettings
import com.johansouplis.kanban.data.normalizeJoinCode
import com.johansouplis.kanban.domain.Board
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.EventSpec
import com.johansouplis.kanban.domain.Task
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
    private var listener: ListenerRegistration? = null

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
        onBoard { it.save(if (task.createdAt == 0L) task.copy(createdAt = System.currentTimeMillis()) else task) }
    }

    fun move(id: String, column: Column) = onBoard { it.move(id, column) }

    fun delete(id: String) = onBoard { it.delete(id) }

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
