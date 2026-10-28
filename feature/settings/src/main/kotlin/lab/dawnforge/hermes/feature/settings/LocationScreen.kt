package lab.dawnforge.hermes.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.time.OffsetDateTime
import java.util.Date
import lab.dawnforge.hermes.core.data.phonedata.location.LocationPause
import lab.dawnforge.hermes.core.data.phonedata.location.LocationPrecision
import lab.dawnforge.hermes.core.data.phonedata.location.LocationRead
import lab.dawnforge.hermes.core.data.phonedata.location.LocationRule
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSettings
import lab.dawnforge.hermes.core.data.phonedata.location.LocationSharing
import lab.dawnforge.hermes.core.data.phonedata.location.PlaceKind
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace
import lab.dawnforge.hermes.core.ui.failureText

/** Settings › Location: sharing where the phone is with one Hermes profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocationScreen(viewModel: LocationViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.recheck() }

    var consentFor by rememberSaveable { mutableStateOf<LocationSharing.Layer?>(null) }
    var askingFor by remember { mutableStateOf<LocationSharing.Layer?>(null) }
    var editing by remember { mutableStateOf<PlaceDraft?>(null) }
    var showsWipe by rememberSaveable { mutableStateOf(false) }

    val askAlways = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        askingFor?.let(viewModel::turnOn)
        askingFor = null
    }
    val askWhileInUse =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            val layer = askingFor ?: return@rememberLauncherForActivityResult
            val allowed = answers.values.any { it } || viewModel.state.value.access.whileInUse
            viewModel.recheck()
            if (!allowed) {
                askingFor = null
                return@rememberLauncherForActivityResult
            }
            if (layer == LocationSharing.Layer.Background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // "Allow all the time" is asked on its own, after "while using the app".
                askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                viewModel.turnOn(layer)
                askingFor = null
            }
        }

    fun turnOn(layer: LocationSharing.Layer) {
        askingFor = layer
        val access = ui.access
        when {
            !access.whileInUse -> askWhileInUse.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )

            layer == LocationSharing.Layer.Background && !access.always &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                askAlways.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

            else -> {
                viewModel.turnOn(layer)
                askingFor = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_location)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            introSection(ui, context)
            layerSection(
                ui,
                LocationSharing.Layer.Talking,
                onSwitch = { on ->
                    if (on) {
                        consentFor = LocationSharing.Layer.Talking
                    } else {
                        viewModel.turnOff(
                            LocationSharing.Layer.Talking,
                        )
                    }
                },
                onPrecision = { viewModel.setPrecision(LocationSharing.Layer.Talking, it) },
            )
            layerSection(
                ui,
                LocationSharing.Layer.Background,
                onSwitch = { on ->
                    if (on) {
                        consentFor = LocationSharing.Layer.Background
                    } else {
                        viewModel.turnOff(
                            LocationSharing.Layer.Background,
                        )
                    }
                },
                onPrecision = { viewModel.setPrecision(LocationSharing.Layer.Background, it) },
            )
            placesSection(ui, onEdit = { editing = it })
            if (ui.settings.isOn) pauseSection(ui, viewModel)
            retentionSection(ui, viewModel::setRetention)
            if (ui.config.destination != null) {
                readsSection(ui)
                rulesSection(ui, viewModel::deleteRule)
                item {
                    OutlinedButton(
                        onClick = { showsWipe = true },
                        enabled = !ui.working,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Text(stringResource(R.string.feature_settings_location_wipe, ui.destinationHost.orEmpty()))
                    }
                }
            }
            ui.failure?.let { failure -> item { Paragraph(failureText(failure), error = true) } }
        }
    }

    consentFor?.let { layer ->
        LocationConsentDialog(
            profile = ui.target?.profile.orEmpty(),
            host = ui.target?.host.orEmpty(),
            onContinue = {
                consentFor = null
                turnOn(layer)
            },
            onCancel = { consentFor = null },
        )
    }
    editing?.let { draft ->
        PlaceEditor(
            draft = draft,
            canLocate = ui.access.whileInUse,
            here = viewModel::here,
            search = viewModel::search,
            onSave = { place ->
                editing = null
                viewModel.savePlace(place)
            },
            onDelete = { id ->
                editing = null
                viewModel.removePlace(id)
            },
            onCancel = { editing = null },
        )
    }
    if (showsWipe) {
        AlertDialog(
            onDismissRequest = { showsWipe = false },
            title = { Text(stringResource(R.string.feature_settings_location_wipe_title)) },
            text = { Text(stringResource(R.string.feature_settings_location_wipe_note)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    val danger = MaterialTheme.colorScheme.error
                    TextButton(onClick = {
                        showsWipe = false
                        viewModel.wipe(everything = false)
                    }) {
                        Text(stringResource(R.string.feature_settings_location_wipe_phone), color = danger)
                    }
                    TextButton(onClick = {
                        showsWipe = false
                        viewModel.wipe(everything = true)
                    }) {
                        Text(stringResource(R.string.feature_settings_location_wipe_all), color = danger)
                    }
                    TextButton(onClick = { showsWipe = false }) {
                        Text(stringResource(R.string.feature_settings_location_cancel))
                    }
                }
            },
        )
    }
}

private fun LazyListScope.introSection(ui: LocationUi, context: Context) {
    item { Paragraph(stringResource(R.string.feature_settings_location_intro)) }
    if (ui.target == null && !ui.settings.isOn) {
        item { Paragraph(stringResource(R.string.feature_settings_location_no_dashboard), error = true) }
    }
    if (!ui.settings.isOn) return
    ui.config.destination?.let { destination ->
        item {
            Paragraph(
                stringResource(
                    R.string.feature_settings_location_sharing_with,
                    destination.profile,
                    ui.destinationHost.orEmpty(),
                ),
                muted = false,
            )
        }
    }
    item {
        val until = ui.settings.pausedUntil
        val pause = ui.state.pause
        when {
            ui.paused && until == LocationSettings.FOREVER ->
                Paragraph(stringResource(R.string.feature_settings_location_paused_forever), error = true)

            ui.paused && until != null -> {
                val shown = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(until))
                Paragraph(stringResource(R.string.feature_settings_location_paused_until, shown), error = true)
            }

            pause == LocationPause.SignInRequired -> Paragraph(
                stringResource(R.string.feature_settings_location_pause_sign_in),
                error = true,
            )

            pause == LocationPause.PluginMissing -> Paragraph(
                stringResource(R.string.feature_settings_location_pause_plugin),
                error = true,
            )

            pause == LocationPause.Refused ->
                Paragraph(
                    stringResource(R.string.feature_settings_location_pause_refused, ui.state.refusal.orEmpty()),
                    error = true,
                )

            ui.state.lastFixSent != null -> {
                val sent = ui.state.lastFixSent!!
                // Under a minute reads "just now", not "0 minutes ago".
                if (ui.nowMs - sent < DateUtils.MINUTE_IN_MILLIS) {
                    Paragraph(stringResource(R.string.feature_settings_location_last_sent_now))
                } else {
                    val ago = DateUtils.getRelativeTimeSpanString(sent, ui.nowMs, DateUtils.MINUTE_IN_MILLIS)
                    Paragraph(stringResource(R.string.feature_settings_location_last_sent, ago))
                }
            }

            else -> Paragraph(stringResource(R.string.feature_settings_location_nothing_sent))
        }
    }
    when {
        !ui.access.whileInUse -> {
            item { Paragraph(stringResource(R.string.feature_settings_location_access_off), error = true) }
            item { OpenAppSettings(context) }
        }

        ui.settings.background && !ui.access.always -> {
            item { Paragraph(stringResource(R.string.feature_settings_location_needs_always), error = true) }
            item { OpenAppSettings(context) }
        }
    }
    if (ui.access.approximateOnly) {
        item {
            Paragraph(stringResource(R.string.feature_settings_location_approximate), error = true)
        }
    }
    if (ui.state.pause == null) ui.state.lastError?.let { item { Paragraph(it, error = true) } }
}

@Composable
private fun OpenAppSettings(context: Context) {
    TextButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(stringResource(R.string.feature_settings_location_open_settings)) }
}

private fun LazyListScope.layerSection(
    ui: LocationUi,
    layer: LocationSharing.Layer,
    onSwitch: (Boolean) -> Unit,
    onPrecision: (LocationPrecision) -> Unit,
) {
    val talking = layer == LocationSharing.Layer.Talking
    val on = if (talking) ui.settings.talking else ui.settings.background
    val precision = if (talking) ui.settings.talkingPrecision else ui.settings.backgroundPrecision
    val texts = if (talking) LayerTexts.Talking else LayerTexts.Background
    item {
        Header(stringResource(texts.title))
    }
    item {
        SwitchRow(
            stringResource(texts.switch),
            on,
        ) { wanted -> if (!ui.working && (on || ui.target != null)) onSwitch(wanted) }
    }
    item {
        Paragraph(stringResource(texts.note))
    }
    item { Paragraph(stringResource(R.string.feature_settings_location_detail_title), muted = false) }
    LocationPrecision.entries.forEach { choice ->
        item {
            Option(precisionTitle(choice), choice == precision) {
                if (choice != precision) onPrecision(choice)
            }
        }
    }
    item { Paragraph(precisionSummary(precision)) }
    if (!talking && on && ui.host.status?.locateAvailable != true) {
        item { Paragraph(stringResource(R.string.feature_settings_location_no_locate)) }
    }
}

/** Each layer's title, switch and note. */
private enum class LayerTexts(val title: Int, val switch: Int, val note: Int) {
    Talking(
        R.string.feature_settings_location_talking,
        R.string.feature_settings_location_talking_switch,
        R.string.feature_settings_location_talking_note,
    ),
    Background(
        R.string.feature_settings_location_background,
        R.string.feature_settings_location_background_switch,
        R.string.feature_settings_location_background_note,
    ),
}

