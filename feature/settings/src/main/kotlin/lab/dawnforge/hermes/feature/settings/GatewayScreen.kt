package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.settings.Connector
import lab.dawnforge.hermes.core.data.settings.ConnectorHealth
import lab.dawnforge.hermes.core.data.settings.RestartStage
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayScreen(viewModel: GatewayViewModel, onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refresh() }
    }
    var confirming by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings_gateway)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.restarting) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.feature_settings_refresh))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                state.address?.let { ValueRow(stringResource(R.string.feature_settings_gateway_address), it) }
                val connected = link.state is GatewayState.Connected
                ListItem(
                    headlineContent = { Text(stringResource(R.string.feature_settings_gateway_link)) },
                    supportingContent = { Text(linkText(link.state)) },
                    leadingContent = { Dot(if (connected) ConnectorHealth.Up else ConnectorHealth.Coming) },
                    trailingContent = {
                        if (!connected) {
                            TextButton(onClick = viewModel::reconnect) {
                                Text(stringResource(R.string.feature_settings_reconnect))
                            }
                        }
                    },
                )
            }
            item { GatewayHealthRows(state) }
            item {
                RestartSection(
                    state = state,
                    onRestart = { confirming = true },
                    onDismiss = viewModel::dismissRestart,
                )
            }
            item { CardHeader(stringResource(R.string.feature_settings_connectors)) }
            state.connectorsFailure?.let { failure ->
                item { ErrorText(stringResource(R.string.feature_settings_connectors_failed, failureText(failure))) }
            }
            if (state.connectors.isEmpty() && state.connectorsFailure == null) {
                item {
                    Text(
                        stringResource(R.string.feature_settings_connectors_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(state.connectors, key = Connector::id) { ConnectorRow(it) }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.feature_settings_logs)) },
                    supportingContent = { Text(stringResource(R.string.feature_settings_logs_detail)) },
                    modifier = Modifier.clickable(onClick = onOpenLogs),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.feature_settings_restart_title)) },
            text = { Text(stringResource(R.string.feature_settings_restart_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.restart()
                    },
                ) { Text(stringResource(R.string.feature_settings_restart_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.feature_settings_cancel)) }
            },
        )
    }
}

@Composable
private fun GatewayHealthRows(state: GatewayUiState) {
    Column {
        state.healthFailure?.let {
            ErrorText(stringResource(R.string.feature_settings_gateway_failed_read, failureText(it)))
        }
        val health = state.health ?: return@Column
        val stateText = stringResource(
            when {
                !health.running -> if (health.state == "startup_failed") {
                    R.string.feature_settings_gateway_failed
                } else {
                    R.string.feature_settings_gateway_stopped
                }

                health.state == "starting" -> R.string.feature_settings_gateway_starting

                health.state == "draining" -> R.string.feature_settings_gateway_draining

                health.state == "degraded" -> R.string.feature_settings_gateway_degraded

                else -> R.string.feature_settings_gateway_running
            },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_settings_gateway_state)) },
            supportingContent = {
                Column {
                    Text(stateText)
                    if (!health.running) {
                        health.exitReason?.let { Text(stringResource(R.string.feature_settings_gateway_exit, it)) }
                    }
                }
            },
            leadingContent = {
                Dot(
                    when {
                        health.running && health.state in setOf(null, "running") -> ConnectorHealth.Up
                        health.running -> ConnectorHealth.Coming
                        else -> ConnectorHealth.Down
                    },
                )
            },
        )
        health.version?.let { ValueRow(stringResource(R.string.feature_settings_gateway_version), it) }
        if (health.activeAgents > 0) {
            ValueRow(stringResource(R.string.feature_settings_gateway_agents), health.activeAgents.toString())
        }
    }
}

@Composable
private fun RestartSection(state: GatewayUiState, onRestart: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRestart, enabled = !state.restarting) {
            Text(stringResource(R.string.feature_settings_restart))
        }
        state.restartFailure?.let {
            ErrorText(stringResource(R.string.feature_settings_restart_failed) + ". " + failureText(it), padded = false)
        }
        val stage = state.restart ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!stage.finished) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    restartText(stage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (stage) {
                        is RestartStage.Failed, RestartStage.DidntStart, RestartStage.NotBackYet ->
                            MaterialTheme.colorScheme.error

                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                val line = (stage as? RestartStage.Failed)?.line ?: state.restartLine
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (stage.finished) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.feature_settings_restart_dismiss))
                }
            }
        }
    }
}

@Composable
private fun restartText(stage: RestartStage): String = when (stage) {
    RestartStage.Asking -> stringResource(R.string.feature_settings_restart_asking)

    RestartStage.Draining -> stringResource(R.string.feature_settings_restart_draining)

    RestartStage.Stopping -> stringResource(R.string.feature_settings_restart_stopping)

    RestartStage.Starting -> stringResource(R.string.feature_settings_restart_starting)

    is RestartStage.Done -> if (stage.sharedWith.isEmpty()) {
        stringResource(R.string.feature_settings_restart_done)
    } else {
        stringResource(R.string.feature_settings_restart_done_shared, stage.sharedWith.joinToString(", "))
    }

    is RestartStage.Failed -> stringResource(R.string.feature_settings_restart_failed)

    RestartStage.DidntStart -> stringResource(R.string.feature_settings_restart_didnt_start)

    RestartStage.NotBackYet -> stringResource(R.string.feature_settings_restart_not_back)

    RestartStage.Unsupported -> stringResource(R.string.feature_settings_restart_unsupported)
}

@Composable
private fun linkText(state: GatewayState): String = stringResource(
    when (state) {
        is GatewayState.Connected -> R.string.feature_settings_link_connected
        is GatewayState.Connecting -> R.string.feature_settings_link_connecting
        is GatewayState.Waiting -> R.string.feature_settings_link_waiting
        is GatewayState.SignInRequired -> R.string.feature_settings_link_sign_in
        GatewayState.Stopped -> R.string.feature_settings_link_stopped
    },
)

@Composable
private fun ConnectorRow(connector: Connector) {
    ListItem(
        headlineContent = {
            Text(
                if (connector.enabled) {
                    connector.name
                } else {
                    stringResource(R.string.feature_settings_connector_off, connector.name)
                },
            )
        },
        supportingContent = {
            Column {
                Text(connector.state.replace('_', ' ').replaceFirstChar(Char::titlecase))
                connector.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        leadingContent = { Dot(connector.health) },
    )
}

@Composable
private fun ValueRow(title: String, value: String) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) })
}

@Composable
private fun ErrorText(text: String, padded: Boolean = true) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = if (padded) Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp) else Modifier,
    )
}

/** The theme's main colour when up, its third while coming up, its error colour otherwise. */
@Composable
private fun Dot(health: ConnectorHealth) {
    val color = when (health) {
        ConnectorHealth.Up -> MaterialTheme.colorScheme.primary
        ConnectorHealth.Coming -> MaterialTheme.colorScheme.tertiary
        ConnectorHealth.Down -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}
