package lab.dawnforge.hermes.feature.notifications

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.plugin.ReplySource
import lab.dawnforge.hermes.core.data.plugin.ReplySources
import lab.dawnforge.hermes.core.push.PushProblem
import lab.dawnforge.hermes.core.push.PushStore
import lab.dawnforge.hermes.core.push.relay.RelayException
import lab.dawnforge.hermes.core.ui.failureText

/** The Hermes event types a profile can mute, with their labels, in the order shown. */
private const val REPLIES = "response.ready"
private const val DEFAULT_PROFILE = "default"

private val SOURCES = listOf(
    ReplySource.App to R.string.feature_notifications_source_app,
    ReplySource.Desktop to R.string.feature_notifications_source_desktop,
    ReplySource.Messaging to R.string.feature_notifications_source_messaging,
    ReplySource.Terminal to R.string.feature_notifications_source_terminal,
)

private val EVENT_TYPES = listOf(
    "response.ready" to R.string.feature_notifications_type_replies,
    "approval.needed" to R.string.feature_notifications_type_approvals,
    "input.needed" to R.string.feature_notifications_type_questions,
    "turn.failed" to R.string.feature_notifications_type_failures,
    "background_task.finished" to R.string.feature_notifications_type_background,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    // The profile waiting on the permission answer, or ALL for the all-profiles switch.
    var pending by remember { mutableStateOf<String?>(null) }

    fun proceed(profile: String) = if (profile == ALL) viewModel.turnAllOn() else viewModel.turnOn(profile)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        pending?.takeIf { granted }?.let(::proceed)
        pending = null
    }

    fun turnOn(profile: String) {
        permissionDenied = false
        when {
            NotificationManagerCompat.from(context).areNotificationsEnabled() -> proceed(profile)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                pending = profile
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> permissionDenied = true
        }
    }

    fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.feature_notifications_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.feature_notifications_intro), style = MaterialTheme.typography.bodyMedium)
            if (!state.available) {
                Text(
                    stringResource(R.string.feature_notifications_no_firebase),
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            if (permissionDenied) {
                Text(
                    stringResource(R.string.feature_notifications_permission_denied),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = ::openSettings) {
                    Text(stringResource(R.string.feature_notifications_open_settings))
                }
            }
            state.problem?.let { Text(problemText(it), color = MaterialTheme.colorScheme.error) }
            state.dashboard?.let { dashboard ->
                Text(
                    stringResource(R.string.feature_notifications_dashboard, dashboard),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.profiles.isNotEmpty()) {
                AllProfilesCard(
                    allOn = AllProfiles.allOn(state.profiles),
                    working = state.working == ALL,
                    enabled = state.working == null && !AllProfiles.checking(state.profiles),
                    onChange = { on -> if (on) turnOn(ALL) else viewModel.turnAllOff() },
                )
            }
            if (state.profiles.any { it.status == ProfileNotifications.On }) {
                ChoicesCard(
                    preferences = AllProfiles.preferences(state.profiles),
                    sources = AllProfiles.sources(state.profiles),
                    savingSource = state.savingSource,
                    onMute = viewModel::setMuted,
                    onHideText = viewModel::setHideText,
                    onReplySource = viewModel::setReplySource,
                )
            }
            if (state.profiles.isNotEmpty()) {
                ProfilesCard(
                    rows = state.profiles,
                    working = state.working,
                    onTurnOn = ::turnOn,
                    onTurnOff = viewModel::turnOff,
                )
            }
            if (state.others.isNotEmpty()) {
                Text(
                    stringResource(R.string.feature_notifications_others),
                    style = MaterialTheme.typography.titleMedium,
                )
                for (other in state.others) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.feature_notifications_other, other.profile, other.dashboard),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.turnOffOther(other) }) {
                            Text(stringResource(R.string.feature_notifications_turn_off))
                        }
                    }
                }
            }
            state.sourcesFailure?.let {
                Text(
                    stringResource(R.string.feature_notifications_sources_failed, failureText(it)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.profiles.any { it.status == ProfileNotifications.On } || state.others.isNotEmpty()) {
                TextButton(onClick = ::openSettings) { Text(stringResource(R.string.feature_notifications_channels)) }
            }
            BatteryAdvice()
            state.profiles.firstOrNull { it.status == ProfileNotifications.On }?.let { CheckPush(it.name) }
            OutlinedTextField(
                value = state.relayUrl,
                onValueChange = viewModel::onRelayUrlChange,
                label = { Text(stringResource(R.string.feature_notifications_relay)) },
                supportingText = { Text(stringResource(R.string.feature_notifications_relay_help)) },
                singleLine = true,
                // The relay holds this phone's registration: it changes only with nothing paired.
                enabled = state.working == null && state.others.isEmpty() &&
                    state.profiles.none { it.status == ProfileNotifications.On },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One switch for every profile on this phone. */
@Composable
private fun AllProfilesCard(allOn: Boolean, working: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.feature_notifications_all),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.feature_notifications_all_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Switch(checked = allOn, onCheckedChange = onChange, enabled = enabled)
            }
        }
    }
}

