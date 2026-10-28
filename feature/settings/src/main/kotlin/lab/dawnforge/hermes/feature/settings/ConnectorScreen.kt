package lab.dawnforge.hermes.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.McpServer
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.settings.navigation.ConnectorRoute

/** Where a sign-in to the server stands. */
internal sealed interface SignInStep {
    /** Waiting for the user to finish in the browser; [url] is the page Hermes gave. */
    data class Waiting(val flowId: String, val url: String) : SignInStep

    data object Done : SignInStep

    data class Failed(val reason: String?) : SignInStep

    data object TimedOut : SignInStep

    data object NoPage : SignInStep
}

internal data class ConnectorUiState(
    val name: String,
    val server: McpServer? = null,
    val description: String? = null,
    val loading: Boolean = true,
    val state: ServerState = ServerState.Unknown,
    val signIn: SignInStep? = null,
    val removed: Boolean = false,
    val failure: ConnectionFailure? = null,
)

/** One MCP server: its state and tools, testing it, signing in to it, and removing it. */
@HiltViewModel
internal class ConnectorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val connectors: ConnectorsRepository,
) : ViewModel() {
    private val name = savedStateHandle.toRoute<ConnectorRoute>().name
    private val _state = MutableStateFlow(ConnectorUiState(name))
    val state: StateFlow<ConnectorUiState> = _state.asStateFlow()
    private var waiting: Job? = null

    init {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            coroutineScope {
                val catalog = async { connectors.catalog(id, profile) }
                val server = (connectors.servers(id, profile) as? NetResult.Success)?.value?.firstOrNull {
                    it.name ==
                        name
                }
                _state.update {
                    it.copy(
                        loading = false,
                        server = server,
                        description = (catalog.await() as? NetResult.Success)?.value?.firstOrNull { e ->
                            e.name == name
                        }?.description,
                        state = if (server?.enabled == false) ServerState.Off else it.state,
                    )
                }
                if (server?.enabled == true) test()
            }
        }
    }

    fun test() {
        _state.update { it.copy(state = ServerState.Checking) }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val probe = connectors.test(id, profile, name)
            _state.update {
                it.copy(
                    state = when (probe) {
                        is NetResult.Success -> probe.value.state()
                        is NetResult.Failure -> ServerState.Failed("")
                    },
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val server = _state.value.server ?: return
        _state.update {
            it.copy(server = server.copy(enabled = enabled), state = if (enabled) it.state else ServerState.Off)
        }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            when (val result = connectors.setEnabled(id, profile, name, enabled)) {
                is NetResult.Success -> if (enabled) test()
                is NetResult.Failure -> _state.update { it.copy(server = server, failure = result.failure) }
            }
        }
    }

    /** Asks Hermes for the sign-in page; [open] opens it, then the flow is followed until it ends. */
    fun signIn(open: (String) -> Unit) {
        if (_state.value.signIn is SignInStep.Waiting) return
        waiting?.cancel()
        waiting = viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val start = when (val started = connectors.signIn(id, profile, name)) {
                is NetResult.Failure -> {
                    _state.update { it.copy(failure = started.failure) }
                    return@launch
                }

                is NetResult.Success -> started.value
            }
            val url = start?.url
            if (start == null || url == null) {
                _state.update { it.copy(signIn = start?.error?.let { e -> SignInStep.Failed(e) } ?: SignInStep.NoPage) }
                return@launch
            }
            _state.update { it.copy(signIn = SignInStep.Waiting(start.flowId, url)) }
            open(url)
            repeat(SIGN_IN_POLLS) {
                delay(SIGN_IN_POLL_MS)
                val flow = (connectors.signInStatus(id, start.flowId) as? NetResult.Success)?.value ?: return@repeat
                when (flow.status) {
                    "approved" -> {
                        _state.update { it.copy(signIn = SignInStep.Done) }
                        test()
                        return@launch
                    }

                    "error" -> {
                        _state.update { it.copy(signIn = SignInStep.Failed(flow.error)) }
                        return@launch
                    }
                }
            }
            _state.update { it.copy(signIn = SignInStep.TimedOut) }
        }
    }

    /** Stops waiting, and tells Hermes to drop the flow. */
    fun stopWaiting() {
        val step = _state.value.signIn as? SignInStep.Waiting ?: return
        waiting?.cancel()
        _state.update { it.copy(signIn = null) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            connectors.cancelSignIn(id, step.flowId)
        }
    }

    fun remove() {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            when (val result = connectors.remove(id, profile, name)) {
                is NetResult.Success -> _state.update { it.copy(removed = true) }
                is NetResult.Failure -> _state.update { it.copy(failure = result.failure) }
            }
        }
    }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }

    private companion object {
        const val SIGN_IN_POLL_MS = 2_000L

        /** About five minutes, as long as Hermes waits for the callback. */
        const val SIGN_IN_POLLS = 150
    }
}

