package lab.dawnforge.hermes.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.R
import lab.dawnforge.hermes.core.data.phonedata.alarms.AlarmBook
import lab.dawnforge.hermes.core.data.phonedata.alarms.PhoneAlarm
import lab.dawnforge.hermes.core.data.phonedata.phone.AutoAdded
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarSource
import lab.dawnforge.hermes.core.data.phonedata.phone.CalendarWriter
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneChange
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneChangeKind
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine

/** Hermes' changes to the calendar and alarms, for the card over the app and the banner after one went in. */
@HiltViewModel
class PhoneChangesViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: PhoneSyncEngine,
    alarms: AlarmBook,
) : ViewModel() {
    private val calendars = CalendarSource(context)

    val prompts: StateFlow<List<PhoneChange>> = engine.prompts
    val autoAdded: StateFlow<AutoAdded?> = engine.autoAdded
    val alarms: StateFlow<List<PhoneAlarm>> = alarms.alarms.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun answer(change: PhoneChange, approve: Boolean) {
        viewModelScope.launch { engine.answer(change, approve) }
    }

    fun undo(added: AutoAdded) {
        viewModelScope.launch { engine.undo(added) }
    }

    fun dismissAutoAdded() = engine.dismissAutoAdded()

    /** The name of the calendar a hashed id names, for "In Work". */
    fun calendarName(hashed: String?): String? {
        val calendars = calendars.calendars()
        val chosen = hashed?.let { id -> calendars.firstOrNull { CalendarSource.calendarId(it.id) == id } }
            ?: calendars.firstOrNull { it.writable }
        return chosen?.title
    }
}

/**
 * The first change Hermes is waiting on, across the top of the app as on iOS: what it wants, the
 * event or alarm, why, and Not now or the change's own button; and, after something went in
 * without asking, "Hermes added …" with Undo for 8 seconds.
 */
@Composable
internal fun PhoneChangeOverlay(viewModel: PhoneChangesViewModel) {
    val prompts by viewModel.prompts.collectAsStateWithLifecycle()
    val added by viewModel.autoAdded.collectAsStateWithLifecycle()
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val change = prompts.firstOrNull()
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (change != null) {
            ChangeCard(change, prompts.size - 1, alarms, viewModel)
        } else {
            added?.let { AutoAddedBanner(it, viewModel) }
        }
    }
}

@Composable
private fun ChangeCard(change: PhoneChange, more: Int, alarms: List<PhoneAlarm>, viewModel: PhoneChangesViewModel) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(headline(change.kind)), style = MaterialTheme.typography.titleMedium)
            Text(detail(context, change, alarms), style = MaterialTheme.typography.bodyMedium)
            if (change.kind == PhoneChangeKind.AddEvent) {
                viewModel.calendarName(change.text("calendar_id"))?.let {
                    Text(
                        stringResource(R.string.app_change_in_calendar, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            change.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (more > 0) {
                    Text(
                        stringResource(R.string.app_change_more, more),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Row(Modifier.weight(1f)) {}
                }
                TextButton(onClick = { viewModel.answer(change, approve = false) }) {
                    Text(stringResource(R.string.app_change_not_now))
                }
                val destructive =
                    change.kind == PhoneChangeKind.DeleteEvent || change.kind == PhoneChangeKind.CancelAlarm
                Button(
                    onClick = { viewModel.answer(change, approve = true) },
                    colors = if (destructive) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) { Text(stringResource(action(change.kind))) }
            }
        }
    }
}

@Composable
private fun AutoAddedBanner(added: AutoAdded, viewModel: PhoneChangesViewModel) {
    val context = LocalContext.current
    LaunchedEffect(added) {
        delay(BANNER_MS)
        viewModel.dismissAutoAdded()
    }
    ElevatedCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val change = added.change
            val text = if (change.kind == PhoneChangeKind.AddEvent) {
                val calendar = viewModel.calendarName(change.text("calendar_id"))
                if (calendar != null) {
                    stringResource(R.string.app_change_added_to, change.text("title").orEmpty(), calendar)
                } else {
                    stringResource(R.string.app_change_added, change.text("title").orEmpty())
                }
            } else {
                stringResource(R.string.app_change_set, alarmSummary(context, change))
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (added.itemId != null) {
                TextButton(onClick = { viewModel.undo(added) }) { Text(stringResource(R.string.app_change_undo)) }
            }
            TextButton(onClick = viewModel::dismissAutoAdded) { Text(stringResource(R.string.app_change_close)) }
        }
    }
}