/** What notifies, the same for every profile on this phone. */
@Composable
private fun ChoicesCard(
    preferences: PushStore.NotifyPreferences,
    sources: ReplySources?,
    savingSource: Boolean,
    onMute: (String, Boolean) -> Unit,
    onHideText: (Boolean) -> Unit,
    onReplySource: (ReplySource, Boolean) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.feature_notifications_choices),
                style = MaterialTheme.typography.titleSmall,
            )
            for ((type, label) in EVENT_TYPES) {
                SwitchRow(label, checked = type !in preferences.muted) { on -> onMute(type, !on) }
            }
            SwitchRow(R.string.feature_notifications_show_text, checked = !preferences.hideText) { on ->
                onHideText(!on)
            }
            if (sources != null && REPLIES !in preferences.muted) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.feature_notifications_sources_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.feature_notifications_sources_intro),
                    style = MaterialTheme.typography.bodySmall,
                )
                for ((source, label) in SOURCES) {
                    SwitchRow(label, checked = sources.of(source), enabled = !savingSource) { on ->
                        onReplySource(source, on)
                    }
                }
            }
        }
    }
}

/** Each profile, to leave one out; a profile that notifies another phone can be moved here. */
@Composable
private fun ProfilesCard(
    rows: List<ProfileRow>,
    working: String?,
    onTurnOn: (String) -> Unit,
    onTurnOff: (String) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.feature_notifications_profiles),
                style = MaterialTheme.typography.titleSmall,
            )
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.label)
                        if (row.status == ProfileNotifications.OnElsewhere) {
                            Text(
                                stringResource(R.string.feature_notifications_elsewhere_short),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    when {
                        working == row.name || row.status == ProfileNotifications.Checking ->
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)

                        row.status == ProfileNotifications.OnElsewhere ->
                            TextButton(onClick = { onTurnOn(row.name) }, enabled = working == null) {
                                Text(stringResource(R.string.feature_notifications_move_here))
                            }

                        else -> Switch(
                            checked = row.status == ProfileNotifications.On,
                            onCheckedChange = { on -> if (on) onTurnOn(row.name) else onTurnOff(row.name) },
                            enabled = working == null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Android's background limits, while the app isn't allowed unrestricted battery use
 * ([BackgroundLimits]): what that means, and where to allow it. Read again each time the screen
 * comes back, so it goes once the user has allowed it.
 */
@Composable
private fun BatteryAdvice() {
    val context = LocalContext.current
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val unrestricted = remember(resumed) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val advice = BackgroundLimits.advice(unrestricted, Build.MANUFACTURER, Build.BRAND) ?: return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.feature_notifications_background_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.feature_notifications_background_body),
                style = MaterialTheme.typography.bodySmall,
            )
            if (advice == BackgroundLimits.Advice.BatteryAndAutostart) {
                Text(
                    stringResource(R.string.feature_notifications_background_autostart),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.feature_notifications_background_open))
            }
        }
    }
}

/**
 * The commands that confirm pairing and send a test notification, run on the host: the push
 * plugin has no route for a test. Tapping one copies it.
 */
@Composable
private fun CheckPush(profile: String) {
    val context = LocalContext.current
    val prefix = if (profile == DEFAULT_PROFILE) "hermes" else "hermes -p $profile"
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.feature_notifications_check_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.feature_notifications_check_intro), style = MaterialTheme.typography.bodySmall)
            for ((label, command) in listOf(
                R.string.feature_notifications_check_status to "$prefix talaria status",
                R.string.feature_notifications_check_test to "$prefix talaria test",
            )) {
                Column(
                    Modifier.fillMaxWidth().clickable {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(command, command))
                        Toast.makeText(context, R.string.feature_notifications_check_copied, Toast.LENGTH_SHORT).show()
                    }.padding(vertical = 6.dp),
                ) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun problemText(problem: PushProblem): String = when (problem) {
    PushProblem.NoFirebase -> stringResource(R.string.feature_notifications_no_firebase)

    PushProblem.PluginMissing -> stringResource(R.string.feature_notifications_plugin_missing)

    PushProblem.NoRelay -> stringResource(R.string.feature_notifications_no_relay)

    is PushProblem.NoToken -> stringResource(R.string.feature_notifications_no_token) +
        problem.detail?.takeIf(String::isNotBlank)
            ?.let { "\n" + stringResource(R.string.feature_notifications_no_token_reason, it) }.orEmpty()

    PushProblem.ChallengeNotDelivered -> stringResource(R.string.feature_notifications_challenge)

    is PushProblem.Dashboard -> failureText(problem.failure)

    is PushProblem.Relay -> when (val error = problem.error) {
        is RelayException.Unreachable -> stringResource(R.string.feature_notifications_relay_unreachable)

        is RelayException.NoAndroidSupport -> stringResource(R.string.feature_notifications_relay_no_android)

        is RelayException.AppNotAllowed -> stringResource(R.string.feature_notifications_relay_app)

        is RelayException.TokenRefused -> stringResource(R.string.feature_notifications_relay_token)

        is RelayException.RateLimited -> stringResource(R.string.feature_notifications_relay_busy)

        is RelayException.Failed ->
            stringResource(R.string.feature_notifications_relay_failed, error.error ?: error.status.toString())

        else -> stringResource(R.string.feature_notifications_relay_failed, error.message.orEmpty())
    }
}
