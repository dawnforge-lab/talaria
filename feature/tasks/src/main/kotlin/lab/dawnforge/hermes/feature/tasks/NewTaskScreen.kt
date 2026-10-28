package lab.dawnforge.hermes.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.Skill
import lab.dawnforge.hermes.core.data.tasks.KanbanProfile
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.tasks.KanbanTask
import lab.dawnforge.hermes.core.data.tasks.ModelOption
import lab.dawnforge.hermes.core.data.tasks.ModelOverride
import lab.dawnforge.hermes.core.data.tasks.NewTask
import lab.dawnforge.hermes.core.data.tasks.TaskStatus
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.tasks.navigation.NewTaskRoute

/** Who a new task goes to. */
internal sealed interface Assignment {
    /** Hermes' default assignee, whoever that is when the task is picked up. */
    data object Default : Assignment

    data class Profile(val name: String) : Assignment

    /** Nobody: it won't run until someone is assigned. */
    data object Parked : Assignment
}

internal data class NewTaskUiState(
    val title: String = "",
    val body: String = "",
    val priority: Int = 0,
    val assignment: Assignment = Assignment.Default,
    val lane: TaskStatus = TaskStatus.Todo,
    val parent: KanbanTask? = null,
    val profiles: List<KanbanProfile> = emptyList(),
    val defaultAssignee: String? = null,
    val tasks: List<KanbanTask> = emptyList(),
    /** `scratch`, `worktree` or `dir`; null takes the board's default. */
    val workspaceKind: String? = null,
    val workspacePath: String = "",
    val skills: List<String> = emptyList(),
    /** The skills of the profile that would run the task; null until asked for. */
    val skillChoices: List<Skill>? = null,
    val goalMode: Boolean = false,
    /** The most turns in goal mode, as typed; blank is no limit. */
    val goalTurns: String = "",
    val model: ModelOverride? = null,
    /** The models Hermes offers for tasks; null until asked for. */
    val modelOptions: List<ModelOption>? = null,
    val creating: Boolean = false,
    val failure: ConnectionFailure? = null,
    val titleMissing: Boolean = false,
    /** The new task's id, once made: the composer closes and opens it. */
    val created: String? = null,
)