@Composable
private fun precisionTitle(precision: LocationPrecision) = stringResource(
    when (precision) {
        LocationPrecision.Places -> R.string.feature_settings_location_precision_places
        LocationPrecision.Area -> R.string.feature_settings_location_precision_area
        LocationPrecision.Precise -> R.string.feature_settings_location_precision_precise
    },
)

@Composable
private fun precisionSummary(precision: LocationPrecision) = stringResource(
    when (precision) {
        LocationPrecision.Places -> R.string.feature_settings_location_precision_places_note
        LocationPrecision.Area -> R.string.feature_settings_location_precision_area_note
        LocationPrecision.Precise -> R.string.feature_settings_location_precision_precise_note
    },
)

@Composable
internal fun kindTitle(kind: PlaceKind) = stringResource(
    when (kind) {
        PlaceKind.Home -> R.string.feature_settings_location_kind_home
        PlaceKind.Work -> R.string.feature_settings_location_kind_work
        PlaceKind.Gym -> R.string.feature_settings_location_kind_gym
        PlaceKind.School -> R.string.feature_settings_location_kind_school
        PlaceKind.Other -> R.string.feature_settings_location_kind_other
    },
)

private fun LazyListScope.placesSection(ui: LocationUi, onEdit: (PlaceDraft) -> Unit) {
    item { Header(stringResource(R.string.feature_settings_location_places)) }
    items(ui.config.places, key = { it.id }) { place ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = false, role = Role.Button) { onEdit(PlaceDraft.of(place)) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    kindTitle(place.kind) + " · " +
                        stringResource(R.string.feature_settings_location_metres, place.radius.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (place.isPrivate) {
                Icon(
                    Icons.Filled.Lock,
                    stringResource(R.string.feature_settings_location_private),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item {
        TextButton(
            onClick = { onEdit(PlaceDraft()) },
            enabled = ui.config.places.size < SavedPlace.MAX_COUNT,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.feature_settings_location_add_place)) }
    }
    item { Paragraph(stringResource(R.string.feature_settings_location_places_note)) }
}

private fun LazyListScope.pauseSection(ui: LocationUi, viewModel: LocationViewModel) {
    item { Header(stringResource(R.string.feature_settings_location_pause)) }
    if (ui.paused) {
        item {
            TextButton(
                onClick = viewModel::resume,
                enabled = !ui.working,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.feature_settings_location_resume))
            }
        }
    } else {
        item {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = viewModel::pauseForAnHour, enabled = !ui.working) {
                    Text(stringResource(R.string.feature_settings_location_pause_hour))
                }
                TextButton(onClick = viewModel::pauseUntilTomorrow, enabled = !ui.working) {
                    Text(stringResource(R.string.feature_settings_location_pause_tomorrow))
                }
                TextButton(onClick = viewModel::pauseUntilResumed, enabled = !ui.working) {
                    Text(stringResource(R.string.feature_settings_location_pause_off))
                }
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_location_pause_note)) }
}

