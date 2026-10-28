package lab.dawnforge.hermes.feature.sessions

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.data.session.ProjectDetail
import lab.dawnforge.hermes.core.data.session.ProjectsRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.sessions.navigation.ProjectRoute

internal data class ProjectUiState(
    val project: Project? = null,
    val detail: ProjectDetail? = null,
    /** Every project, for moving a chat to another one. */
    val projects: List<Project> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: ConnectionFailure? = null,
    val collapsed: Set<String> = emptySet(),
    val startingChat: Boolean = false,
    /** A chat being moved to another project. */
    val moving: SessionSummary? = null,
    val busy: Boolean = false,
    val note: ConnectionFailure? = null,
)

/** One project: its chats by folder, a new chat in it, and moving its chats elsewhere. */
@HiltViewModel
internal class ProjectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val projects: ProjectsRepository,
    private val sessions: SessionRepository,
    private val gateway: GatewayManager,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ProjectRoute>()
    private val _state = MutableStateFlow(ProjectUiState())
    val state: StateFlow<ProjectUiState> = _state.asStateFlow()
    private val openChannel = Channel<ChatTarget>(Channel.BUFFERED)
    val opens = openChannel.receiveAsFlow()

    suspend fun watch() {
        gateway.status.collect { status ->
            if (status.state is GatewayState.Connected && _state.value.detail == null) load()
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { load() }
    }

    fun toggle(laneId: String) = _state.update {
        it.copy(collapsed = if (laneId in it.collapsed) it.collapsed - laneId else it.collapsed + laneId)
    }

    fun open(row: SessionSummary) {
        viewModelScope.launch { openChannel.send(ChatTarget(route.profile, row.id, null, row.displayTitle)) }
    }

    /** A new chat that starts in the project's folder. */
    fun newChat() {
        val path = _state.value.project?.primaryPath ?: return
        if (_state.value.startingChat) return
        _state.update { it.copy(startingChat = true) }
        viewModelScope.launch {
            when (val chat = sessions.startChat(route.profile, cwd = path)) {
                is NetResult.Success -> openChannel.send(
                    ChatTarget(chat.value.profile, null, chat.value.runtimeId, null),
                )

                is NetResult.Failure -> _state.update { it.copy(note = chat.failure) }
            }
            _state.update { it.copy(startingChat = false) }
        }
    }

    fun askMove(row: SessionSummary) = _state.update { it.copy(moving = row) }

    fun cancelMove() = _state.update { it.copy(moving = null) }

    fun move(to: Project) {
        val row = _state.value.moving ?: return
        val path = to.primaryPath ?: return
        _state.update { it.copy(moving = null, busy = true) }
        viewModelScope.launch {
            val result = projects.move(route.profile, row.id, path)
            _state.update { it.copy(busy = false, note = (result as? NetResult.Failure)?.failure) }
            load()
        }
    }

    fun noteShown() = _state.update { it.copy(note = null) }

    private suspend fun load() {
        val tree = projects.tree(route.profile)
        (tree as? NetResult.Success)?.value?.let { all ->
            _state.update {
                it.copy(projects = all, project = all.firstOrNull { project -> project.id == route.projectId })
            }
        }
        val result = projects.sessions(route.profile, route.projectId)
        _state.update { state ->
            when (result) {
                is NetResult.Success -> state.copy(
                    detail = result.value,
                    loading = false,
                    refreshing = false,
                    failure = null,
                )

                is NetResult.Failure -> state.copy(loading = false, refreshing = false, failure = result.failure)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectScreen(
    viewModel: ProjectViewModel,
    title: String,
    onBack: () -> Unit,
    onOpenChat: (ChatTarget) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val openChat by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.opens.collect { openChat(it) } }
    val snackbar = remember { SnackbarHostState() }
    val note = state.note?.let { stringResource(R.string.feature_sessions_project_change_failed, failureText(it)) }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    val project = state.project
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.title ?: title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_sessions_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (project?.canTakeChats == true) {
                ExtendedFloatingActionButton(
                    onClick = { if (!state.startingChat) viewModel.newChat() },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.feature_sessions_new_chat_in_project)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val detail = state.detail
            val failure = state.failure
            when {
                state.loading -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.feature_sessions_project_loading),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                detail == null && failure != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.feature_sessions_project_failed, failureText(failure)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_sessions_retry)) }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { Header(project) }
                    val lanes = detail?.lanes.orEmpty()
                    if (lanes.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.feature_sessions_project_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    lanes.forEach { lane ->
                        val open = lane.id !in state.collapsed
                        item(key = "lane:${lane.id}") {
                            LaneHeader(
                                title = lane.title ?: stringResource(R.string.feature_sessions_project_recent),
                                path = lane.path?.let { relativeLanePath(it, project?.primaryPath) },
                                count = lane.sessions.size,
                                open = open,
                                onToggle = { viewModel.toggle(lane.id) },
                            )
                        }
                        if (open) {
                            items(lane.sessions, key = { "chat:${lane.id}:${it.id}" }) { row ->
                                ProjectChatRow(
                                    row = row,
                                    canMove =
                                        state.projects.any { it.canTakeChats && it.id != project?.id } &&
                                            row.source != "cron",
                                    busy = state.busy,
                                    onOpen = { viewModel.open(row) },
                                    onMove = { viewModel.askMove(row) },
                                )
                            }
                        }
                    }
                    item { Box(Modifier.size(88.dp)) }
                }
            }
        }
    }
    state.moving?.let { row ->
        MoveToProjectDialog(
            chat = row.displayTitle,
            targets = state.projects.filter { it.canTakeChats && it.id != project?.id },
            onMove = viewModel::move,
            onDismiss = viewModel::cancelMove,
        )
    }
}

