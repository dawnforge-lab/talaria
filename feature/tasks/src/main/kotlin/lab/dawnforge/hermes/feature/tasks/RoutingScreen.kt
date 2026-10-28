package lab.dawnforge.hermes.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.tasks.KanbanProfile
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.tasks.Orchestration
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText

internal data class RoutingUiState(
    val orchestration: Orchestration? = null,
    val profiles: List<KanbanProfile> = emptyList(),
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val busy: Boolean = false,
    /** The profile Hermes is writing a description for. */
    val describing: String? = null,
    val note: RoutingNote? = null,
)

internal sealed interface RoutingNote {
    data object Saved : RoutingNote

    data class Failed(val failure: ConnectionFailure) : RoutingNote

    data class DescribeFailed(val reason: String?) : RoutingNote
}

/**
 * How Hermes routes tasks: who plans splits, who runs tasks nobody was given, whether triage tasks are split
 * and their parts started on their own, and what each profile is for. It is the same for every board.
 */
@HiltViewModel
internal class RoutingViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val kanban: KanbanRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(RoutingUiState())
    val state: StateFlow<RoutingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch { load() }
    }

    fun noteShown() = _state.update { it.copy(note = null) }

    fun setAutoDecompose(on: Boolean) = orchestrate { kanban.setOrchestration(it, autoDecompose = on) }

    fun setAutoPromote(on: Boolean) = orchestrate { kanban.setOrchestration(it, autoPromoteChildren = on) }

    /** Who plans splits; "" is Hermes' default. */
    fun setOrchestrator(profile: String) = orchestrate { kanban.setOrchestration(it, orchestrator = profile) }

    /** Who runs tasks nobody was given; "" is Hermes' default. */
    fun setDefaultAssignee(profile: String) = orchestrate { kanban.setOrchestration(it, defaultAssignee = profile) }

    private fun orchestrate(block: suspend (DashboardId) -> NetResult<Orchestration>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = block(id)) {
                is NetResult.Success -> _state.update {
                    it.copy(busy = false, orchestration = result.value, note = RoutingNote.Saved)
                }

                is NetResult.Failure -> _state.update {
                    it.copy(busy = false, note = RoutingNote.Failed(result.failure))
                }
            }
        }
    }

    /** Saves what [profile] is for ("" clears it). */
    fun describe(profile: String, description: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val note = when (val result = kanban.describeProfile(id, profile, description)) {
                is NetResult.Success -> RoutingNote.Saved
                is NetResult.Failure -> RoutingNote.Failed(result.failure)
            }
            _state.update { it.copy(busy = false, note = note) }
            loadProfiles(id)
        }
    }

    /** Has Hermes write what [profile] is for from its setup; it is saved as soon as it is written. */
    fun describeAuto(profile: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, describing = profile) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val note = when (val result = kanban.describeProfileAuto(id, profile)) {
                is NetResult.Failure -> RoutingNote.Failed(result.failure)

                is NetResult.Success -> if (result.value.ok) {
                    RoutingNote.Saved
                } else {
                    RoutingNote.DescribeFailed(
                        result.value.reason,
                    )
                }
            }
            loadProfiles(id)
            _state.update { it.copy(busy = false, describing = null, note = note) }
        }
    }

    private suspend fun load() {
        val id = dashboards.activeDashboardId.first() ?: return
        when (val result = kanban.orchestration(id)) {
            is NetResult.Success -> _state.update { it.copy(orchestration = result.value, failure = null) }
            is NetResult.Failure -> _state.update { it.copy(failure = result.failure) }
        }
        loadProfiles(id)
        _state.update { it.copy(loading = false) }
    }

    private suspend fun loadProfiles(id: DashboardId) {
        (kanban.profiles(id) as? NetResult.Success)?.value?.let { profiles ->
            _state.update { it.copy(profiles = profiles) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoutingScreen(viewModel: RoutingViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val note = when (val current = state.note) {
        null -> null

        RoutingNote.Saved -> stringResource(R.string.feature_tasks_saved)

        is RoutingNote.Failed -> stringResource(R.string.feature_tasks_routing_failed, failureText(current.failure))

        is RoutingNote.DescribeFailed -> stringResource(
            R.string.feature_tasks_describe_failed,
            current.reason.orEmpty(),
        )
    }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    var picking by remember { mutableStateOf<RoutingPick?>(null) }
    var editing by remember { mutableStateOf<KanbanProfile?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_tasks_routing_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_tasks_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val orchestration = state.orchestration
        val failure = state.failure
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                orchestration == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.feature_tasks_unavailable, failure?.let { failureText(it) }.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.feature_tasks_retry)) }
                }

                else -> RoutingList(
                    state = state,
                    orchestration = orchestration,
                    viewModel = viewModel,
                    onPick = { picking = it },
                    onEdit = { editing = it },
                )
            }
        }
    }
    picking?.let { pick ->
        val resolved = when (pick) {
            RoutingPick.Orchestrator -> state.orchestration?.resolvedOrchestrator
            RoutingPick.DefaultAssignee -> state.orchestration?.resolvedDefaultAssignee
        }
        PickDialog(
            title = stringResource(pick.title),
            options = listOf("" to defaultLabel(resolved)) + state.profiles.map { it.name to it.name },
            onPick = {
                picking = null
                when (pick) {
                    RoutingPick.Orchestrator -> viewModel.setOrchestrator(it)
                    RoutingPick.DefaultAssignee -> viewModel.setDefaultAssignee(it)
                }
            },
            onDismiss = { picking = null },
        )
    }
    editing?.let { profile ->
        // Follows the list, so a description Hermes wrote shows up in the open editor.
        val current = state.profiles.firstOrNull { it.name == profile.name } ?: profile
        DescriptionDialog(
            profile = current,
            describing = state.describing == current.name,
            busy = state.busy,
            onSave = {
                editing = null
                viewModel.describe(current.name, it)
            },
            onAuto = { viewModel.describeAuto(current.name) },
            onDismiss = { editing = null },
        )
    }
}