private fun LazyListScope.retentionSection(ui: LocationUi, onPick: (Int) -> Unit) {
    item { Header(stringResource(R.string.feature_settings_location_history)) }
    item { Paragraph(stringResource(R.string.feature_settings_location_history_keep), muted = false) }
    LocationSettings.RETENTION_CHOICES.forEach { days ->
        item {
            Option(
                stringResource(
                    when (days) {
                        0 -> R.string.feature_settings_location_history_latest
                        1 -> R.string.feature_settings_location_history_day
                        7 -> R.string.feature_settings_location_history_week
                        30 -> R.string.feature_settings_location_history_month
                        else -> R.string.feature_settings_location_history_year
                    },
                ),
                ui.settings.retentionDays == days,
            ) { if (ui.settings.retentionDays != days) onPick(days) }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_location_history_note)) }
}

private fun LazyListScope.readsSection(ui: LocationUi) {
    item { Header(stringResource(R.string.feature_settings_location_reads)) }
    val host = ui.host
    if (host.unreachable) {
        item { Paragraph(stringResource(R.string.feature_settings_location_unreachable)) }
    } else {
        host.status?.let {
            item {
                Paragraph(stringResource(R.string.feature_settings_location_reads_today, it.readsToday), muted = false)
            }
        }
    }
    if (host.reads.isEmpty()) {
        if (!host.unreachable) item { Paragraph(stringResource(R.string.feature_settings_location_reads_none)) }
    } else {
        items(host.reads.take(MAX_READS)) { read ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(readAction(read.action), style = MaterialTheme.typography.bodyMedium)
                Text(
                    readDetail(read),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_location_reads_note)) }
}

