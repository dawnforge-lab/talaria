package lab.dawnforge.hermes.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.phonedata.health.HealthAvailability
import lab.dawnforge.hermes.core.data.phonedata.health.HealthCategory
import lab.dawnforge.hermes.core.data.phonedata.health.HealthHistory
import lab.dawnforge.hermes.core.data.phonedata.health.HealthPause

/** Settings › Health: health data from Health Connect, shared with one Hermes profile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HealthScreen(viewModel: HealthViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.recheck() }
    var consent by rememberSaveable { mutableStateOf(false) }
    var turningOff by rememberSaveable { mutableStateOf(false) }
    var turningOn by rememberSaveable { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.recheck()
        if (turningOn) viewModel.turnOn()
        turningOn = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_health)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { Paragraph(stringResource(R.string.feature_settings_health_intro)) }
            when (ui.availability) {
                HealthAvailability.Unavailable -> {
                    item { Paragraph(stringResource(R.string.feature_settings_health_unavailable), error = true) }
                    return@LazyColumn
                }

                HealthAvailability.NeedsUpdate -> {
                    item { Paragraph(stringResource(R.string.feature_settings_health_update), error = true) }
                    item {
                        TextButton(onClick = {
                            openHealthConnectStore(context)
                        }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.feature_settings_health_update_button))
                        }
                    }
                    return@LazyColumn
                }

                HealthAvailability.Available -> Unit
            }
            if (!ui.preferences.isOn) {
                if (ui.target ==
                    null
                ) {
                    item {
                        Paragraph(stringResource(R.string.feature_settings_location_no_dashboard), error = true)
                    }
                }
                item {
                    Button(
                        onClick = { consent = true },
                        enabled = ui.target != null,
                        modifier = Modifier.padding(16.dp),
                    ) { Text(stringResource(R.string.feature_settings_health_turn_on)) }
                }
                return@LazyColumn
            }
            statusSection(ui, onAllow = { ask.launch(ui.wanted) })
            categoriesSection(ui, viewModel)
            historySection(ui, viewModel::setHistory)
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::syncNow, enabled = !ui.working) {
                        Text(stringResource(R.string.feature_settings_health_sync_now))
                    }
                    OutlinedButton(onClick = { turningOff = true }, enabled = !ui.working) {
                        Text(stringResource(R.string.feature_settings_health_turn_off))
                    }
                }
            }
            if (ui.deleteFailed) {
                item {
                    Paragraph(stringResource(R.string.feature_settings_health_delete_failed), error = true)
                }
            }
        }
    }

    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text(stringResource(R.string.feature_settings_health_consent_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            R.string.feature_settings_health_consent_where,
                            ui.target?.profile.orEmpty(),
                            ui.target?.host.orEmpty(),
                        ),
                    )
                    Text(stringResource(R.string.feature_settings_health_consent_reads))
                    Text(stringResource(R.string.feature_settings_health_consent_never))
                    Text(stringResource(R.string.feature_settings_health_consent_stop))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    consent = false
                    turningOn = true
                    ask.launch(viewModel.permissionsToTurnOn())
                }) { Text(stringResource(R.string.feature_settings_location_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    consent = false
                }) { Text(stringResource(R.string.feature_settings_location_cancel)) }
            },
        )
    }
    if (turningOff) {
        AlertDialog(
            onDismissRequest = { turningOff = false },
            title = { Text(stringResource(R.string.feature_settings_health_turn_off)) },
            text = { Text(stringResource(R.string.feature_settings_health_turn_off_note)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        turningOff = false
                        viewModel.turnOff(delete = true)
                    }) {
                        Text(
                            stringResource(R.string.feature_settings_health_turn_off_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = {
                        turningOff = false
                        viewModel.turnOff(delete = false)
                    }) { Text(stringResource(R.string.feature_settings_health_turn_off_keep)) }
                    TextButton(onClick = {
                        turningOff = false
                    }) { Text(stringResource(R.string.feature_settings_location_cancel)) }
                }
            },
        )
    }
}

private fun LazyListScope.statusSection(ui: HealthUi, onAllow: () -> Unit) {
    ui.preferences.destination?.let { destination ->
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
        val state = ui.state
        when (state.pause) {
            HealthPause.SignInRequired -> Paragraph(
                stringResource(R.string.feature_settings_health_pause_sign_in),
                error = true,
            )

            HealthPause.PluginMissing -> Paragraph(
                stringResource(R.string.feature_settings_health_pause_plugin),
                error = true,
            )

            HealthPause.Refused -> Paragraph(
                stringResource(R.string.feature_settings_health_pause_refused, state.refusal.orEmpty()),
                error = true,
            )

            HealthPause.NoAccess -> Paragraph(
                stringResource(R.string.feature_settings_health_pause_access),
                error = true,
            )

            null -> {
                val last = state.lastSync
                if (last == null) {
                    Paragraph(stringResource(R.string.feature_settings_health_not_synced))
                } else {
                    val ago = DateUtils.getRelativeTimeSpanString(
                        last,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    )
                    Paragraph(stringResource(R.string.feature_settings_health_last_sync, ago))
                }
            }
        }
    }
    if (ui.missing.any {
            it != HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND &&
                it != HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
    ) {
        item { Paragraph(stringResource(R.string.feature_settings_health_missing), error = true) }
        item {
            TextButton(onClick = onAllow, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.feature_settings_health_allow))
            }
        }
    } else if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in ui.missing) {
        item { Paragraph(stringResource(R.string.feature_settings_health_background)) }
        item {
            TextButton(onClick = onAllow, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.feature_settings_health_allow))
            }
        }
    }
}

private fun LazyListScope.categoriesSection(ui: HealthUi, viewModel: HealthViewModel) {
    item { Header(stringResource(R.string.feature_settings_health_what)) }
    HealthCategory.entries.forEach { category ->
        val on = category in ui.preferences.categories
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = on, role = Role.Switch) { viewModel.setCategory(category, it) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(categoryTitle(category))
                    Text(
                        categorySummary(category),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = on, onCheckedChange = null)
            }
        }
        if (on && category.hasDetailedReadings) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = category in ui.preferences.detailed, role = Role.Switch) {
                            viewModel.setDetailed(category, it)
                        }
                        .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.feature_settings_health_detailed),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = category in ui.preferences.detailed, onCheckedChange = null)
                }
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_health_what_note)) }
    item { Paragraph(stringResource(R.string.feature_settings_health_unticking_note)) }
}

private fun LazyListScope.historySection(ui: HealthUi, onPick: (HealthHistory) -> Unit) {
    item { Header(stringResource(R.string.feature_settings_health_history)) }
    HealthHistory.entries.forEach { history ->
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = ui.preferences.history == history, role = Role.RadioButton) {
                        onPick(history)
                    }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.preferences.history == history, onClick = null)
                Text(historyTitle(history), Modifier.padding(start = 12.dp))
            }
        }
    }
    item { Paragraph(stringResource(R.string.feature_settings_health_history_note)) }
}

@Composable
private fun categoryTitle(category: HealthCategory) = stringResource(
    when (category) {
        HealthCategory.Activity -> R.string.feature_settings_health_activity
        HealthCategory.Heart -> R.string.feature_settings_health_heart
        HealthCategory.Sleep -> R.string.feature_settings_health_sleep
        HealthCategory.Workouts -> R.string.feature_settings_health_workouts
        HealthCategory.Body -> R.string.feature_settings_health_body
        HealthCategory.Vitals -> R.string.feature_settings_health_vitals
        HealthCategory.Fitness -> R.string.feature_settings_health_fitness
    },
)

@Composable
private fun categorySummary(category: HealthCategory) = stringResource(
    when (category) {
        HealthCategory.Activity -> R.string.feature_settings_health_activity_summary
        HealthCategory.Heart -> R.string.feature_settings_health_heart_summary
        HealthCategory.Sleep -> R.string.feature_settings_health_sleep_summary
        HealthCategory.Workouts -> R.string.feature_settings_health_workouts_summary
        HealthCategory.Body -> R.string.feature_settings_health_body_summary
        HealthCategory.Vitals -> R.string.feature_settings_health_vitals_summary
        HealthCategory.Fitness -> R.string.feature_settings_health_fitness_summary
    },
)

@Composable
private fun historyTitle(history: HealthHistory) = stringResource(
    when (history) {
        HealthHistory.Days30 -> R.string.feature_settings_location_history_month
        HealthHistory.Days90 -> R.string.feature_settings_health_history_90
        HealthHistory.Year -> R.string.feature_settings_location_history_year
        HealthHistory.All -> R.string.feature_settings_health_history_all
    },
)

/** Health Connect's page in the Play Store, to install or update it. */
private fun openHealthConnectStore(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
