package com.johansouplis.kanban.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.data.Import
import com.johansouplis.kanban.data.MAX_ATTACHMENTS
import com.johansouplis.kanban.data.decodeThumbnail
import com.johansouplis.kanban.data.deleteCameraFile
import com.johansouplis.kanban.data.newCameraUri
import com.johansouplis.kanban.domain.Attachment
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.LEAD_TIMES_MINUTES
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val AttachmentsSaver = listSaver<List<Attachment>, String>(
    save = { list -> list.flatMap { listOf(it.id, it.name, it.mimeType, it.size.toString()) } },
    restore = { flat -> flat.chunked(4).map { (id, name, mimeType, size) -> Attachment(id, name, mimeType, size.toInt()) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(
    initial: Task,
    isNew: Boolean,
    scheduling: Boolean,
    calendars: List<CalendarInfo>,
    lastCalendarKey: String?,
    hasCalendarPermission: Boolean,
    onRequestPermission: () -> Unit,
    onImport: suspend (Uri) -> Import,
    loadBytes: suspend (Attachment) -> ByteArray?,
    onOpenAttachment: suspend (Attachment) -> String?,
    onSave: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var column by rememberSaveable { mutableStateOf(initial.column) }
    var attachments by rememberSaveable(stateSaver = AttachmentsSaver) { mutableStateOf(initial.attachments) }
    var importing by remember { mutableStateOf(false) }
    var hasReminder by rememberSaveable { mutableStateOf(initial.reminder != null || scheduling) }
    var dateTime by rememberSaveable {
        mutableStateOf(
            initial.reminder?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.startMillis), zone) }
                ?: LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        )
    }
    var leadMinutes by rememberSaveable { mutableStateOf(initial.reminder?.leadMinutes ?: 15) }
    var chosenKey by rememberSaveable { mutableStateOf(initial.reminder?.calendarKey) }
    val calendarKey = chosenKey
        ?: lastCalendarKey?.takeIf { key -> calendars.any { it.key == key } }
        ?: calendars.firstOrNull()?.key
    val calendar = calendars.find { it.key == calendarKey }

    fun result(): Task {
        val reminder = when {
            !hasCalendarPermission || calendarKey == null -> initial.reminder
            hasReminder -> Reminder(dateTime.atZone(zone).toInstant().toEpochMilli(), leadMinutes, calendarKey)
            else -> null
        }
        return initial.copy(
            title = title.trim(),
            description = description.trim(),
            column = column,
            reminder = reminder,
            attachments = attachments,
        )
    }

    fun attach(uri: Uri?, then: () -> Unit = {}) {
        uri ?: return
        importing = true
        scope.launch {
            when (val imported = onImport(uri)) {
                is Import.Done -> attachments = attachments + imported.attachment
                is Import.Refused -> snackbar.showSnackbar(imported.message)
            }
            importing = false
            then()
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { attach(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { attach(it) }
    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = cameraUri ?: return@rememberLauncherForActivityResult
        cameraUri = null
        if (taken) attach(uri) { deleteCameraFile(context, uri) } else deleteCameraFile(context, uri)
    }

    val titleFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Fermer") }
                    },
                    title = { Text(if (isNew) "Nouvelle tâche" else "Modifier la tâche") },
                    actions = {
                        TextButton(onClick = { onSave(result()) }, enabled = title.isNotBlank() && !importing) { Text("Enregistrer") }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            LaunchedEffect(Unit) { if (isNew) titleFocus.requestFocus() }
            Column(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optionnelle)") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle("Pièces jointes")
                val canAdd = attachments.size < MAX_ATTACHMENTS && !importing
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                    ) { Text("Photo", maxLines = 1) }
                    OutlinedButton(
                        onClick = { newCameraUri(context).also { cameraUri = it }.let(takePhoto::launch) },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                    ) { Text("Appareil photo", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    OutlinedButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = canAdd,
                        modifier = Modifier.weight(1f),
                    ) { Text("Fichier", maxLines = 1) }
                }
                when {
                    importing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    attachments.size >= MAX_ATTACHMENTS -> Hint("$MAX_ATTACHMENTS pièces jointes au maximum.")
                    attachments.isEmpty() -> Hint("Photos jusqu'à 700 Ko après réduction, autres fichiers jusqu'à 900 Ko.")
                }
                attachments.forEach { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        loadBytes = loadBytes,
                        onOpen = { scope.launch { onOpenAttachment(attachment)?.let { snackbar.showSnackbar(it) } } },
                        onRemove = { attachments = attachments - attachment },
                    )
                }

                SectionTitle("Colonne")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Column.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = column == option,
                            onClick = { column = option },
                            shape = SegmentedButtonDefaults.itemShape(index, Column.entries.size),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }

                SectionTitle("Date prévue et rappel")
                Hint("Sans date, la tâche reste dans le backlog.")
                when {
                    !hasCalendarPermission -> {
                        Text("Autorisez l'accès à l'agenda pour ajouter un rappel.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onRequestPermission) { Text("Autoriser l'accès à l'agenda") }
                    }
                    calendarKey == null -> Text(
                        "Aucun agenda modifiable sur ce téléphone. Synchronisez un agenda Google dans l'application Google Agenda.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Rappel dans l'agenda", Modifier.weight(1f))
                            Switch(checked = hasReminder, onCheckedChange = { hasReminder = it })
                        }
                        if (hasReminder) {
                            DateTimeFields(dateTime) { dateTime = it }
                            Dropdown(
                                label = "Alerte",
                                selected = leadTimeLabel(leadMinutes),
                                options = LEAD_TIMES_MINUTES,
                                optionLabel = ::leadTimeLabel,
                                onSelect = { leadMinutes = it },
                            )
                            Dropdown(
                                label = "Agenda",
                                selected = calendar?.let(::calendarLabel) ?: "Agenda absent de ce téléphone",
                                options = calendars,
                                optionLabel = ::calendarLabel,
                                onSelect = { chosenKey = it.key },
                                leading = { ColorDot(it.color) },
                                selectedLeading = calendar?.let { { ColorDot(it.color) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val THUMBNAIL_SIDE_PX = 168

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    loadBytes: suspend (Attachment) -> ByteArray?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val isImage = attachment.mimeType.startsWith("image/")
    val thumbnail by produceState<ImageBitmap?>(null, attachment.id) {
        if (isImage) {
            value = loadBytes(attachment)?.let { bytes ->
                withContext(Dispatchers.Default) { decodeThumbnail(bytes, THUMBNAIL_SIDE_PX)?.asImageBitmap() }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val image = thumbnail
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    attachment.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "?" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(attachment.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(attachment.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Retirer ${attachment.name}") }
    }
}

private fun formatSize(bytes: Int) = if (bytes < 1000) "$bytes o" else "${(bytes + 500) / 1000} Ko"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeFields(value: LocalDateTime, onChange: (LocalDateTime) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickingDate = true }, Modifier.weight(1f)) {
            Text("%02d/%02d/%d".format(value.dayOfMonth, value.monthValue, value.year))
        }
        OutlinedButton(onClick = { pickingTime = true }, Modifier.weight(1f)) {
            Text("%02d:%02d".format(value.hour, value.minute))
        }
    }
    if (pickingDate) {
        val state = rememberDatePickerState(value.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(LocalDateTime.of(date, value.toLocalTime()))
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Annuler") } },
        ) { DatePicker(state) }
    }
    if (pickingTime) {
        val state = rememberTimePickerState(value.hour, value.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalDateTime.of(value.toLocalDate(), LocalTime.of(state.hour, state.minute)))
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(
    label: String,
    selected: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    leading: (@Composable (T) -> Unit)? = null,
    selectedLeading: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = selectedLeading,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    leadingIcon = leading?.let { { it(option) } },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun calendarLabel(calendar: CalendarInfo) = "${calendar.name} (${calendar.account})"

@Composable
private fun ColorDot(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape))
}
