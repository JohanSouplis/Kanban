package com.johansouplis.kanban.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Task
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun KanbanApp(viewModel: BoardViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshCalendars()
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCalendars() }

    when (state.phase) {
        Phase.STARTING -> Unit
        Phase.WELCOME -> WelcomeScreen(state, viewModel::create, viewModel::join, requestPermission)
        Phase.BOARD -> BoardScreen(state, viewModel, requestPermission)
    }
}

/** A task being edited is kept by id, so a change from the other phone shows up in the open editor's base. */
private data class Editing(val id: String, val newInColumn: Column)

private val EditingSaver = Saver<Editing?, String>(
    save = { it?.let { "${it.newInColumn.name}:${it.id}" } },
    restore = { Editing(it.substringAfter(':'), Column.valueOf(it.substringBefore(':'))) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardScreen(state: BoardUiState, viewModel: BoardViewModel, requestPermission: () -> Unit) {
    val columns = Column.entries
    val pager = rememberPagerState { columns.size }
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kanban") },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Partager le tableau") },
                                onClick = {
                                    menuOpen = false
                                    sharing = true
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Editing(UUID.randomUUID().toString(), columns[pager.currentPage]) }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter une tâche")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                columns.forEachIndexed { index, column ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text("${column.label} (${state.tasks.count { it.column == column }})") },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val tasks = state.tasks.filter { it.column == columns[page] }
                if (tasks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucune tâche", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(tasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                calendar = state.calendars.find { it.key == task.reminder?.calendarKey },
                                status = calendarStatus(task, state),
                                onOpen = { editing = Editing(task.id, task.column) },
                                onMove = { viewModel.move(task.id, it) },
                                onDelete = { deletingId = task.id },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        val existing = state.tasks.find { it.id == target.id }
        TaskEditor(
            initial = existing ?: Task(id = target.id, title = "", column = target.newInColumn),
            isNew = existing == null,
            calendars = state.calendars,
            lastCalendarKey = state.lastCalendarKey,
            hasCalendarPermission = state.hasCalendarPermission,
            onRequestPermission = requestPermission,
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    state.tasks.find { it.id == deletingId }?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Supprimer la tâche ?") },
            text = {
                if (task.event == null) {
                    Text("« ${task.title} » sera supprimée.")
                } else {
                    Text("« ${task.title} » et son rappel dans l'agenda seront supprimés.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(task.id)
                    deletingId = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Annuler") } },
        )
    }

    if (sharing) {
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text("Partager le tableau") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sur l'autre téléphone, installez Kanban, choisissez « Rejoindre avec un code » et saisissez :")
                    SelectionContainer {
                        Text(
                            state.joinCode,
                            style = MaterialTheme.typography.displaySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        "Les tâches sont alors partagées en direct. Gardez ce code : il permet aussi de retrouver le tableau après une réinstallation.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { sharing = false }) { Text("Fermer") } },
        )
    }
}

private fun calendarStatus(task: Task, state: BoardUiState): String = when {
    task.reminder == null -> ""
    task.id in state.pending -> "agenda à mettre à jour"
    task.column == Column.DONE -> ""
    state.hasCalendarPermission && state.calendars.none { it.key == task.reminder.calendarKey } -> "agenda absent de ce téléphone"
    task.event == null -> "hors agenda"
    else -> ""
}

@Composable
private fun TaskCard(
    task: Task,
    calendar: CalendarInfo?,
    status: String,
    onOpen: () -> Unit,
    onMove: (Column) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                if (task.description.isNotBlank()) {
                    Text(
                        task.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                task.reminder?.let { reminder ->
                    val where = calendar?.name?.let { " · $it" }.orEmpty()
                    val suffix = if (status.isEmpty()) "" else " ($status)"
                    Text(
                        "Rappel ${formatDateTime(reminder.startMillis)}$where$suffix",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Column.entries.filter { it != task.column }.forEach { column ->
                        DropdownMenuItem(
                            text = { Text("Déplacer vers « ${column.label} »") },
                            onClick = {
                                menuOpen = false
                                onMove(column)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            if (task.column == Column.TODO) {
                TextButton(onClick = { onMove(Column.DOING) }) { Text("Commencer") }
            }
            if (task.column != Column.DONE) {
                TextButton(onClick = { onMove(Column.DONE) }) { Text("✓ Valider") }
            }
        }
    }
}