@Composable
internal fun ConnectorScreen(viewModel: ConnectorViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    val server = state.server
    DetailScaffold(state.name, onBack) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            StateDot(state.state)
            Text(serverStateText(state.state), style = MaterialTheme.typography.titleSmall)
        }
        state.description?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp)) }
        (state.state as? ServerState.Failed)?.error?.takeIf(String::isNotBlank)?.let {
            SelectionContainer { Banner(it) }
        }
        state.failure?.let { Banner(failureText(it)) }
        if (server != null) {
            val plugin = server.plugin
            if (plugin != null) {
                Text(
                    stringResource(R.string.feature_settings_mcp_from_plugin, plugin),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.feature_settings_turned_on)) },
                    supportingContent = { Text(stringResource(R.string.feature_settings_new_sessions)) },
                    trailingContent = { Switch(checked = server.enabled, onCheckedChange = viewModel::setEnabled) },
                )
            }
            CardHeader(stringResource(R.string.feature_settings_mcp_connection))
            server.url?.let {
                ListItem(overlineContent = {
                    Text(stringResource(R.string.feature_settings_mcp_address))
                }, headlineContent = { Text(it) })
            }
            if (server.url == null) {
                server.runs?.let {
                    ListItem(
                        overlineContent = { Text(stringResource(R.string.feature_settings_mcp_command)) },
                        headlineContent = { Text(it, fontFamily = FontFamily.Monospace) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                val checking = state.state == ServerState.Checking
                OutlinedButton(onClick = viewModel::test, enabled = server.enabled && !checking) {
                    Text(
                        stringResource(
                            if (checking) R.string.feature_settings_mcp_testing else R.string.feature_settings_mcp_test,
                        ),
                    )
                }
                val offerSignIn = server.canSignIn && (state.state == ServerState.NeedsSignIn || server.auth == "oauth")
                if (state.signIn is SignInStep.Waiting) {
                    OutlinedButton(onClick = viewModel::stopWaiting) {
                        Text(stringResource(R.string.feature_settings_mcp_stop_waiting))
                    }
                } else if (offerSignIn) {
                    OutlinedButton(onClick = {
                        viewModel.signIn { url ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: ActivityNotFoundException) {
                                // No browser: the page stays waiting until it times out or is stopped.
                            }
                        }
                    }) { Text(stringResource(R.string.feature_settings_mcp_sign_in)) }
                }
            }
            state.signIn?.let { SignInLine(it) }
            CardHeader(stringResource(R.string.feature_settings_tools))
            ToolList(state)
            TextButton(onClick = {
                confirmRemove = true
            }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.feature_settings_mcp_remove), color = MaterialTheme.colorScheme.error)
            }
        } else if (state.loading) {
            CircularProgressIndicator(Modifier.padding(32.dp))
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.feature_settings_mcp_remove_title, state.name),
            message = stringResource(R.string.feature_settings_mcp_remove_message),
            confirm = stringResource(R.string.feature_settings_remove),
            onConfirm = {
                confirmRemove = false
                viewModel.remove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun SignInLine(step: SignInStep) {
    val (text, error) = when (step) {
        is SignInStep.Waiting -> stringResource(R.string.feature_settings_mcp_sign_in_browser) to false
        SignInStep.Done -> stringResource(R.string.feature_settings_mcp_signed_in) to false
        is SignInStep.Failed -> (step.reason ?: stringResource(R.string.feature_settings_mcp_sign_in_failed)) to true
        SignInStep.TimedOut -> stringResource(R.string.feature_settings_mcp_sign_in_timed_out) to true
        SignInStep.NoPage -> stringResource(R.string.feature_settings_mcp_no_page) to true
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (step is SignInStep.Waiting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ToolList(state: ConnectorUiState) {
    when (val serverState = state.state) {
        is ServerState.On -> if (serverState.tools.isEmpty()) {
            Empty(stringResource(R.string.feature_settings_mcp_tools_none))
        } else {
            Column {
                serverState.tools.forEach { (name, description) ->
                    ListItem(
                        headlineContent = { Text(name, fontFamily = FontFamily.Monospace) },
                        supportingContent = description.takeIf(String::isNotBlank)?.let { { Text(it) } },
                    )
                }
            }
        }

        ServerState.Off -> Empty(stringResource(R.string.feature_settings_mcp_tools_off, state.name))

        ServerState.Checking, ServerState.Unknown -> Empty(stringResource(R.string.feature_settings_mcp_tools_reading))

        ServerState.NeedsSignIn -> Empty(stringResource(R.string.feature_settings_mcp_tools_sign_in, state.name))

        is ServerState.Failed -> Empty(stringResource(R.string.feature_settings_mcp_tools_unavailable))
    }
}
