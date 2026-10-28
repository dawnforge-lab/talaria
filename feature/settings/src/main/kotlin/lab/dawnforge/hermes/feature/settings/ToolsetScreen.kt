package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.BackendKey
import lab.dawnforge.hermes.core.data.settings.BackendStatus
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.TerminalBackend
import lab.dawnforge.hermes.core.data.settings.ToolBackend
import lab.dawnforge.hermes.core.data.settings.Toolset
import lab.dawnforge.hermes.core.data.settings.ToolsetConfig
import lab.dawnforge.hermes.core.data.settings.usageLabel
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.settings.navigation.ToolsetRoute

/** What the toolset page tells, once, after a change. */
internal sealed interface ToolsetNote {
    data object NeedsNous : ToolsetNote

    data class Failed(val what: Int, val subject: String, val failure: ConnectionFailure) : ToolsetNote
}

internal data class ToolsetUiState(
    val name: String,
    val toolset: Toolset? = null,
    val usage: Map<String, Long> = emptyMap(),
    val config: ToolsetConfig? = null,
    val configFailure: ConnectionFailure? = null,
    val terminal: List<TerminalBackend> = emptyList(),
    val terminalFailure: ConnectionFailure? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val note: ToolsetNote? = null,
)

/** One toolset: its tools and their use, its backends and their keys, and where the terminal runs. */
@HiltViewModel
internal class ToolsetViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val capabilities: CapabilitiesRepository,
) : ViewModel() {
    private val name = savedStateHandle.toRoute<ToolsetRoute>().name
    private val _state = MutableStateFlow(ToolsetUiState(name))
    val state: StateFlow<ToolsetUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            coroutineScope {
                val toolsets = async { capabilities.toolsets(id, profile) }
                val usage = async { capabilities.toolUsage(id, profile) }
                val config = async { capabilities.toolsetConfig(id, profile, name) }
                val terminal = async { if (name == TERMINAL) capabilities.terminalBackends(id, profile) else null }
                val readConfig = config.await()
                val readTerminal = terminal.await()
                _state.update { state ->
                    state.copy(
                        loading = false,
                        toolset = (toolsets.await() as? NetResult.Success)?.value?.firstOrNull { it.name == name }
                            ?: state.toolset,
                        usage = (usage.await() as? NetResult.Success)?.value ?: state.usage,
                        config = (readConfig as? NetResult.Success)?.value ?: state.config,
                        configFailure = (readConfig as? NetResult.Failure)?.failure,
                        terminal = (readTerminal as? NetResult.Success)?.value ?: state.terminal,
                        terminalFailure = (readTerminal as? NetResult.Failure)?.failure,
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val toolset = _state.value.toolset ?: return
        _state.update { it.copy(toolset = toolset.copy(enabled = enabled)) }
        act { id, profile ->
            val result = capabilities.setToolset(id, profile, name, enabled)
            if (result is NetResult.Failure) {
                _state.update {
                    it.copy(
                        toolset = toolset,
                        note = ToolsetNote.Failed(
                            R.string.feature_settings_toolset_failed,
                            toolset.label,
                            result.failure,
                        ),
                    )
                }
            }
        }
    }

    /** Saves [value] for [key] to the host; it is never read back, only whether it is set. */
    fun saveKey(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        act { id, profile ->
            when (val result = capabilities.setKey(id, profile, key, trimmed)) {
                is NetResult.Success -> load()

                is NetResult.Failure -> _state.update {
                    it.copy(note = ToolsetNote.Failed(R.string.feature_settings_key_failed, key, result.failure))
                }
            }
        }
    }

    fun useBackend(backend: ToolBackend, capability: String? = null) = act { id, profile ->
        when (val result = capabilities.useBackend(id, profile, name, backend.name, capability)) {
            is NetResult.Success -> {
                if (result.value) _state.update { it.copy(note = ToolsetNote.NeedsNous) }
                load()
            }

            is NetResult.Failure -> _state.update {
                it.copy(
                    note = ToolsetNote.Failed(R.string.feature_settings_backend_failed, backend.name, result.failure),
                )
            }
        }
    }

    fun useTerminal(backend: TerminalBackend) = act { id, profile ->
        when (val result = capabilities.useTerminal(id, profile, backend.name)) {
            is NetResult.Success -> load()

            is NetResult.Failure -> _state.update {
                it.copy(
                    note = ToolsetNote.Failed(R.string.feature_settings_backend_failed, backend.label, result.failure),
                )
            }
        }
    }

    fun noteShown() = _state.update { it.copy(note = null) }

    private fun act(block: suspend (DashboardId, String) -> Unit) {
        if (_state.value.working) return
        _state.update { it.copy(working = true, note = null) }
        viewModelScope.launch {
            target()?.let { (id, profile) -> block(id, profile) }
            _state.update { it.copy(working = false) }
        }
    }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }

    companion object {
        const val TERMINAL = "terminal"
        const val WEB = "web"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolsetScreen(viewModel: ToolsetViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingKey by remember { mutableStateOf<BackendKey?>(null) }
    var confirmTerminal by remember { mutableStateOf<TerminalBackend?>(null) }
    val toolset = state.toolset
    DetailScaffold(toolset?.label ?: state.name, onBack) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@DetailScaffold
        }
        if (toolset != null) {
            val calls = toolset.tools.sumOf { state.usage[it] ?: 0 }
            Header(
                description = toolset.description.ifBlank { null },
                tags = listOfNotNull(
                    if (toolset.configured) null else stringResource(R.string.feature_settings_needs_keys),
                    calls.takeIf { it > 0 }?.let(::usageLabel),
                ),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.feature_settings_turned_on)) },
                supportingContent = { Text(stringResource(R.string.feature_settings_new_sessions)) },
                trailingContent = {
                    Switch(checked = toolset.enabled, onCheckedChange = viewModel::setEnabled, enabled = !state.working)
                },
            )
        }
        state.note?.let { note -> NoteText(note) }
        if (toolset != null && toolset.tools.isNotEmpty()) {
            CardHeader(stringResource(R.string.feature_settings_tools))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                toolset.tools.forEach { tool ->
                    val count = state.usage[tool] ?: 0
                    AssistChip(onClick = {}, label = { Text(if (count > 0) "$tool ${usageLabel(count)}" else tool) })
                }
            }
        }
        if (state.name == ToolsetViewModel.TERMINAL) {
            CardHeader(stringResource(R.string.feature_settings_terminal_runs))
            state.terminalFailure?.let {
                Banner(stringResource(R.string.feature_settings_terminal_failed, failureText(it)))
            }
            state.terminal.forEach { backend ->
                TerminalRow(backend, enabled = !state.working) {
                    if (backend.status == "ready") viewModel.useTerminal(backend) else confirmTerminal = backend
                }
            }
        }
        state.configFailure?.let { Banner(stringResource(R.string.feature_settings_backends_failed, failureText(it))) }
        val config = state.config
        if (config != null && config.hasBackends) {
            CardHeader(stringResource(R.string.feature_settings_backends))
            config.backends.forEach { backend ->
                BackendCard(
                    state.name,
                    config,
                    backend,
                    enabled = !state.working,
                    onUse = viewModel::useBackend,
                    onKey = { editingKey = it },
                )
            }
        }
    }
    editingKey?.let { key ->
        KeyDialog(key, onSave = { value ->
            editingKey = null
            viewModel.saveKey(key.key, value)
        }, onDismiss = { editingKey = null })
    }
    confirmTerminal?.let { backend ->
        AlertDialog(
            onDismissRequest = { confirmTerminal = null },
            title = { Text(stringResource(R.string.feature_settings_terminal_anyway, backend.label)) },
            text = { Text(backend.detail ?: stringResource(R.string.feature_settings_terminal_anyway_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTerminal = null
                    viewModel.useTerminal(backend)
                }) { Text(stringResource(R.string.feature_settings_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmTerminal = null
                }) { Text(stringResource(R.string.feature_settings_cancel)) }
            },
        )
    }
}

