package lab.dawnforge.hermes.feature.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * "Finish setting up", at the top of the chat list for a dashboard just added: the push plugin and
 * notifications, as iOS lists them. It goes once both are done, or on "Not now".
 */
@Composable
internal fun FinishSetupCard(
    setup: FinishSetup,
    onOpenPlugin: () -> Unit,
    onOpenNotifications: () -> Unit,
    onDismiss: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.feature_sessions_setup_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_setup_not_now)) }
            }
            val pluginDetail = when (setup.plugin) {
                SetupPlugin.Checking, SetupPlugin.Install -> R.string.feature_sessions_setup_plugin_needed
                SetupPlugin.Update -> R.string.feature_sessions_setup_plugin_update
                SetupPlugin.Restart -> R.string.feature_sessions_setup_plugin_restart
                SetupPlugin.NotRunning -> R.string.feature_sessions_setup_plugin_not_running
                SetupPlugin.Done -> R.string.feature_sessions_setup_plugin_done
            }
            val pluginButton = when (setup.plugin) {
                SetupPlugin.Checking -> R.string.feature_sessions_setup_check
                SetupPlugin.Install -> R.string.feature_sessions_setup_install
                SetupPlugin.Update -> R.string.feature_sessions_setup_update
                SetupPlugin.Restart -> R.string.feature_sessions_setup_finish
                SetupPlugin.NotRunning -> R.string.feature_sessions_setup_steps
                SetupPlugin.Done -> null
            }
            SetupRow(
                title = stringResource(R.string.feature_sessions_setup_plugin),
                detail = stringResource(pluginDetail),
                button = pluginButton?.let { stringResource(it) },
                checking = setup.plugin == SetupPlugin.Checking,
                onClick = onOpenPlugin,
            )
            SetupRow(
                title = stringResource(R.string.feature_sessions_setup_notifications),
                detail = stringResource(
                    when {
                        setup.notifications -> R.string.feature_sessions_setup_notifications_on
                        else -> R.string.feature_sessions_setup_notifications_off
                    },
                ),
                button = if (setup.notifications) null else stringResource(R.string.feature_sessions_setup_turn_on),
                checking = false,
                onClick = onOpenNotifications,
            )
        }
    }
}

@Composable
private fun SetupRow(title: String, detail: String, button: String?, checking: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)

            button != null -> FilledTonalButton(onClick = onClick) { Text(button) }

            else -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.feature_sessions_setup_done),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
