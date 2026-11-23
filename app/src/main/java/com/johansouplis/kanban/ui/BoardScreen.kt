package com.johansouplis.kanban.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.data.openAttachment
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.Lane
import com.johansouplis.kanban.domain.Task
import com.johansouplis.kanban.domain.inLane
import com.johansouplis.kanban.domain.lane
import com.johansouplis.kanban.domain.reorderBacklog
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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

/**
 * A task being edited is kept by id, so a change from the other phone shows up in the open editor's base.
 * [scheduling] turns the reminder on when the editor opens.
 */
private data class Editing(val id: String, val newInColumn: Column, val scheduling: Boolean = false)

private val EditingSaver = Saver<Editing?, String>(
    save = { it?.let { "${it.newInColumn.name}:${it.scheduling}:${it.id}" } },
    restore = {
        val (column, scheduling, id) = it.split(':', limit = 3)
        Editing(id, Column.valueOf(column), scheduling.toBoolean())
    },
)

private val emptyLaneHints = mapOf(
    Lane.TODO to "Les tâches avec une date prévue apparaissent ici.",
    Lane.BACKLOG to "Les tâches sans date arrivent ici. Appui long pour les réordonner.",
    Lane.DOING to "« Commencer » sur une carte l'amène ici.",
    Lane.DONE to "Les tâches validées arrivent ici.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardScreen(state: BoardUiState, viewModel: BoardViewModel, requestPermission: () -> Unit) {
    val lanes = Lane.entries
    val pager = rememberPagerState { lanes.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val byLane = remember(state.tasks) { lanes.associateWith { state.tasks.inLane(it) } }

    fun move(task: Task, column: Column) {
        viewModel.move(task.id, column)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val arrival = task.copy(column = column).lane.label
            val result = snackbar.showSnackbar("« ${task.title} » déplacée vers $arrival", actionLabel = "Annuler", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) viewModel.restore(task)
        }
    }

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
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val lane = lanes[pager.currentPage]
                val column = if (lane == Lane.DOING) Column.DOING else Column.TODO
                editing = Editing(UUID.randomUUID().toString(), column, scheduling = lane == Lane.TODO)
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter une tâche")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
                lanes.forEachIndexed { index, lane ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text("${lane.label} (${byLane.getValue(lane).size})", maxLines = 1) },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val lane = lanes[page]
                val tasks = byLane.getValue(lane)
                if (tasks.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            emptyLaneHints.getValue(lane),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LaneList(tasks, reorderable = lane == Lane.BACKLOG, onReorder = viewModel::reorder) { task, dragHandle, dragging ->
                        TaskCard(
                            task = task,
                            calendar = state.calendars.find { it.key == task.reminder?.calendarKey },
                            status = calendarStatus(task, state),
                            dragging = dragging,
                            modifier = dragHandle,
                            onOpen = { editing = Editing(task.id, task.column) },
                            onSchedule = { editing = Editing(task.id, task.column, scheduling = true) },
                            onMove = { move(task, it) },
                            onRemoveDate = { viewModel.save(task.copy(reminder = null)) },
                            onDelete = { deletingId = task.id },
                        )
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
            scheduling = target.scheduling,
            calendars = state.calendars,
            lastCalendarKey = state.lastCalendarKey,
            hasCalendarPermission = state.hasCalendarPermission,
            onRequestPermission = requestPermission,
            onImport = viewModel::importAttachment,
            loadBytes = viewModel::attachmentBytes,
            onOpenAttachment = { attachment ->
                val bytes = viewModel.attachmentBytes(attachment)
                if (bytes == null) "Pièce jointe indisponible : vérifiez le réseau." else openAttachment(context, attachment, bytes)
            },
            onSave = {
                viewModel.save(it)
                editing = null
            },
            onDismiss = {
                viewModel.discardDrafts()
                editing = null
            },
        )
    }

    state.tasks.find { it.id == deletingId }?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("Supprimer la tâche ?") },
            text = {
                val extras = listOfNotNull(
                    "son rappel dans l'agenda".takeIf { task.event != null },
                    "ses pièces jointes".takeIf { task.attachments.isNotEmpty() },
                )
                if (extras.isEmpty()) {
                    Text("« ${task.title} » sera supprimée.")
                } else {
                    Text("« ${task.title} » et ${extras.joinToString(" et ")} seront supprimés.")
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
private fun LaneList(
    tasks: List<Task>,
    reorderable: Boolean,
    onReorder: (Map<String, Double>) -> Unit,
    card: @Composable (task: Task, dragHandle: Modifier, dragging: Boolean) -> Unit,
) {
    // The drag handle keeps the callbacks of its first composition, so they read the board through states.
    val board by rememberUpdatedState(tasks)
    var shown by remember { mutableStateOf(tasks) }
    LaunchedEffect(tasks) { shown = tasks }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        shown = shown.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(shown, key = { it.id }) { task ->
            ReorderableItem(reorderState, key = task.id, enabled = reorderable) { dragging ->
                val handle = if (!reorderable) {
                    Modifier
                } else {
                    Modifier.longPressDraggableHandle(
                        onDragStarted = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDragStopped = {
                            val from = board.indexOfFirst { it.id == task.id }
                            val to = shown.indexOfFirst { it.id == task.id }
                            if (from >= 0 && to >= 0) onReorder(reorderBacklog(board, from, to))
                        },
                    )
                }
                card(task, handle, dragging)
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    calendar: CalendarInfo?,
    status: String,
    dragging: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onSchedule: () -> Unit,
    onMove: (Column) -> Unit,
    onRemoveDate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val lane = task.lane
    val done = lane == Lane.DONE
    val elevation by animateDpAsState(if (dragging) 8.dp else 1.dp, label = "elevation")
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                if (task.description.isNotBlank()) {
                    Text(
                        task.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    task.reminder?.let { reminder ->
                        val where = calendar?.name?.let { " · $it" }.orEmpty()
                        val suffix = if (status.isEmpty()) "" else " ($status)"
                        "${formatRelative(reminder.startMillis)}$where$suffix"
                    },
                    task.attachments.takeIf { it.isNotEmpty() }?.let { "📎 ${it.size}" },
                )
                if (meta.isNotEmpty()) {
                    val overdue = !done && (task.reminder?.startMillis ?: Long.MAX_VALUE) < System.currentTimeMillis()
                    Text(
                        meta.joinToString("   "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                            text = { Text("Déplacer vers « ${task.copy(column = column).lane.label} »") },
                            onClick = {
                                menuOpen = false
                                onMove(column)
                            },
                        )
                    }
                    if (lane == Lane.TODO) {
                        DropdownMenuItem(
                            text = { Text("Retirer la date") },
                            onClick = {
                                menuOpen = false
                                onRemoveDate()
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
            if (lane == Lane.BACKLOG) {
                TextButton(onClick = onSchedule) { Text("Planifier") }
            }
            if (lane == Lane.BACKLOG || lane == Lane.TODO) {
                TextButton(onClick = { onMove(Column.DOING) }) { Text("Commencer") }
            }
            if (!done) {
                TextButton(onClick = { onMove(Column.DONE) }) { Text("✓ Valider") }
            }
        }
    }
}
