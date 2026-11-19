package com.johansouplis.kanban.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.johansouplis.kanban.data.CalendarInfo
import com.johansouplis.kanban.domain.Column
import com.johansouplis.kanban.domain.LEAD_TIMES_MINUTES
import com.johansouplis.kanban.domain.Reminder
import com.johansouplis.kanban.domain.Task
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(
    initial: Task,
    isNew: Boolean,
    calendars: List<CalendarInfo>,
    lastCalendarKey: String?,
    hasCalendarPermission: Boolean,
    onRequestPermission: () -> Unit,
    onSave: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var column by rememberSaveable { mutableStateOf(initial.column) }
    var hasReminder by rememberSaveable { mutableStateOf(initial.reminder != null) }
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
        return initial.copy(title = title.trim(), description = description.trim(), column = column, reminder = reminder)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nouvelle tâche" else "Modifier la tâche") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optionnelle)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Column.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = column == option,
                            onClick = { column = option },
                            shape = SegmentedButtonDefaults.itemShape(index, Column.entries.size),
                        ) { Text(option.label, maxLines = 1) }
                    }
                }
                HorizontalDivider()
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
        },
        confirmButton = {
            TextButton(onClick = { onSave(result()) }, enabled = title.isNotBlank()) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

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
