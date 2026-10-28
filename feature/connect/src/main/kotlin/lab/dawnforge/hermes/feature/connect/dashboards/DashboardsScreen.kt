package lab.dawnforge.hermes.feature.connect.dashboards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.core.ui.LiveConnectionLine
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.connect.ErrorText
import lab.dawnforge.hermes.feature.connect.FormColumn
import lab.dawnforge.hermes.feature.connect.ProxyHeadersEditor
import lab.dawnforge.hermes.feature.connect.R
import lab.dawnforge.hermes.feature.connect.allValid
import lab.dawnforge.hermes.feature.connect.navigation.RepairRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DashboardsScreen(
    viewModel: DashboardsViewModel,
    onBack: () -> Unit,
    onAddDashboard: () -> Unit,
    onSignInAgain: (address: String, username: String?) -> Unit,
    onNoDashboards: () -> Unit,
    onOpenRepair: (RepairRoute) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val headers by viewModel.headers.collectAsStateWithLifecycle()
    val unlock by viewModel.unlockSetting.collectAsStateWithLifecycle()
    LaunchedEffect(state.loading, state.active) {
        if (!state.loading && state.active == null) onNoDashboards()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_connect_dashboards_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.feature_connect_back)) }
                },
                actions = {
                    TextButton(onClick = onAddDashboard) {
                        Text(stringResource(R.string.feature_connect_add_dashboard))
                    }
                },
            )
        },
    ) { padding ->
        FormColumn(Modifier.padding(padding)) {
            val active = state.active ?: return@FormColumn
            ActiveDashboardCard(
                dashboard = active,
                session = state.session,
                live = state.live,
                held = state.liveHeld,
                onRepair = { viewModel.repairRoute(edit = false)?.let(onOpenRepair) },
                onSetup = { viewModel.repairRoute(edit = true)?.let(onOpenRepair) },
                onRetry = viewModel::retry,
                onSignInAgain = { onSignInAgain(active.url.toString(), active.username) },
                onSignOut = viewModel::signOutActive,
                onRemove = { viewModel.askToRemove(active) },
                onEditHeaders = viewModel::editHeaders.takeIf { ProxyHeaderRules.origin(active.url) != null },
                unlock = unlock,
                onUnlockChange = viewModel::setUnlock,
            )

            if (state.others.isNotEmpty()) {
                Text(
                    stringResource(R.string.feature_connect_other_dashboards),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.others.forEach { dashboard ->
                    OtherDashboardRow(
                        dashboard = dashboard,
                        onUse = { viewModel.use(dashboard.id) },
                        onRemove = { viewModel.askToRemove(dashboard) },
                    )
                }
            }
        }
    }

    headers?.let { rows ->
        AlertDialog(
            onDismissRequest = viewModel::closeHeaders,
            title = { Text(stringResource(R.string.feature_connect_headers_title)) },
            text = {
                ProxyHeadersEditor(
                    rows,
                    viewModel::changeHeaders,
                    enabled = true,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::saveHeaders, enabled = rows.allValid()) {
                    Text(stringResource(R.string.feature_connect_headers_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::closeHeaders) { Text(stringResource(R.string.feature_connect_cancel)) }
            },
        )
    }

    state.pendingRemoval?.let { dashboard ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRemoval,
            title = { Text(stringResource(R.string.feature_connect_remove_title)) },
            text = { Text(stringResource(R.string.feature_connect_remove_body, dashboard.url.toString())) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoval) {
                    Text(stringResource(R.string.feature_connect_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRemoval) { Text(stringResource(R.string.feature_connect_cancel)) }
            },
        )
    }
}

@Composable
private fun ActiveDashboardCard(
    dashboard: Dashboard,
    session: SessionUi,
    live: GatewayState,
    held: GatewayState?,
    onRepair: () -> Unit,
    onSetup: () -> Unit,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
    onEditHeaders: (() -> Unit)?,
    unlock: Boolean?,
    onUnlockChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(dashboard.url.toString(), style = MaterialTheme.typography.titleMedium)
            dashboard.username?.let {
                Text(
                    stringResource(R.string.feature_connect_signed_in_as, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SessionLine(session)
            if (session is SessionUi.Ready || held != null) {
                LiveConnectionLine(live, held = held, onRepair = onRepair, onReconnect = onRetry)
            }
            unlock?.let { on ->
                Row(
                    Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onUnlockChange),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.feature_connect_unlock), Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = null)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (session) {
                    is SessionUi.Unreachable -> {
                        Button(onClick = onRetry) { Text(stringResource(R.string.feature_connect_retry)) }
                        if (held == null) {
                            OutlinedButton(onClick = onRepair) { Text(stringResource(R.string.feature_connect_repair)) }
                        }
                    }

                    is SessionUi.NeedsSignIn -> Button(onClick = onSignInAgain) {
                        Text(stringResource(R.string.feature_connect_sign_in_again))
                    }

                    else -> Unit
                }
                if (session is SessionUi.Ready) {
                    OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.feature_connect_sign_out)) }
                }
                onEditHeaders?.let {
                    TextButton(onClick = it) { Text(stringResource(R.string.feature_connect_headers_title)) }
                }
                TextButton(onClick = onSetup) { Text(stringResource(R.string.feature_connect_setup_title)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.feature_connect_remove)) }
            }
        }
    }
}

@Composable
private fun SessionLine(session: SessionUi) {
    when (session) {
        SessionUi.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.feature_connect_status_checking))
        }

        is SessionUi.Ready -> Text(
            session.hermesVersion?.let { stringResource(R.string.feature_connect_status_ready_version, it) }
                ?: stringResource(R.string.feature_connect_status_ready),
            color = MaterialTheme.colorScheme.primary,
        )

        is SessionUi.NeedsSignIn -> {
            Text(stringResource(R.string.feature_connect_status_needs_sign_in))
            session.reason?.let { ErrorText(failureText(it)) }
        }

        is SessionUi.Unreachable -> {
            Text(stringResource(R.string.feature_connect_status_unreachable))
            ErrorText(failureText(session.failure))
        }
    }
}

@Composable
private fun OtherDashboardRow(dashboard: Dashboard, onUse: () -> Unit, onRemove: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dashboard.url.toString(), style = MaterialTheme.typography.bodyLarge)
            dashboard.username?.let {
                Text(
                    stringResource(R.string.feature_connect_signed_in_as, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUse) { Text(stringResource(R.string.feature_connect_switch)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.feature_connect_remove)) }
            }
        }
    }
}