private enum class RoutingPick(val title: Int) {
    Orchestrator(R.string.feature_tasks_orchestrator),
    DefaultAssignee(R.string.feature_tasks_default_assignee),
}

@Composable
private fun defaultLabel(resolved: String?): String = if (resolved.isNullOrBlank()) {
    stringResource(R.string.feature_tasks_default_none)
} else {
    stringResource(R.string.feature_tasks_default_choice, resolved)
}

@Composable
private fun RoutingList(
    state: RoutingUiState,
    orchestration: Orchestration,
    viewModel: RoutingViewModel,
    onPick: (RoutingPick) -> Unit,
    onEdit: (KanbanProfile) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            SwitchRow(
                title = stringResource(R.string.feature_tasks_auto_decompose),
                help = stringResource(R.string.feature_tasks_auto_decompose_help),
                checked = orchestration.autoDecompose,
                enabled = !state.busy,
                onChange = viewModel::setAutoDecompose,
            )
            SwitchRow(
                title = stringResource(R.string.feature_tasks_auto_promote),
                help = stringResource(R.string.feature_tasks_auto_promote_help),
                checked = orchestration.autoPromoteChildren,
                enabled = !state.busy,
                onChange = viewModel::setAutoPromote,
            )
            PickRow(
                title = stringResource(R.string.feature_tasks_orchestrator),
                value = orchestration.orchestratorProfile.ifBlank { defaultLabel(orchestration.resolvedOrchestrator) },
                enabled = !state.busy,
                onClick = { onPick(RoutingPick.Orchestrator) },
            )
            PickRow(
                title = stringResource(R.string.feature_tasks_default_assignee),
                value = orchestration.defaultAssignee.ifBlank { defaultLabel(orchestration.resolvedDefaultAssignee) },
                enabled = !state.busy,
                onClick = { onPick(RoutingPick.DefaultAssignee) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                stringResource(R.string.feature_tasks_profiles),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.feature_tasks_profiles_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.profiles.isEmpty()) {
                Text(
                    stringResource(R.string.feature_tasks_profiles_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(state.profiles, key = { it.name }) { profile -> ProfileRow(profile, onClick = { onEdit(profile) }) }
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun PickRow(title: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ProfileRow(profile: KanbanProfile, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val tags = listOfNotNull(
            stringResource(R.string.feature_tasks_profile_default).takeIf { profile.isDefault },
            stringResource(R.string.feature_tasks_profile_auto).takeIf { profile.autoDescribed },
        )
        Text(
            (listOf(profile.name) + tags).joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            profile.description.trim().ifEmpty { stringResource(R.string.feature_tasks_profile_undescribed) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What [profile] is for, with Hermes writing it on request (which replaces unsaved edits, so it asks first). */
@Composable
private fun DescriptionDialog(
    profile: KanbanProfile,
    describing: Boolean,
    busy: Boolean,
    onSave: (String) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(profile.name, profile.description) { mutableStateOf(profile.description) }
    var replacing by remember { mutableStateOf(false) }
    val edited = text.trim() != profile.description.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_tasks_describe, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.autoDescribed && !edited) {
                    Text(
                        stringResource(R.string.feature_tasks_profile_auto),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !describing,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.feature_tasks_describe_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { if (edited) replacing = true else onAuto() }, enabled = !busy) {
                    if (describing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.feature_tasks_describe_auto))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
            }, enabled = edited && !busy) { Text(stringResource(R.string.feature_tasks_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
    if (replacing) {
        ConfirmDialog(
            title = stringResource(R.string.feature_tasks_describe_replace_title),
            message = stringResource(R.string.feature_tasks_describe_replace_message),
            confirm = stringResource(R.string.feature_tasks_replace),
            onConfirm = {
                replacing = false
                onAuto()
            },
            onDismiss = { replacing = false },
        )
    }
}
