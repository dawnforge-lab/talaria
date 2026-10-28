package lab.dawnforge.hermes.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarAccess
import lab.dawnforge.hermes.core.data.phonedata.phone.PeopleBook
import lab.dawnforge.hermes.core.data.phonedata.phone.PhonePerson
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSharing
import lab.dawnforge.hermes.core.ui.failureText

/**
 * Settings › Calendar & phone, as iOS's: turned on for the active dashboard's profile, then each
 * part its own choice: the calendar (read and add, add only, or off), its details and calendars,
 * whether Hermes' new events need asking, and Do Not Disturb and phone status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneDataScreen(viewModel: PhoneDataViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Contacts access was refused for the classic picker (Android 16 and older).
    var contactsDenied by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.recheck()
        if (!PickPeople.needsPermission(context)) contactsDenied = false
    }
    val pickPeople = rememberLauncherForActivityResult(PickPeople()) { uri -> uri?.let(viewModel::addPeople) }
    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        contactsDenied = !granted
        if (granted) pickPeople.launch(PeopleBook.MAX_PEOPLE - ui.people.size)
    }
    fun addPeople() {
        if (PickPeople.needsPermission(context)) {
            askContacts.launch(PickPeople.PERMISSION)
        } else {
            pickPeople.launch(PeopleBook.MAX_PEOPLE - ui.people.size)
        }
    }
    var asking by remember { mutableStateOf(false) }
    var turningOff by remember { mutableStateOf(false) }
    var wanted by remember { mutableStateOf<CalendarAccess?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.recheck()
        wanted?.let(viewModel::setCalendar)
        wanted = null
    }
    fun chooseCalendar(access: CalendarAccess) {
        val needed = when (access) {
            CalendarAccess.Full -> arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            CalendarAccess.WriteOnly -> arrayOf(Manifest.permission.WRITE_CALENDAR)
            CalendarAccess.Off -> emptyArray()
        }
        val missing = (access == CalendarAccess.Full && !ui.canRead) || (access != CalendarAccess.Off && !ui.canWrite)
        if (needed.isNotEmpty() && missing) {
            wanted = access
            permissions.launch(needed)
        } else {
            viewModel.setCalendar(access)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_phone)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Paragraph(stringResource(R.string.feature_settings_phone_intro)) }
            val destination = ui.sharing.destination
            if (destination == null) {
                item {
                    Button(
                        onClick = { asking = true },
                        enabled = ui.target != null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(stringResource(R.string.feature_settings_phone_turn_on)) }
                }
                return@LazyColumn
            }
            item {
                Paragraph(
                    stringResource(
                        R.string.feature_settings_phone_sharing_with,
                        destination.profile,
                        ui.destinationHost ?: "?",
                    ),
                    muted = false,
                )
            }
            item { SyncLine(ui, viewModel) }
            calendarSection(ui, viewModel, ::chooseCalendar)
            item { Header(stringResource(R.string.feature_settings_phone_status_title)) }
            item {
                SwitchRow(stringResource(R.string.feature_settings_phone_focus), ui.sharing.focus, viewModel::setFocus)
            }
            item {
                SwitchRow(
                    stringResource(R.string.feature_settings_phone_status),
                    ui.sharing.status,
                    viewModel::setStatus,
                )
            }
            item { Paragraph(stringResource(R.string.feature_settings_phone_status_note)) }
            alarmsSection(ui, viewModel, onAllowAlarms = { openAlarmAccess(context) })
            peopleSection(ui, viewModel, contactsDenied, onAdd = ::addPeople, onOpenSettings = {
                openAppSettings(context)
            })
            item {
                OutlinedButton(onClick = { turningOff = true }, modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.feature_settings_phone_turn_off))
                }
            }
        }
    }

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.feature_settings_phone_consent_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.feature_settings_phone_consent,
                        ui.target?.profile.orEmpty(),
                        ui.target?.host.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    viewModel.turnOn()
                }) { Text(stringResource(R.string.feature_settings_phone_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) {
                    Text(stringResource(R.string.feature_settings_phone_not_now))
                }
            },
        )
    }
    if (turningOff) {
        AlertDialog(
            onDismissRequest = { turningOff = false },
            title = { Text(stringResource(R.string.feature_settings_phone_turn_off)) },
            text = { Text(stringResource(R.string.feature_settings_phone_turn_off_note)) },
            confirmButton = {
                TextButton(onClick = {
                    turningOff = false
                    viewModel.turnOff(delete = true)
                }) { Text(stringResource(R.string.feature_settings_phone_turn_off_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    turningOff = false
                    viewModel.turnOff(delete = false)
                }) { Text(stringResource(R.string.feature_settings_phone_turn_off_keep)) }
            },
        )
    }
}

private fun LazyListScope.calendarSection(
    ui: PhoneDataUi,
    viewModel: PhoneDataViewModel,
    choose: (CalendarAccess) -> Unit,
) {
    item { Header(stringResource(R.string.feature_settings_phone_calendar)) }
    item {
        Column {
            listOf(
                CalendarAccess.Full to R.string.feature_settings_phone_calendar_full,
                CalendarAccess.WriteOnly to R.string.feature_settings_phone_calendar_add,
                CalendarAccess.Off to R.string.feature_settings_phone_calendar_off,
            ).forEach { (access, label) ->
                Choice(stringResource(label), ui.sharing.calendar == access) { choose(access) }
            }
        }
    }
    when (ui.sharing.calendar) {
        CalendarAccess.Full -> item { Paragraph(stringResource(R.string.feature_settings_phone_calendar_full_note)) }

        CalendarAccess.WriteOnly -> item {
            Paragraph(stringResource(R.string.feature_settings_phone_calendar_add_note))
        }

        CalendarAccess.Off -> Unit
    }
    if (ui.sharing.calendar != CalendarAccess.Off && !ui.canWrite) {
        item { Paragraph(stringResource(R.string.feature_settings_phone_calendar_denied), error = true) }
    }
    if (ui.sharing.calendar != CalendarAccess.Full) return
    item {
        Column {
            PhoneSharing.FUTURE_DAYS.forEach { days ->
                val label = when (days) {
                    30 -> R.string.feature_settings_phone_window_30
                    60 -> R.string.feature_settings_phone_window_60
                    else -> R.string.feature_settings_phone_window_180
                }
                Choice(stringResource(label), ui.sharing.futureDays == days) { viewModel.setFutureDays(days) }
            }
        }
    }
    item {
        SwitchRow(stringResource(R.string.feature_settings_phone_details), ui.sharing.details, viewModel::setDetails)
    }
    if (ui.sharing.details) {
        item {
            SwitchRow(
                stringResource(R.string.feature_settings_phone_emails),
                ui.sharing.attendeeEmails,
                viewModel::setAttendeeEmails,
            )
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_phone_details_note)) }
    if (ui.calendars.isNotEmpty()) {
        item { Header(stringResource(R.string.feature_settings_phone_calendars)) }
        items(ui.calendars, key = { it.id }) { calendar ->
            val shown = calendar.id.toString() !in ui.sharing.hiddenCalendars
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = shown, role = Role.Checkbox) { viewModel.setCalendarShown(calendar.id, it) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = shown, onCheckedChange = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(calendar.title)
                    Text(
                        calendar.account,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    item { Header(stringResource(R.string.feature_settings_phone_changes)) }
    item {
        Column {
            val ask = stringResource(R.string.feature_settings_phone_ask)
            val auto = stringResource(R.string.feature_settings_phone_auto_add)
            Choice(ask, !ui.sharing.autoAdd) { viewModel.setAutoAdd(false) }
            Choice(auto, ui.sharing.autoAdd) { viewModel.setAutoAdd(true) }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_phone_changes_note)) }
}

@Composable
private fun SyncLine(ui: PhoneDataUi, viewModel: PhoneDataViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val last = ui.state.lastSyncMs
        Text(
            if (last == null) {
                stringResource(R.string.feature_settings_phone_not_synced)
            } else {
                stringResource(
                    R.string.feature_settings_phone_last_synced,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(last)),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.failure?.let {
            Text(failureText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = viewModel::syncNow, enabled = !ui.syncing) {
            val label = if (ui.syncing) {
                R.string.feature_settings_phone_syncing
            } else {
                R.string.feature_settings_phone_sync_now
            }
            Text(stringResource(label))
        }
    }
}

@Composable
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Paragraph(text: String, muted: Boolean = true, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            error -> MaterialTheme.colorScheme.error
            muted -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

/** Alarms and timers Hermes sets, which this app rings; Android must let it ring at exact times. */
private fun LazyListScope.alarmsSection(ui: PhoneDataUi, viewModel: PhoneDataViewModel, onAllowAlarms: () -> Unit) {
    item { Header(stringResource(R.string.feature_settings_phone_alarms)) }
    item {
        SwitchRow(stringResource(R.string.feature_settings_phone_alarms_switch), ui.sharing.alarms) { on ->
            if (on && !ui.canRingAlarms) onAllowAlarms()
            viewModel.setAlarms(on)
        }
    }
    if (ui.sharing.alarms && !ui.canRingAlarms) {
        item { Paragraph(stringResource(R.string.feature_settings_phone_alarms_denied), error = true) }
        item {
            TextButton(onClick = onAllowAlarms, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.feature_settings_phone_alarms_allow))
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_phone_alarms_note)) }
}

