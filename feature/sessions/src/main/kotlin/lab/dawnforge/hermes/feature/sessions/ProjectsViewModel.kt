package lab.dawnforge.hermes.feature.sessions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
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
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.data.session.ProjectsRepository
import lab.dawnforge.hermes.core.data.workspace.WorkspaceRepository
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.sessions.navigation.ProjectsRoute

/** A new project being written: its name, folders (the first is where chats start) and idea. */
internal data class NewProject(
    val name: String = "",
    val folders: List<String> = emptyList(),
    val idea: String = "",
    val saving: Boolean = false,
    val failure: ConnectionFailure? = null,
) {
    val ready: Boolean get() = name.isNotBlank() && folders.isNotEmpty() && !saving
}

internal data class ProjectsUiState(
    /** Null until read; empty when there are none. */
    val projects: List<Project>? = null,
    /** This Hermes has no projects. */
    val unsupported: Boolean = false,
    val failure: ConnectionFailure? = null,
    val refreshing: Boolean = false,
    val query: String = "",
    val creating: NewProject? = null,
    val picking: FolderPick? = null,
    val renaming: Project? = null,
    val deleting: Project? = null,
    /** A rename or delete is on its way. */
    val busy: Boolean = false,
    val note: ConnectionFailure? = null,
) {
    /** The projects whose name, or a recent chat's title, matches the search. */
    val shown: List<Project>
        get() {
            val all = projects.orEmpty()
            val q = query.trim()
            if (q.isEmpty()) return all
            return all.filter { project ->
                project.title.contains(q, ignoreCase = true) ||
                    project.previewSessions.any { it.displayTitle.contains(q, ignoreCase = true) }
            }
        }
}

/** The profile's projects, as Hermes groups chats by their folders. */
@HiltViewModel
internal class ProjectsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val projects: ProjectsRepository,
    private val workspace: WorkspaceRepository,
    private val gateway: GatewayManager,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ProjectsRoute>()
    private val _state = MutableStateFlow(ProjectsUiState())
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    /** The list is read once the live connection is up, and again each time it comes back. */
    suspend fun watch() {
        gateway.status.collect { status ->
            if (status.state is GatewayState.Connected) load()
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch { load() }
    }

    fun search(query: String) = _state.update { it.copy(query = query) }

    fun noteShown() = _state.update { it.copy(note = null) }

    // ---- New project ---------------------------------------------------------------------------

    fun startNew() = _state.update { it.copy(creating = NewProject()) }

    fun editNew(change: (NewProject) -> NewProject) = _state.update { state ->
        state.copy(creating = state.creating?.let { change(it).copy(failure = null) })
    }

    fun cancelNew() = _state.update { it.copy(creating = null, picking = null) }

    fun removeFolder(path: String) = editNew { it.copy(folders = it.folders - path) }

    /** Opens the folder picker at the profile's working folder on the host. */
    fun pickFolder() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(picking = FolderPick(path = "")) }
            when (val start = projects.defaultFolder(id, route.profile)) {
                is NetResult.Success -> openFolder(start.value)

                is NetResult.Failure -> _state.update {
                    it.copy(picking = FolderPick("", loading = false, failure = start.failure))
                }
            }
        }
    }

    fun openFolder(path: String) {
        _state.update { it.copy(picking = FolderPick(path)) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = workspace.list(id, route.profile, path)
            _state.update { state ->
                val pick = state.picking?.takeIf { it.path == path } ?: return@update state
                state.copy(
                    picking = when (result) {
                        is NetResult.Success -> pick.copy(
                            folders = result.value.filter {
                                it.isDirectory
                            },
                            loading = false,
                        )

                        is NetResult.Failure -> pick.copy(loading = false, failure = result.failure)
                    },
                )
            }
        }
    }

    fun chooseFolder(path: String) {
        _state.update { state ->
            val creating = state.creating ?: return@update state.copy(picking = null)
            val folders = if (path in creating.folders) creating.folders else creating.folders + path
            state.copy(picking = null, creating = creating.copy(folders = folders))
        }
    }

    fun closePicker() = _state.update { it.copy(picking = null) }

    fun create() {
        val draft = _state.value.creating ?: return
        if (!draft.ready) return
        _state.update { it.copy(creating = draft.copy(saving = true)) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = projects.create(id, route.profile, draft.name, draft.folders, draft.idea)) {
                is NetResult.Success -> {
                    _state.update { it.copy(creating = null) }
                    load()
                }

                is NetResult.Failure -> _state.update {
                    it.copy(creating = it.creating?.copy(saving = false, failure = result.failure))
                }
            }
        }
    }

    // ---- Rename and delete ---------------------------------------------------------------------

    fun askRename(project: Project) = _state.update { it.copy(renaming = project) }

    fun askDelete(project: Project) = _state.update { it.copy(deleting = project) }

    fun dismiss() = _state.update { it.copy(renaming = null, deleting = null) }

    fun rename(name: String) {
        val project = _state.value.renaming ?: return
        if (name.isBlank()) return
        change { projects.rename(route.profile, project.id, name) }
    }

    fun delete() {
        val project = _state.value.deleting ?: return
        change { projects.delete(route.profile, project.id) }
    }

    private fun change(block: suspend () -> NetResult<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, renaming = null, deleting = null) }
        viewModelScope.launch {
            val result = block()
            _state.update { it.copy(busy = false, note = (result as? NetResult.Failure)?.failure) }
            load()
        }
    }

    private suspend fun load() {
        val result = projects.tree(route.profile)
        _state.update { state ->
            when (result) {
                is NetResult.Success -> state.copy(
                    projects = result.value.orEmpty(),
                    unsupported = result.value == null,
                    failure = null,
                    refreshing = false,
                )

                // A failed refresh keeps the list shown.
                is NetResult.Failure -> state.copy(failure = result.failure, refreshing = false)
            }
        }
    }
}