@Composable
private fun NoteText(note: ToolsetNote) {
    when (note) {
        ToolsetNote.NeedsNous -> Text(
            stringResource(R.string.feature_settings_backend_nous),
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        is ToolsetNote.Failed -> Banner(stringResource(note.what, note.subject, failureText(note.failure)))
    }
}

@Composable
private fun TerminalRow(backend: TerminalBackend, enabled: Boolean, onPick: () -> Unit) {
    val usable = backend.status != "unavailable"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .selectable(
                backend.active,
                enabled = enabled && usable && !backend.active,
                role = Role.RadioButton,
                onClick = onPick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = backend.active, onClick = null, enabled = usable)
        Column(Modifier.padding(start = 12.dp)) {
            Text(backend.label, style = MaterialTheme.typography.bodyLarge)
            if (backend.description.isNotBlank()) Text(backend.description, style = MaterialTheme.typography.bodySmall)
            when (backend.status) {
                "needs_setup" -> Text(
                    backend.detail ?: stringResource(R.string.feature_settings_backend_needs_setup),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )

                "unavailable" -> Text(
                    backend.detail ?: stringResource(R.string.feature_settings_backend_unavailable),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackendCard(
    toolset: String,
    config: ToolsetConfig,
    backend: ToolBackend,
    enabled: Boolean,
    onUse: (ToolBackend, String?) -> Unit,
    onKey: (BackendKey) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(backend.name, style = MaterialTheme.typography.titleSmall)
            backend.badge?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(statusLabel(backend.status)),
                style = MaterialTheme.typography.labelMedium,
                color = if (backend.status == BackendStatus.Ready) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
            )
        }
        backend.tag?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        backend.keys.forEach { key ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(key.key, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            if (key.set) R.string.feature_settings_key_set else R.string.feature_settings_key_not_set,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (key.set) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = { onKey(key) }, enabled = enabled) {
                    Text(
                        stringResource(
                            if (key.set) R.string.feature_settings_key_replace else R.string.feature_settings_key_add,
                        ),
                    )
                }
            }
        }
        if (toolset == ToolsetViewModel.WEB && backend.capabilities.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(
                        "search",
                        R.string.feature_settings_use_for_search,
                        R.string.feature_settings_used_for_search,
                    ),
                    Triple(
                        "extract",
                        R.string.feature_settings_use_for_extract,
                        R.string.feature_settings_used_for_extract,
                    ),
                ).filter { it.first in backend.capabilities }.forEach { (capability, use, used) ->
                    val serving = config.serves(backend, capability)
                    OutlinedButton(onClick = { onUse(backend, capability) }, enabled = enabled && !serving) {
                        Text(stringResource(if (serving) used else use))
                    }
                }
            }
        } else {
            OutlinedButton(onClick = { onUse(backend, null) }, enabled = enabled && !backend.active) {
                val label = if (backend.active) {
                    R.string.feature_settings_backend_in_use
                } else {
                    R.string.feature_settings_use_backend
                }
                Text(stringResource(label))
            }
        }
    }
}

private fun statusLabel(status: BackendStatus): Int = when (status) {
    BackendStatus.Ready -> R.string.feature_settings_backend_ready
    BackendStatus.NeedsSetup -> R.string.feature_settings_backend_needs_setup
    BackendStatus.NeedsAuth -> R.string.feature_settings_backend_needs_auth
    BackendStatus.NeedsKeys -> R.string.feature_settings_backend_needs_keys
}

/** Asks for a key's value, hidden as it is typed. */
@Composable
private fun KeyDialog(key: BackendKey, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.key) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (key.prompt != key.key) key.prompt else stringResource(R.string.feature_settings_key_note))
                if (key.prompt != key.key) {
                    Text(stringResource(R.string.feature_settings_key_note), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.feature_settings_key_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.feature_settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}
