package com.johansouplis.kanban.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.data.CalendarRepository
import com.johansouplis.kanban.data.Snapshot
import com.johansouplis.kanban.data.TaskStore
import com.johansouplis.kanban.domain.Board
import com.johansouplis.kanban.domain.Column
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

data class BoardUiState(
    val tasks: List<Task> = emptyList(),
    val calendars: List<CalendarInfo> = emptyList(),
    val lastCalendarId: Long? = null,
    val hasCalendarPermission: Boolean = false,
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {
    private val calendar = CalendarRepository(app)
    private val store = TaskStore(File(app.filesDir, "board.json"))
    private val board = Board(calendar)
    private val lock = Mutex()

    private val _state = MutableStateFlow(BoardUiState())
    val state = _state.asStateFlow()

    init {
        serially {
            val snapshot = store.load()
            _state.update { it.copy(tasks = snapshot.tasks, lastCalendarId = snapshot.lastCalendarId) }
        }
    }

    fun refreshCalendars() {
        serially { loadCalendars() }
        mutate { board.reconcile(it) }
    }

    fun save(task: Task) = mutate(task.reminder?.calendarId) { board.save(it, task) }

    fun move(id: String, column: Column) = mutate { board.move(it, id, column) }

    fun delete(id: String) = mutate { board.delete(it, id) }

    private fun loadCalendars() {
        val granted = calendar.hasPermission()
        _state.update {
            it.copy(hasCalendarPermission = granted, calendars = if (granted) calendar.writableCalendars() else emptyList())
        }
    }

    private fun mutate(usedCalendarId: Long? = null, change: (List<Task>) -> List<Task>) = serially {
        val current = _state.value
        val next = current.copy(tasks = change(current.tasks), lastCalendarId = usedCalendarId ?: current.lastCalendarId)
        if (next == current) return@serially
        _state.value = next
        store.save(Snapshot(next.tasks, next.lastCalendarId))
    }

    private fun serially(block: () -> Unit) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            lock.withLock { withContext(Dispatchers.IO) { block() } }
        }
    }
}
