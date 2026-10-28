package lab.dawnforge.hermes.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.data.plugin.HostedPlugin
import lab.dawnforge.hermes.core.data.plugin.PluginState
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.ui.failureText

/** What the plugin does here, each needing all of its capabilities. */
private val FEATURES = listOf(
    R.string.feature_settings_push_feature_takeover to listOf("session-takeover", "desktop-views"),
    R.string.feature_settings_plugin_feature_e2e to listOf("e2e-notifications"),
    R.string.feature_settings_push_feature_pairing to listOf("pairing"),
    R.string.feature_settings_push_feature_sources to listOf("reply-sources"),
    R.string.feature_settings_push_feature_artifacts to listOf("artifacts"),
    R.string.feature_settings_push_feature_cards to listOf("present-v1"),
    R.string.feature_settings_push_feature_settings to listOf("talaria-settings"),
    R.string.feature_settings_push_feature_calls to listOf("hermes-calls"),
    R.string.feature_settings_push_feature_updates to listOf("self-restart"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PushPluginScreen(viewModel: PushPluginViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_push)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Overview(state, viewModel) }
            state.status?.let { status ->
                item { CardHeader(stringResource(R.string.feature_settings_push_does)) }
                FEATURES.forEach { (label, needs) ->
                    item {
                        FeatureRow(stringResource(label), served = needs.all(status::reports))
                    }
                }
            }
            item { Manual(state) }
        }
    }
    if (state.askingUpdate) {
        AlertDialog(
            onDismissRequest = viewModel::cancelAsk,
            title = { Text(stringResource(R.string.feature_settings_push_update_title)) },
            text = { Text(stringResource(R.string.feature_settings_push_update_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::update) {
                    Text(stringResource(R.string.feature_settings_push_update_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelAsk) { Text(stringResource(R.string.feature_settings_cancel)) }
            },
        )
    }
}

@Composable
private fun FeatureRow(label: String, served: Boolean) {
    val note = stringResource(R.string.feature_settings_push_update_needed)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = note.takeIf { !served }?.let { { Text(it) } },
        trailingContent = {
            if (served) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.feature_settings_push_supported),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(Icons.Filled.Warning, contentDescription = note, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun Overview(state: PushPluginUiState, viewModel: PushPluginViewModel) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_settings_push_intro), style = MaterialTheme.typography.bodyMedium)
        val version = state.status?.runningVersion ?: "—"
        Text(
            stringResource(R.string.feature_settings_push_version, version),
            style = MaterialTheme.typography.titleMedium,
            color = if (state.needsAttention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val failure = state.failure
        Text(
            when {
                state.loading && state.state == null -> stringResource(R.string.feature_settings_push_checking)

                failure != null && state.state == null -> stringResource(
                    R.string.feature_settings_push_unreachable,
                    failureText(failure),
                )

                else -> statusText(state)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val update = state.update
        if (update != null) {
            UpdateProgress(update, onCancel = viewModel::cancelUpdate, onDismiss = viewModel::dismissUpdate)
        } else if (state.updateAvailable || state.restartPending) {
            Button(onClick = viewModel::askUpdate) {
                Text(
                    when {
                        state.offersInstall -> stringResource(R.string.feature_settings_push_install)

                        state.restartPending && !state.updateAvailable -> stringResource(
                            R.string.feature_settings_push_finish,
                        )

                        else -> stringResource(R.string.feature_settings_push_update_to, state.offered)
                    },
                )
            }
        }
    }
}

@Composable
private fun statusText(state: PushPluginUiState): String = when (val current = state.state) {
    is PluginState.Absent -> stringResource(
        when (current.hosted) {
            HostedPlugin.NotServed -> R.string.feature_settings_push_not_served
            else -> R.string.feature_settings_push_missing
        },
    )

    is PluginState.Reported -> when {
        state.restartPending -> stringResource(R.string.feature_settings_push_restart_due)
        state.updateAvailable -> stringResource(R.string.feature_settings_push_available, state.offered)
        else -> stringResource(R.string.feature_settings_push_up_to_date)
    }

    null -> ""
}

@Composable
private fun UpdateProgress(update: PluginUpdate, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (update.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(updateText(update), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        val label = if (update.running) R.string.feature_settings_cancel else R.string.feature_settings_push_ok
        TextButton(onClick = if (update.running) onCancel else onDismiss) { Text(stringResource(label)) }
    }
}

@Composable
private fun updateText(update: PluginUpdate): String = when (update) {
    is PluginUpdate.Downloading -> stringResource(
        when {
            update.installing -> R.string.feature_settings_push_step_install
            else -> R.string.feature_settings_push_step_download
        },
    )

    PluginUpdate.RestartingGateway -> stringResource(R.string.feature_settings_push_step_gateway)

    is PluginUpdate.WaitingForChats -> stringResource(R.string.feature_settings_push_step_chats, update.count)

    PluginUpdate.RestartingDashboard -> stringResource(R.string.feature_settings_push_step_dashboard)

    is PluginUpdate.Updated -> update.version?.let { stringResource(R.string.feature_settings_push_ready, it) }
        ?: stringResource(R.string.feature_settings_push_up_to_date)

    is PluginUpdate.NeedsDashboardRestart -> stringResource(R.string.feature_settings_push_restart_dashboard)

    is PluginUpdate.Failed -> stringResource(
        R.string.feature_settings_push_update_failed,
        update.detail ?: update.failure?.let { failureText(it) }.orEmpty(),
    )

    PluginUpdate.Unsupported -> stringResource(R.string.feature_settings_push_cant_update)

    PluginUpdate.GatewayNotBack -> stringResource(R.string.feature_settings_push_gateway_not_back)

    PluginUpdate.DashboardNotBack -> stringResource(R.string.feature_settings_push_dashboard_not_back)
}

/** The commands to run on the host when the app can't update the plugin. */
@Composable
private fun Manual(state: PushPluginUiState) {
    val context = LocalContext.current
    val hosted = (state.state as? PluginState.Absent)?.hosted
    val firstLabel = when (hosted) {
        HostedPlugin.NotServed -> R.string.feature_settings_push_cmd_enable
        HostedPlugin.Missing -> R.string.feature_settings_push_cmd_install
        else -> R.string.feature_settings_push_cmd_update
    }
    val firstCommand = when (hosted) {
        HostedPlugin.NotServed -> "hermes plugins enable ${PushPluginRepository.NAME}"
        HostedPlugin.Missing -> "hermes plugins install ${PushPluginRepository.REPOSITORY} --enable"
        else -> "hermes plugins update ${PushPluginRepository.NAME}"
    }
    val commands = listOf(
        firstLabel to firstCommand,
        R.string.feature_settings_push_cmd_gateway to "hermes gateway restart",
    )
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.feature_settings_push_manual), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.feature_settings_push_manual_intro), style = MaterialTheme.typography.bodySmall)
        for ((label, command) in commands) {
            Column(
                Modifier.fillMaxWidth().clickable {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(command, command))
                    Toast.makeText(context, R.string.feature_settings_push_copied, Toast.LENGTH_SHORT).show()
                }.padding(vertical = 6.dp),
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                Text(command, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
        Text(stringResource(R.string.feature_settings_push_manual_then), style = MaterialTheme.typography.bodySmall)
    }
}
