package lab.dawnforge.hermes.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.plugin.CallSettings
import lab.dawnforge.hermes.core.data.plugin.CallsStatus
import lab.dawnforge.hermes.core.ui.failureText

/**
 * Settings › Calls from Hermes, as on iOS: whether and when Hermes may phone the user (kept on the
 * host), how a call rings on this phone, and how to ask for one. [onNewChat] opens a new chat in
 * the profile with a message ready to send (a test call, or asking Hermes to watch for something).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallsSettingsScreen(
    viewModel: CallsSettingsViewModel,
    onBack: () -> Unit,
    onNewChat: (profile: String, runtimeId: String, text: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rings by viewModel.ringsLikeCall.collectAsStateWithLifecycle()
    val fullScreen by viewModel.fullScreen.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.recheck() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_calls)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        val status = state.status
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when {
                status != null -> {
                    callSettings(status, rings, fullScreen, state.saving, viewModel)
                    state.failure?.let { failure -> item { ErrorLine(failureText(failure)) } }
                    tryIt(status.settings) { text -> viewModel.newChat(text, onNewChat) }
                    howTo(status.settings)
                }

                state.loading -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }

                else -> item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.feature_settings_calls_unreadable))
                        state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(onClick = viewModel::load) {
                            Text(stringResource(R.string.feature_settings_calls_retry))
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.callSettings(
    status: CallsStatus,
    rings: Boolean,
    fullScreen: Boolean,
    saving: Boolean,
    viewModel: CallsSettingsViewModel,
) {
    val settings = status.settings
    item { CardHeader(stringResource(R.string.feature_settings_calls)) }
    item {
        Toggle(
            R.string.feature_settings_calls_enabled,
            R.string.feature_settings_calls_enabled_help,
            settings.enabled,
        ) { on ->
            viewModel.update { it.copy(enabled = on) }
        }
    }
    if (!status.paired) item { ErrorLine(stringResource(R.string.feature_settings_calls_not_paired)) }
    if (!settings.enabled) return
    item {
        Toggle(R.string.feature_settings_calls_ring, R.string.feature_settings_calls_ring_help, rings) { on ->
            viewModel.setRingsLikeCall(on)
        }
    }
    if (rings && !fullScreen) item { FullScreenRow() }
    item {
        Toggle(
            R.string.feature_settings_calls_when_asked,
            R.string.feature_settings_calls_when_asked_help,
            settings.whenAsked,
        ) { on ->
            viewModel.update { it.copy(whenAsked = on) }
        }
    }
    settings.decides?.let { decides ->
        item {
            Toggle(
                R.string.feature_settings_calls_decides,
                R.string.feature_settings_calls_decides_help,
                decides,
            ) { on ->
                viewModel.update { it.copy(decides = on) }
            }
        }
    }
    settings.alerts?.let { alerts ->
        item {
            Toggle(R.string.feature_settings_calls_alerts, R.string.feature_settings_calls_alerts_help, alerts) { on ->
                viewModel.update { it.copy(alerts = on) }
            }
        }
    }
    item { SubHeader(stringResource(R.string.feature_settings_calls_gap)) }
    items(gapChoices(status.gapBounds, settings.minGapSeconds)) { seconds ->
        Choice(gapLabel(seconds), selected = settings.minGapSeconds == seconds) {
            viewModel.update { it.copy(minGapSeconds = seconds) }
        }
    }
    item {
        Stepper(
            pluralStringResource(R.plurals.feature_settings_calls_per_hour, settings.perHour, settings.perHour),
            settings.perHour,
            status.perHourBounds,
            enabled = !saving,
        ) { value -> viewModel.update { it.copy(perHour = value) } }
    }
    item {
        Stepper(
            pluralStringResource(R.plurals.feature_settings_calls_per_day, settings.perDay, settings.perDay),
            settings.perDay,
            status.perDayBounds,
            enabled = !saving,
        ) { value -> viewModel.update { it.copy(perDay = value) } }
    }
    item { HelpLine(stringResource(R.string.feature_settings_calls_limits_help)) }
    settings.rules?.let { rules ->
        item {
            RulesField(rules, status.rulesMax) { text ->
                viewModel.update { it.copy(rules = CallSettings.rules(text)) }
            }
        }
    }
}

/** Android 14+ asks the user before an app's call screen may show over the lock screen. */
@Composable
private fun FullScreenRow() {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_settings_calls_full_screen)) },
        supportingContent = { Text(stringResource(R.string.feature_settings_calls_full_screen_help)) },
        trailingContent = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            }) { Text(stringResource(R.string.feature_settings_voice_allow)) }
        },
    )
}