@Composable
private fun Header(project: Project?) {
    project ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        ProjectTile(project, 56)
        Column {
            Text(project.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            project.primaryPath?.let { path ->
                SelectionContainer {
                    Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                pluralStringResource(
                    R.plurals.feature_sessions_project_chats,
                    project.sessionCount,
                    project.sessionCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A lane's folder, under the project's own where it can be (`.worktrees/voice`). */
internal fun relativeLanePath(path: String, root: String?): String {
    val base = root?.trimEnd('/', '\\') ?: return path
    if (!path.startsWith(base, ignoreCase = true) || path.length <= base.length) return path
    return path.substring(base.length).trimStart('/', '\\').ifEmpty { path }
}

@Composable
private fun LaneHeader(title: String, path: String?, count: Int, open: Boolean, onToggle: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        leadingContent = {
            Icon(
                if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        headlineContent = {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = path?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1) }
        },
        trailingContent = { Text(count.toString(), style = MaterialTheme.typography.labelLarge) },
    )
}

@Composable
private fun ProjectChatRow(
    row: SessionSummary,
    canMove: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onMove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).padding(start = 24.dp),
        headlineContent = { Text(row.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = row.preview?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.lastActiveAtMs?.let { Text(relativeTime(it), style = MaterialTheme.typography.bodySmall) }
                if (canMove) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) {
                            Icon(
                                Icons.Filled.MoreVert,
                                stringResource(R.string.feature_sessions_row_actions, row.displayTitle),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_sessions_action_move)) },
                                onClick = {
                                    menu = false
                                    onMove()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** Moves a chat to another project's folder; no files move. */
@Composable
internal fun MoveToProjectDialog(
    chat: String,
    targets: List<Project>,
    onMove: (Project) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_sessions_move_title, chat)) },
        text = {
            LazyColumn {
                items(targets, key = { it.id }) { project ->
                    ListItem(
                        modifier = Modifier.clickable { onMove(project) },
                        leadingContent = { ProjectTile(project, 32) },
                        headlineContent = { Text(project.title) },
                        supportingContent = project.primaryPath?.let {
                            {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_cancel)) } },
    )
}
