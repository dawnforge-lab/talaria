package lab.dawnforge.hermes.feature.cron

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import lab.dawnforge.hermes.core.data.cron.IntervalUnit
import lab.dawnforge.hermes.core.data.cron.ScheduleChoice
import lab.dawnforge.hermes.core.data.cron.ScheduleWords
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronEditScreen(viewModel: CronEditViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(viewModel) { viewModel.saved.collect { done() } }
    var confirmDiscard by remember { mutableStateOf(false) }
    // Unsaved edits aren't dropped by a stray back.
    val close = { if (state.dirty) confirmDiscard = true else onDone() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.editing) R.string.feature_cron_edit_title else R.string.feature_cron_new_job,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.feature_cron_close))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = viewModel::save, enabled = state.canSave) {
                            Text(stringResource(R.string.feature_cron_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                label = { Text(stringResource(R.string.feature_cron_name)) },
                placeholder = { Text(stringResource(R.string.feature_cron_name_placeholder)) },
                supportingText = { Text(stringResource(R.string.feature_cron_name_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = { Text(stringResource(R.string.feature_cron_prompt)) },
                supportingText = { Text(stringResource(R.string.feature_cron_prompt_help)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 3,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            ScheduleSection(state, viewModel)
            DeliverySection(state, viewModel::onDeliverChange)
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.feature_cron_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text(stringResource(R.string.feature_cron_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.feature_cron_keep_editing))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleSection(state: CronEditUiState, viewModel: CronEditViewModel) {
    Text(stringResource(R.string.feature_cron_repeats), style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val modes = ScheduleMode.entries
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = state.mode == mode,
                onClick = { viewModel.onModeChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                label = { Text(stringResource(modeLabel(mode)), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
    when (state.mode) {
        ScheduleMode.AtTime -> AtTimeControls(state.atTime, viewModel::onAtTimeChange)

        ScheduleMode.Every -> EveryControls(state.every, viewModel::onEveryChange)

        ScheduleMode.Once -> OnceControls(state.once, viewModel::onOnceChange)

        ScheduleMode.Custom -> OutlinedTextField(
            value = state.custom.text,
            onValueChange = viewModel::onCustomChange,
            label = { Text(stringResource(R.string.feature_cron_schedule)) },
            placeholder = { Text(stringResource(R.string.feature_cron_custom_placeholder)) },
            supportingText = { Text(stringResource(R.string.feature_cron_custom_help)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // The schedule in words, or why it isn't one yet.
    val words = ScheduleWords.of(state.choice)
    if (words == null) {
        Text(
            stringResource(
                if (state.mode ==
                    ScheduleMode.AtTime
                ) {
                    R.string.feature_cron_pick_a_day
                } else {
                    R.string.feature_cron_not_a_schedule
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Text(scheduleText(words, state.zoneLabel), style = MaterialTheme.typography.bodyMedium)
    }
    if (state.mode == ScheduleMode.AtTime && state.zoneLabel != null) {
        Text(
            stringResource(R.string.feature_cron_zone_note, state.zoneLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AtTimeControls(choice: ScheduleChoice.AtTime, onChange: (ScheduleChoice.AtTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }) { Text(clockTime(choice.hour, choice.minute)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val locale = currentLocale()
        for (day in weekInOrder(locale)) {
            val on = day in choice.days
            FilterChip(
                selected = on,
                onClick = { onChange(choice.copy(days = if (on) choice.days - day else choice.days + day)) },
                label = { Text(dayName(day, locale)) },
            )
        }
    }
    if (picking) {
        TimeDialog(choice.hour, choice.minute, onDismiss = { picking = false }) { hour, minute ->
            picking = false
            onChange(choice.copy(hour = hour, minute = minute))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EveryControls(choice: ScheduleChoice.Every, onChange: (ScheduleChoice.Every) -> Unit) {
    // The typed text, so a field emptied to type a new number doesn't snap back.
    var text by remember(choice.unit) { mutableStateOf(choice.count.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { typed ->
                val digits = typed.filter(Char::isDigit).take(MAX_DIGITS)
                text = digits
                onChange(choice.copy(count = digits.toIntOrNull() ?: 0))
            },
            label = { Text(stringResource(R.string.feature_cron_every_count)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (unit in IntervalUnit.entries) {
                FilterChip(
                    selected = choice.unit == unit,
                    onClick = { onChange(choice.copy(unit = unit)) },
                    label = { Text(stringResource(unitLabel(unit))) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnceControls(choice: ScheduleChoice.Once, onChange: (ScheduleChoice.Once) -> Unit) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    // Picked in the phone's zone, kept with its offset.
    val local = choice.at.atZoneSameInstant(ZoneId.systemDefault())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickingDate = true }) {
            Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local))
        }
        OutlinedButton(onClick = { pickingTime = true }) { Text(clockTime(local.hour, local.minute)) }
    }
    if (pickingDate) {
        val today = LocalDate.now()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingDate = false
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(ScheduleChoice.Once(local.with(date).toOffsetDateTime()))
                    }
                }) { Text(stringResource(R.string.feature_cron_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.feature_cron_cancel)) }
            },
        ) { DatePicker(picker) }
    }
    if (pickingTime) {
        TimeDialog(local.hour, local.minute, onDismiss = { pickingTime = false }) { hour, minute ->
            pickingTime = false
            val at: OffsetDateTime = local.withHour(hour).withMinute(minute).toOffsetDateTime()
            onChange(ScheduleChoice.Once(at))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val picker = rememberTimePickerState(hour, minute, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onPick(picker.hour, picker.minute)
            }) { Text(stringResource(R.string.feature_cron_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_cron_cancel)) } },
        text = { TimePicker(picker) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliverySection(state: CronEditUiState, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = deliveryTitle(state.deliver, state.targets),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.feature_cron_deliver_to)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (target in state.targets) {
                DropdownMenuItem(
                    text = { Text(deliveryTitle(target.id, state.targets)) },
                    onClick = {
                        open = false
                        onChange(target.id)
                    },
                )
            }
        }
    }
    val chosen = state.targets.firstOrNull { it.id == state.deliver }
    Text(
        stringResource(
            if (chosen?.hasHome == false) R.string.feature_cron_no_home else R.string.feature_cron_delivery_note,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun modeLabel(mode: ScheduleMode): Int = when (mode) {
    ScheduleMode.AtTime -> R.string.feature_cron_mode_at
    ScheduleMode.Every -> R.string.feature_cron_mode_every
    ScheduleMode.Once -> R.string.feature_cron_mode_once
    ScheduleMode.Custom -> R.string.feature_cron_mode_custom
}

private fun unitLabel(unit: IntervalUnit): Int = when (unit) {
    IntervalUnit.Minutes -> R.string.feature_cron_unit_minutes
    IntervalUnit.Hours -> R.string.feature_cron_unit_hours
    IntervalUnit.Days -> R.string.feature_cron_unit_days
}

private const val MAX_DIGITS = 3