@Composable
private fun Stepper(label: String, value: Int, bounds: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onChange(value - 1) }, enabled = enabled && value > bounds.first) { Text("−") }
                TextButton(onClick = { onChange(value + 1) }, enabled = enabled && value < bounds.last) { Text("+") }
            }
        },
    )
}

/** What's worth a call Hermes decides to make, in the user's words; saved with Save. */
@Composable
private fun RulesField(rules: String, max: Int, onSave: (String) -> Unit) {
    var text by remember(rules) { mutableStateOf(rules) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.feature_settings_calls_rules), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(max) },
            placeholder = { Text(stringResource(R.string.feature_settings_calls_rules_placeholder)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${text.length}/$max",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSave(text) }, enabled = CallSettings.rules(text) != rules) {
                Text(stringResource(R.string.feature_settings_save))
            }
        }
        Text(
            stringResource(R.string.feature_settings_calls_rules_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A test call, and asking Hermes to watch for what the note describes: each opens a new chat ready to send. */
private fun LazyListScope.tryIt(settings: CallSettings, onNewChat: (String) -> Unit) {
    if (!settings.enabled) return
    item {
        val prompt = stringResource(R.string.feature_settings_calls_test_prompt)
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_calls_test)) },
            supportingContent = { Text(stringResource(R.string.feature_settings_calls_test_help, prompt)) },
            modifier = Modifier.clickable { onNewChat(prompt) },
        )
    }
    val rules = settings.rules?.takeIf(String::isNotBlank) ?: return
    if (settings.decides == null) return
    item {
        val prompt = stringResource(R.string.feature_settings_calls_watch_prompt, rules)
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_calls_watch)) },
            supportingContent = { Text(stringResource(R.string.feature_settings_calls_watch_help)) },
            modifier = Modifier.clickable { onNewChat(prompt) },
        )
    }
}

private fun LazyListScope.howTo(settings: CallSettings) {
    item { CardHeader(stringResource(R.string.feature_settings_calls_how)) }
    item { HelpLine(stringResource(R.string.feature_settings_calls_how_ask)) }
    if (settings.decides != null) item { HelpLine(stringResource(R.string.feature_settings_calls_how_watch)) }
    if (settings.rules != null) {
        item { HelpLine(stringResource(R.string.feature_settings_calls_how_decides_note)) }
    } else if (settings.decides != null) {
        item { HelpLine(stringResource(R.string.feature_settings_calls_how_decides)) }
    }
    item { HelpLine(stringResource(R.string.feature_settings_calls_how_missed)) }
}

@Composable
private fun SubHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun HelpLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** The spacing choices inside the host's bounds, with the current one kept even when it isn't a usual step. */
private fun gapChoices(bounds: IntRange, current: Int): List<Int> =
    (GAP_STEPS.filter { it in bounds } + current).distinct().sorted()

@Composable
private fun gapLabel(seconds: Int): String = when {
    seconds % SECONDS_PER_HOUR == 0 -> pluralStringResource(
        R.plurals.feature_settings_calls_hours,
        seconds / SECONDS_PER_HOUR,
        seconds / SECONDS_PER_HOUR,
    )

    seconds % SECONDS_PER_MINUTE == 0 ->
        pluralStringResource(
            R.plurals.feature_settings_calls_minutes,
            seconds / SECONDS_PER_MINUTE,
            seconds / SECONDS_PER_MINUTE,
        )

    else -> pluralStringResource(R.plurals.feature_settings_calls_seconds, seconds, seconds)
}

private val GAP_STEPS = listOf(30, 60, 120, 300, 600, 1_800, 3_600)
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3_600