@HiltViewModel
internal class NewTaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val kanban: KanbanRepository,
    private val capabilities: CapabilitiesRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<NewTaskRoute>()
    private val _state = MutableStateFlow(
        NewTaskUiState(
            lane =
                TaskStatus.of(route.lane).takeIf { it.canCreate } ?: TaskStatus.Todo,
        ),
    )
    val state: StateFlow<NewTaskUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            (kanban.profiles(id) as? NetResult.Success)?.value?.let { profiles ->
                _state.update { it.copy(profiles = profiles) }
            }
            (kanban.orchestration(id) as? NetResult.Success)?.value?.let { orchestration ->
                _state.update { it.copy(defaultAssignee = orchestration.resolvedDefaultAssignee) }
            }
            (kanban.board(id, route.board) as? NetResult.Success)?.value?.let { board ->
                _state.update { it.copy(tasks = board.tasks) }
            }
        }
    }

    fun update(change: (NewTaskUiState) -> NewTaskUiState) = _state.update {
        change(it).copy(failure = null, titleMissing = false)
    }

    /** Reads the skills of the profile that would run the task. */
    fun loadSkills() {
        val state = _state.value
        val profile = (state.assignment as? Assignment.Profile)?.name ?: state.defaultAssignee ?: "default"
        _state.update { it.copy(skillChoices = null) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val skills = (capabilities.skills(id, profile) as? NetResult.Success)?.value.orEmpty()
            _state.update { it.copy(skillChoices = skills.filter { skill -> skill.enabled }) }
        }
    }

    fun loadModels() {
        if (_state.value.modelOptions != null) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val options = (kanban.modelOptions(id) as? NetResult.Success)?.value.orEmpty()
            _state.update { it.copy(modelOptions = options) }
        }
    }

    fun create() {
        val state = _state.value
        if (state.creating) return
        if (state.title.isBlank()) {
            _state.update { it.copy(titleMissing = true) }
            return
        }
        _state.update { it.copy(creating = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val assignee = when (val assignment = state.assignment) {
                Assignment.Default -> state.defaultAssignee ?: "default"
                is Assignment.Profile -> assignment.name
                Assignment.Parked -> null
            }
            val task = NewTask(
                title = state.title,
                body = state.body,
                assignee = assignee,
                priority = state.priority,
                lane = state.lane,
                parent = state.parent?.id,
                workspaceKind = state.workspaceKind,
                workspacePath = state.workspacePath,
                skills = state.skills,
                goalMode = state.goalMode,
                goalMaxTurns = state.goalTurns.trim().toIntOrNull()?.takeIf { it > 0 },
                model = state.model,
            )
            when (val result = kanban.create(id, route.board, task)) {
                is NetResult.Success -> {
                    kanban.nudge(id, route.board)
                    _state.update { it.copy(creating = false, created = result.value.id) }
                }

                is NetResult.Failure -> _state.update { it.copy(creating = false, failure = result.failure) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewTaskScreen(viewModel: NewTaskViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.created) { state.created?.let(onCreated) }
    var pickingParent by remember { mutableStateOf(false) }
    var pickingSkills by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_tasks_compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, stringResource(R.string.feature_tasks_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::create, enabled = !state.creating) {
                        Text(stringResource(R.string.feature_tasks_create))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.failure?.let {
                Text(
                    stringResource(R.string.feature_tasks_create_failed, failureText(it)),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.title,
                onValueChange = { text -> viewModel.update { it.copy(title = text) } },
                label = { Text(stringResource(R.string.feature_tasks_field_title)) },
                isError = state.titleMissing,
                supportingText = if (state.titleMissing) {
                    (
                        {
                            Text(stringResource(R.string.feature_tasks_title_required))
                        }
                        )
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = { text -> viewModel.update { it.copy(body = text) } },
                label = { Text(stringResource(R.string.feature_tasks_field_body)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Label(stringResource(R.string.feature_tasks_priority_label))
            Chips(
                listOf(
                    0 to R.string.feature_tasks_priority_normal,
                    1 to R.string.feature_tasks_priority_high,
                    2 to R.string.feature_tasks_priority_urgent,
                    3 to R.string.feature_tasks_priority_critical,
                ).map { (value, label) -> value to stringResource(label) },
                selected = state.priority,
            ) { value -> viewModel.update { it.copy(priority = value) } }
            Label(stringResource(R.string.feature_tasks_assignee))
            val defaultName = state.defaultAssignee ?: "default"
            Chips(
                listOf<Pair<Assignment, String>>(
                    Assignment.Default to stringResource(R.string.feature_tasks_assign_default, defaultName),
                ) +
                    state.profiles.filter { it.name != state.defaultAssignee }.map {
                        Assignment.Profile(it.name) to
                            it.name
                    } +
                    (Assignment.Parked to stringResource(R.string.feature_tasks_assign_parked)),
                selected = state.assignment,
            ) { value -> viewModel.update { it.copy(assignment = value) } }
            Label(stringResource(R.string.feature_tasks_lands_in))
            Chips(
                TaskStatus.entries.filter { it.canCreate }.map { it to stringResource(laneName(it)) },
                selected = state.lane,
            ) { value -> viewModel.update { it.copy(lane = value) } }
            if (state.lane == TaskStatus.Todo && state.parent == null) {
                Text(stringResource(R.string.feature_tasks_todo_note), style = MaterialTheme.typography.bodySmall)
            }
            Label(stringResource(R.string.feature_tasks_depends_on))
            ListItem(
                headlineContent = { Text(state.parent?.title ?: stringResource(R.string.feature_tasks_no_parent)) },
                supportingContent = state.parent?.let { { Text(it.shortId) } },
                modifier = Modifier.clickable { pickingParent = true },
            )
            Execution(
                state = state,
                viewModel = viewModel,
                onSkills = {
                    viewModel.loadSkills()
                    pickingSkills = true
                },
                onModel = {
                    viewModel.loadModels()
                    pickingModel = true
                },
            )
            Text(stringResource(R.string.feature_tasks_shared_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (pickingSkills) {
        SkillsDialog(
            choices = state.skillChoices,
            chosen = state.skills,
            onSave = { skills ->
                pickingSkills = false
                viewModel.update { it.copy(skills = skills) }
            },
            onDismiss = { pickingSkills = false },
        )
    }
    if (pickingModel) {
        TaskModelDialog(
            options = state.modelOptions,
            current = state.model,
            onSave = { model ->
                pickingModel = false
                viewModel.update { it.copy(model = model) }
            },
            onDismiss = { pickingModel = false },
        )
    }
    if (pickingParent) {
        AlertDialog(
            onDismissRequest = { pickingParent = false },
            title = { Text(stringResource(R.string.feature_tasks_depends_on)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.feature_tasks_no_parent)) },
                            modifier = Modifier.clickable {
                                pickingParent = false
                                viewModel.update { it.copy(parent = null) }
                            },
                        )
                    }
                    items(state.tasks.filter { it.status != TaskStatus.Archived }, key = { it.id }) { task ->
                        ListItem(
                            headlineContent = { Text(task.title) },
                            supportingContent = { Text(stringResource(laneName(task.status)) + " · " + task.shortId) },
                            modifier = Modifier.clickable {
                                pickingParent = false
                                viewModel.update { it.copy(parent = task) }
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingParent = false }) { Text(stringResource(R.string.feature_tasks_cancel)) }
            },
        )
    }
}

/** Where the worker works, what it loads, whether it keeps going to a goal, and its model. */
@Composable
private fun Execution(state: NewTaskUiState, viewModel: NewTaskViewModel, onSkills: () -> Unit, onModel: () -> Unit) {
    Label(stringResource(R.string.feature_tasks_workspace_kind))
    Chips(
        listOf(
            null to R.string.feature_tasks_workspace_board,
            "scratch" to R.string.feature_tasks_workspace_scratch,
            "worktree" to R.string.feature_tasks_workspace_worktree,
            "dir" to R.string.feature_tasks_workspace_dir,
        ).map { (kind, label) -> kind to stringResource(label) },
        selected = state.workspaceKind,
    ) { kind -> viewModel.update { it.copy(workspaceKind = kind) } }
    if (state.workspaceKind == "worktree" || state.workspaceKind == "dir") {
        OutlinedTextField(
            value = state.workspacePath,
            onValueChange = { text -> viewModel.update { it.copy(workspacePath = text) } },
            singleLine = true,
            label = { Text(stringResource(R.string.feature_tasks_workspace_path)) },
            placeholder = { Text(stringResource(R.string.feature_tasks_workspace_path_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ListItem(
        overlineContent = { Text(stringResource(R.string.feature_tasks_skills)) },
        headlineContent = {
            Text(state.skills.joinToString(", ").ifEmpty { stringResource(R.string.feature_tasks_skills_none) })
        },
        modifier = Modifier.clickable(onClick = onSkills),
    )
    ListItem(
        overlineContent = { Text(stringResource(R.string.feature_tasks_model)) },
        headlineContent = { Text(modelLabel(state.model) ?: stringResource(R.string.feature_tasks_model_inherit)) },
        modifier = Modifier.clickable(onClick = onModel),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_tasks_goal_mode)) },
        supportingContent = { Text(stringResource(R.string.feature_tasks_goal_mode_help)) },
        trailingContent = { Switch(checked = state.goalMode, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = state.goalMode, role = Role.Switch) { on ->
            viewModel.update { it.copy(goalMode = on) }
        },
    )
    if (state.goalMode) {
        OutlinedTextField(
            value = state.goalTurns,
            onValueChange = { text -> viewModel.update { it.copy(goalTurns = text.filter(Char::isDigit)) } },
            singleLine = true,
            label = { Text(stringResource(R.string.feature_tasks_goal_turns)) },
            placeholder = { Text(stringResource(R.string.feature_tasks_goal_unlimited)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}
