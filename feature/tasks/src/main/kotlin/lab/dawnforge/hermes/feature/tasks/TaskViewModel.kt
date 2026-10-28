package lab.dawnforge.hermes.feature.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.tasks.KanbanProfile
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.tasks.ModelOption
import lab.dawnforge.hermes.core.data.tasks.ModelOverride
import lab.dawnforge.hermes.core.data.tasks.TaskDetail
import lab.dawnforge.hermes.core.data.tasks.TaskPatch
import lab.dawnforge.hermes.core.data.tasks.TaskStatus
import lab.dawnforge.hermes.core.data.tasks.TriageResult
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.tasks.navigation.TaskRoute

/** What the task page tells once. */
internal sealed interface TaskNote {
    data class Failed(val failure: ConnectionFailure) : TaskNote

    /** The comment went up, the step after it didn't: requeue ([unblock] false) or unblock. */
    data class AfterComment(val unblock: Boolean, val failure: ConnectionFailure) : TaskNote

    data class Triaged(val decomposed: Boolean, val result: TriageResult) : TaskNote

    data object Copied : TaskNote
}

/** What to do with a comment besides posting it. */
internal enum class CommentThen { Nothing, Requeue, Unblock }

internal data class TaskUiState(
    val board: String,
    val taskId: String,
    val detail: TaskDetail? = null,
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val profiles: List<KanbanProfile> = emptyList(),
    val defaultAssignee: String? = null,
    /** Edits not saved yet; null when the field is as Hermes has it. */
    val title: String? = null,
    val body: String? = null,
    /** Hermes changed the task under unsaved edits. */
    val changedUnderEdits: Boolean = false,
    val busy: Boolean = false,
    /** The models Hermes offers for tasks; null until asked for. */
    val modelOptions: List<ModelOption>? = null,
    val note: TaskNote? = null,
    /** The task was deleted: the page closes. */
    val gone: Boolean = false,
) {
    val edited: Boolean get() = title != null || body != null
}

/** One task of the board: its fields, its conversation with the worker, its runs and log. */
@HiltViewModel
internal class TaskViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val kanban: KanbanRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<TaskRoute>()
    private val _state = MutableStateFlow(TaskUiState(route.board, route.taskId))
    val state: StateFlow<TaskUiState> = _state.asStateFlow()

    /** While the page shows: reads the task, then again every [POLL_MS] for the worker's progress. */
    suspend fun watch() {
        val id = dashboards.activeDashboardId.first() ?: return
        if (_state.value.profiles.isEmpty()) {
            (kanban.profiles(id) as? NetResult.Success)?.value?.let { profiles ->
                _state.update { it.copy(profiles = profiles) }
            }
            (kanban.orchestration(id) as? NetResult.Success)?.value?.let { orchestration ->
                _state.update { it.copy(defaultAssignee = orchestration.resolvedDefaultAssignee) }
            }
        }
        while (true) {
            load(id)
            delay(POLL_MS)
        }
    }

    private suspend fun load(id: DashboardId) {
        val read = kanban.task(id, route.board, route.taskId)
        _state.update { state ->
            when (read) {
                is NetResult.Failure -> state.copy(
                    loading = false,
                    failure = read.failure,
                    gone = state.gone || (read.failure is ConnectionFailure.NotFound && state.detail != null),
                )

                is NetResult.Success -> {
                    val before = state.detail?.task
                    val now = read.value.task
                    val changed = before != null && (before.title != now.title || before.body != now.body)
                    state.copy(
                        loading = false,
                        failure = null,
                        detail = read.value,
                        changedUnderEdits = state.changedUnderEdits || (changed && state.edited),
                    )
                }
            }
        }
    }

    fun editTitle(text: String) = _state.update {
        it.copy(title = text.takeIf { value -> value != it.detail?.task?.title })
    }

    fun editBody(text: String) = _state.update {
        it.copy(body = text.takeIf { value -> value != it.detail?.task?.body })
    }

    /** Saves the edited fields only. */
    fun saveEdits() {
        val state = _state.value
        if (!state.edited) return
        act { id ->
            kanban.update(
                id,
                route.board,
                route.taskId,
                TaskPatch(title = state.title, body = state.body),
            ).also { result ->
                if (result is NetResult.Success) {
                    _state.update {
                        it.copy(title = null, body = null, changedUnderEdits = false)
                    }
                }
            }.unit()
        }
    }

    fun move(to: TaskStatus) = act { id -> kanban.update(id, route.board, route.taskId, TaskPatch(status = to)).unit() }

    fun setPriority(priority: Int) = act { id ->
        kanban.update(id, route.board, route.taskId, TaskPatch(priority = priority.coerceAtLeast(0))).unit()
    }

    /** Gives the task to [profile], or parks it (null). */
    fun reassign(profile: String?) = act { id -> kanban.reassign(id, route.board, route.taskId, profile) }

    fun reclaim() = act { id -> kanban.reclaim(id, route.board, route.taskId) }

    /** Gives the task its own model, or (null) its profile's again. */
    fun setModel(model: ModelOverride?) = act { id -> kanban.setModel(id, route.board, route.taskId, model) }

    fun loadModels() {
        if (_state.value.modelOptions != null) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val options = (kanban.modelOptions(id) as? NetResult.Success)?.value.orEmpty()
            _state.update { it.copy(modelOptions = options) }
        }
    }

    fun comment(text: String, then: CommentThen) {
        if (text.isBlank()) return
        act { id ->
            val posted = kanban.comment(id, route.board, route.taskId, text)
            if (posted is NetResult.Failure || then == CommentThen.Nothing) return@act posted
            val after = when (then) {
                CommentThen.Requeue -> kanban.reclaim(id, route.board, route.taskId)
                else -> kanban.update(id, route.board, route.taskId, TaskPatch(status = TaskStatus.Ready)).unit()
            }
            if (after is NetResult.Failure) {
                _state.update { it.copy(note = TaskNote.AfterComment(then == CommentThen.Unblock, after.failure)) }
            }
            NetResult.Success(Unit)
        }
    }

    fun triage(decompose: Boolean) = act { id ->
        when (val result = kanban.triage(id, route.board, route.taskId, decompose)) {
            is NetResult.Failure -> result

            is NetResult.Success -> {
                _state.update { it.copy(note = TaskNote.Triaged(decompose, result.value)) }
                NetResult.Success(Unit)
            }
        }
    }

    fun archive() = move(TaskStatus.Archived)

    fun delete() = act { id ->
        kanban.delete(id, route.board, route.taskId).also { result ->
            if (result is NetResult.Success) _state.update { it.copy(gone = true) }
        }
    }

    fun copied() = _state.update { it.copy(note = TaskNote.Copied) }

    fun noteShown() = _state.update { it.copy(note = null) }

    private fun act(block: suspend (DashboardId) -> NetResult<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = block(id)
            _state.update { state ->
                state.copy(
                    busy = false,
                    note =
                        (result as? NetResult.Failure)?.let { TaskNote.Failed(it.failure) } ?: state.note,
                )
            }
            // A change can start or stop a worker: the dispatcher looks now rather than on its next tick.
            if (result is NetResult.Success && !_state.value.gone) {
                kanban.nudge(id, route.board)
                load(id)
            }
        }
    }

    private fun <T> NetResult<T>.unit(): NetResult<Unit> = when (this) {
        is NetResult.Success -> NetResult.Success(Unit)
        is NetResult.Failure -> this
    }

    private companion object {
        const val POLL_MS = 5_000L
    }
}