/** The people the user picks one by one, with who they are to them. */
private fun LazyListScope.peopleSection(
    ui: PhoneDataUi,
    viewModel: PhoneDataViewModel,
    contactsDenied: Boolean,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    item { Header(stringResource(R.string.feature_settings_phone_people)) }
    item {
        SwitchRow(
            stringResource(R.string.feature_settings_phone_people_switch),
            ui.sharing.contacts,
            viewModel::setContacts,
        )
    }
    items(ui.people, key = { it.id }) { person -> PersonRow(person, viewModel) }
    item {
        TextButton(
            onClick = onAdd,
            enabled = ui.people.size < PeopleBook.MAX_PEOPLE,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.feature_settings_phone_people_add)) }
    }
    if (contactsDenied) {
        item { Paragraph(stringResource(R.string.feature_settings_phone_people_denied), error = true) }
        item {
            TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.feature_settings_phone_people_settings))
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_phone_people_note)) }
}

@Composable
private fun PersonRow(person: PhonePerson, viewModel: PhoneDataViewModel) {
    var relation by remember(person.id) { mutableStateOf(person.relation.orEmpty()) }
    val focusManager = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(person.name, style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = relation,
                onValueChange = { relation = it.take(PeopleBook.RELATION_CHARS) },
                placeholder = { Text(stringResource(R.string.feature_settings_phone_people_relation)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Done closes the keyboard; losing focus saves.
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused &&
                        relation != person.relation.orEmpty()
                    ) {
                        viewModel.setRelation(person.id, relation)
                    }
                },
            )
        }
        IconButton(onClick = { viewModel.removePerson(person.id) }) {
            Icon(Icons.Filled.Close, stringResource(R.string.feature_settings_phone_people_remove, person.name))
        }
    }
}

/** This app's page in Android's settings, where contacts access can be turned on. */
private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Android's "Alarms & reminders" page for this app. */
private fun openAlarmAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