private fun headline(kind: PhoneChangeKind): Int = when (kind) {
    PhoneChangeKind.AddEvent -> R.string.app_change_add_event
    PhoneChangeKind.UpdateEvent -> R.string.app_change_update_event
    PhoneChangeKind.DeleteEvent -> R.string.app_change_delete_event
    PhoneChangeKind.SetAlarm -> R.string.app_change_set_alarm
    PhoneChangeKind.SetTimer -> R.string.app_change_set_timer
    PhoneChangeKind.CancelAlarm -> R.string.app_change_cancel_alarm
}

private fun action(kind: PhoneChangeKind): Int = when (kind) {
    PhoneChangeKind.AddEvent -> R.string.app_change_action_add
    PhoneChangeKind.UpdateEvent -> R.string.app_change_action_change
    PhoneChangeKind.DeleteEvent -> R.string.app_change_action_delete
    PhoneChangeKind.SetAlarm -> R.string.app_change_action_set
    PhoneChangeKind.SetTimer -> R.string.app_change_action_start
    PhoneChangeKind.CancelAlarm -> R.string.app_change_action_cancel_alarm
}

/** The event (title · when · where) or alarm (label · when) the change is about. */
private fun detail(context: Context, change: PhoneChange, alarms: List<PhoneAlarm>): String = when (change.kind) {
    PhoneChangeKind.SetAlarm, PhoneChangeKind.SetTimer -> alarmSummary(context, change)

    PhoneChangeKind.CancelAlarm -> alarms.firstOrNull { it.id == change.alarmId }?.let { alarm ->
        listOfNotNull(
            alarm.label ?: context.getString(R.string.app_alarm_alarm),
            AlarmBook.nextRing(alarm, System.currentTimeMillis())?.let(::dateTime),
        ).joinToString(" · ")
    } ?: context.getString(R.string.app_alarm_alarm)

    else -> {
        val allDay = change.flag("all_day") == true
        val start = CalendarWriter.time(change.text("start"), allDay)
        val end = CalendarWriter.time(change.text("end"), allDay)
        val whenText = when {
            start == null -> null
            allDay -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(start))
            end != null -> dateTime(start) + "–" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end))
            else -> dateTime(start)
        }
        listOfNotNull(change.text("title"), whenText, change.text("location")).joinToString(" · ")
    }
}

/** "Wake up · 06:30 weekdays", "Tea · 10 min", "Taxi · Fri 18:00". */
private fun alarmSummary(context: Context, change: PhoneChange): String {
    val label = change.text("label")
    val time = when (change.kind) {
        PhoneChangeKind.SetTimer -> (change.fields["seconds"] as? JsonPrimitive)?.intOrNull?.let { seconds ->
            if (seconds >= MINUTE_S) {
                context.getString(R.string.app_change_minutes, seconds / MINUTE_S)
            } else {
                context.getString(R.string.app_change_seconds, seconds)
            }
        }

        else -> change.text("at")?.let { at ->
            runCatching { dateTime(OffsetDateTime.parse(at).toInstant().toEpochMilli()) }.getOrNull()
        }
            ?: change.text("time")?.let { clock -> listOfNotNull(clock, repeats(context, change)).joinToString(" ") }
    }
    val name =
        label
            ?: context.getString(
                if (change.kind ==
                    PhoneChangeKind.SetTimer
                ) {
                    R.string.app_alarm_timer
                } else {
                    R.string.app_alarm_alarm
                },
            )
    return listOfNotNull(name, time).joinToString(" · ")
}

private fun repeats(context: Context, change: PhoneChange): String? {
    val days = (change.fields["repeats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet()
        ?: return null
    return when (days) {
        AlarmBook.WEEKDAYS.toSet() -> context.getString(R.string.app_change_every_day)
        AlarmBook.WEEKDAYS.take(WORK_DAYS).toSet() -> context.getString(R.string.app_change_weekdays)
        AlarmBook.WEEKDAYS.drop(WORK_DAYS).toSet() -> context.getString(R.string.app_change_weekends)
        else -> AlarmBook.WEEKDAYS.filter { it in days }.joinToString(", ") { it.replaceFirstChar(Char::titlecase) }
    }
}

private fun dateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

private const val BANNER_MS = 8_000L
private const val MINUTE_S = 60
private const val WORK_DAYS = 5