@Composable
private fun readAction(action: String): String = when {
    action == "maps" || action.startsWith("maps ") -> stringResource(R.string.feature_settings_location_read_maps)
    action == "now" -> stringResource(R.string.feature_settings_location_read_now)
    action == "places" -> stringResource(R.string.feature_settings_location_read_places)
    action == "history" -> stringResource(R.string.feature_settings_location_read_history)
    action == "distance_to" -> stringResource(R.string.feature_settings_location_read_distance)
    action == "locate" -> stringResource(R.string.feature_settings_location_read_locate)
    action == "rules" -> stringResource(R.string.feature_settings_location_read_rules)
    else -> action
}

private fun readDetail(read: LocationRead): String {
    val at = runCatching { OffsetDateTime.parse(read.at).toInstant().toEpochMilli() }.getOrNull()
        ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: read.at
    val title = read.sessionTitle?.takeIf(String::isNotEmpty)
    return listOfNotNull(at, title, read.platform?.takeIf(String::isNotEmpty)).joinToString(" · ")
}

private fun LazyListScope.rulesSection(ui: LocationUi, onDelete: (String) -> Unit) {
    item { Header(stringResource(R.string.feature_settings_location_rules)) }
    if (ui.host.rules.isEmpty()) {
        item { Paragraph(stringResource(R.string.feature_settings_location_rules_none)) }
        return
    }
    items(ui.host.rules, key = { it.id }) { rule ->
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.what, style = MaterialTheme.typography.bodyMedium)
                Text(
                    ruleDetail(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onDelete(rule.id) }, enabled = rule.id !in ui.deletingRules) {
                Icon(Icons.Filled.Delete, stringResource(R.string.feature_settings_location_rule_delete))
            }
        }
    }
}

/** For example "Any saved place · arrive or leave · every time". */
@Composable
private fun ruleDetail(rule: LocationRule): String {
    val parts = mutableListOf<String>()
    parts += when {
        rule.isAnySavedPlace -> stringResource(R.string.feature_settings_location_rule_any_place)
        !rule.placeName.isNullOrEmpty() -> rule.placeName!!
        else -> stringResource(R.string.feature_settings_location_rule_a_place)
    }
    when (rule.event) {
        "arrive" -> parts += stringResource(R.string.feature_settings_location_rule_arrive)
        "leave" -> parts += stringResource(R.string.feature_settings_location_rule_leave)
        "either" -> parts += stringResource(R.string.feature_settings_location_rule_either)
        else -> if (rule.event.isNotEmpty()) parts += rule.event
    }
    when (rule.repeat) {
        "daily" -> parts += stringResource(R.string.feature_settings_location_rule_daily)
        "every" -> parts += stringResource(R.string.feature_settings_location_rule_every)
    }
    rule.days?.let { parts += it }
    rule.hours?.let { parts += it }
    if (rule.calls) parts += stringResource(R.string.feature_settings_location_rule_calls)
    return parts.joinToString(" · ")
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

/** What the user agrees to before Android asks for location permission. */
@Composable
private fun LocationConsentDialog(profile: String, host: String, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(R.string.feature_settings_location_consent_title), fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.feature_settings_location_consent_where, profile, host))
                Text(stringResource(R.string.feature_settings_location_consent_time))
                Text(stringResource(R.string.feature_settings_location_consent_reads))
                Text(stringResource(R.string.feature_settings_location_consent_hosted))
                Text(stringResource(R.string.feature_settings_location_consent_control))
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.feature_settings_location_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.feature_settings_location_cancel)) }
        },
    )
}

private const val MAX_READS = 50
