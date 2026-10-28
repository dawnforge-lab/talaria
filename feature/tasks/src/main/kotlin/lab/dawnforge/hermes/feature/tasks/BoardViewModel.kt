package lab.dawnforge.hermes.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.tasks.BoardView
import lab.dawnforge.hermes.core.data.tasks.BulkChange
import lab.dawnforge.hermes.core.data.tasks.BulkResult
import lab.dawnforge.hermes.core.data.tasks.KanbanBoard
import lab.dawnforge.hermes.core.data.tasks.KanbanEvents
import lab.dawnforge.hermes.core.data.tasks.KanbanJson
import lab.dawnforge.hermes.core.data.tasks.KanbanProfile
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.tasks.KanbanTask
import lab.dawnforge.hermes.core.data.tasks.TaskPatch
import lab.dawnforge.hermes.core.data.tasks.TaskStatus
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

internal data class BoardUiState(
    val boards: List<KanbanBoard> = emptyList(),
    /** The board shown; null until the list is read. */
    val board: String? = null,
    /** Hermes' own current board. */
    val serverCurrent: String = KanbanJson.DEFAULT_BOARD,
    val view: BoardView? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: ConnectionFailure? = null,
    val lane: TaskStatus = TaskStatus.Ready,
    val query: String = "",
    val assignee: String? = null,
    val showArchived: Boolean = false,
    /** Whether the live event stream is up. */
    val live: Boolean = false,
    val busy: Boolean = false,
    val note: BoardNote? = null,
    /** Who runs a ready task nobody was given; null when nobody does. */
    val defaultAssignee: String? = null,
    val profiles: List<String> = emptyList(),
    /** Choosing tasks for a change to all of them. */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    val boardName: String? get() = boards.firstOrNull { it.slug == board }?.name ?: board

    /** The lanes to show: the board's own, the archived lane only when asked for. */
    val lanes: List<TaskStatus> get() = view?.lanes?.map { it.first }?.filter {
        it != TaskStatus.Archived ||
            showArchived
    }.orEmpty()

    fun tasks(lane: TaskStatus): List<KanbanTask> = view?.lanes?.firstOrNull { it.first == lane }?.second.orEmpty()
        .filter { task -> assignee == null || task.assignee == assignee }
        .filter { task ->
            val q = query.trim()
            q.isEmpty() || listOfNotNull(task.title, task.body, task.id, task.latestSummary, task.assignee)
                .any { it.contains(q, ignoreCase = true) }
        }
}

/** What the board tells once. */
internal sealed interface BoardNote {
    data object Nudged : BoardNote

    data class Failed(val failure: ConnectionFailure) : BoardNote

    /** A board couldn't be made, changed or archived. */
    data class BoardFailed(val failure: ConnectionFailure) : BoardNote

    /** A change to several tasks: how many took it, and those that didn't. */
    data class Bulk(val updated: Int, val failed: List<BulkResult>) : BoardNote
}

/** A board's short name made from its name: lowercase, words joined by hyphens, as iOS makes it. */
internal fun boardSlug(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX_SLUG)

private const val MAX_SLUG = 64

/**
 * The task board of the active dashboard, shared by every profile. It follows the board's live
 * events, reloading when one comes in; without them it reads the board every [POLL_MS].
 */
@HiltViewModel
internal class BoardViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val kanban: KanbanRepository,
    private val events: KanbanEvents,
    private val settings: AppSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private var watching: Job? = null
    private var reloadSoon: Job? = null

    /** Where the event stream runs: the board's own while it shows, so it stops with it. */
    private var liveScope: CoroutineScope? = null

    /** While the board shows: reads it, follows its events, and reads it again now and then. */
    suspend fun watch() = coroutineScope {
        val id = dashboards.activeDashboardId.first() ?: return@coroutineScope
        liveScope = this
        try {
            (kanban.orchestration(id) as? NetResult.Success)?.value?.let { orchestration ->
                _state.update { it.copy(defaultAssignee = orchestration.resolvedDefaultAssignee) }
            }
            (kanban.profiles(id) as? NetResult.Success)?.value?.let { profiles ->
                _state.update { it.copy(profiles = profiles.map(KanbanProfile::name)) }
            }
            load(id, settings.board(id).first())
            while (true) {
                delay(if (_state.value.live) LIVE_POLL_MS else POLL_MS)
                load(id, _state.value.board)
            }
        } finally {
            liveScope = null
            watching = null
            _state.update { it.copy(live = false) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(refreshing = true) }
            load(id, _state.value.board)
        }
    }

    fun choose(board: String?) {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            settings.setBoard(id, board)
            _state.update { it.copy(view = null, loading = true, live = false) }
            watching?.cancel()
            load(id, board)
        }
    }

    fun show(lane: TaskStatus) = _state.update { it.copy(lane = lane) }

    fun noteShown() = _state.update { it.copy(note = null) }

    fun select(on: Boolean) = _state.update { it.copy(selecting = on, selected = emptySet()) }

    fun toggle(task: KanbanTask) = _state.update {
        val selected = if (task.id in it.selected) it.selected - task.id else it.selected + task.id
        it.copy(selected = selected)
    }

    /** Changes every selected task; each succeeds or fails on its own, and the board says how many did. */
    fun bulk(change: BulkChange) = changeSelected { id, board, ids -> kanban.bulk(id, board, ids, change) }

    fun deleteSelected() = changeSelected { id, board, ids -> NetResult.Success(kanban.deleteAll(id, board, ids)) }

    private fun changeSelected(block: suspend (DashboardId, String, List<String>) -> NetResult<List<BulkResult>>) {
        val ids = _state.value.selected.toList()
        if (ids.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val board = _state.value.board ?: return@launch
            val note = when (val result = block(id, board, ids)) {
                is NetResult.Failure -> BoardNote.Failed(result.failure)
                is NetResult.Success -> BoardNote.Bulk(result.value.count { it.ok }, result.value.filter { !it.ok })
            }
            _state.update { it.copy(busy = false, note = note, selecting = false, selected = emptySet()) }
            kanban.nudge(id, board)
            load(id, board)
        }
    }

    /** Creates a board named [name] and shows it. */
    fun createBoard(name: String, description: String) = boardChange { id ->
        val slug = boardSlug(name)
        kanban.createBoard(id, slug, name, description).also {
            if (it is NetResult.Success) settings.setBoard(id, slug)
        }
    }

    fun updateBoard(name: String, description: String) = boardChange { id ->
        kanban.updateBoard(id, _state.value.board ?: return@boardChange NetResult.Success(Unit), name, description)
    }

    /** Archives the board shown and goes back to Hermes' current one. */
    fun archiveBoard() = boardChange { id ->
        val board = _state.value.board ?: return@boardChange NetResult.Success(Unit)
        kanban.archiveBoard(id, board).also { if (it is NetResult.Success) settings.setBoard(id, null) }
    }

    private fun boardChange(block: suspend (DashboardId) -> NetResult<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = block(id)
            _state.update {
                it.copy(
                    busy = false,
                    note = (result as? NetResult.Failure)?.let { failed ->
                        BoardNote.BoardFailed(failed.failure)
                    },
                )
            }
            if (result is NetResult.Success) {
                watching?.cancel()
                _state.update { it.copy(view = null, loading = true, live = false) }
                load(id, settings.board(id).first())
            }
        }
    }

    fun search(query: String) = _state.update { it.copy(query = query) }

    fun filter(assignee: String?) = _state.update { it.copy(assignee = assignee) }

    fun showArchived(show: Boolean) {
        _state.update { it.copy(showArchived = show) }
        refresh()
    }

    fun move(task: KanbanTask, to: TaskStatus) = change { id, board ->
        kanban.update(id, board, task.id, TaskPatch(status = to)).map()
    }

    fun archive(task: KanbanTask) = move(task, TaskStatus.Archived)

    fun delete(task: KanbanTask) = change { id, board -> kanban.delete(id, board, task.id) }

    fun nudge() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val board = _state.value.board ?: return@launch
            when (val result = kanban.nudge(id, board)) {
                is NetResult.Success -> _state.update { it.copy(note = BoardNote.Nudged) }
                is NetResult.Failure -> _state.update { it.copy(note = BoardNote.Failed(result.failure)) }
            }
        }
    }

    private fun change(block: suspend (DashboardId, String) -> NetResult<Unit>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val board = _state.value.board ?: return@launch
            val result = block(id, board)
            _state.update { it.copy(busy = false) }
            if (result is NetResult.Failure) _state.update { it.copy(note = BoardNote.Failed(result.failure)) }
            // A change is a nudge too: a task moved to Ready starts sooner.
            if (result is NetResult.Success) kanban.nudge(id, board)
            load(id, board)
        }
    }

    private suspend fun load(id: DashboardId, chosen: String?) {
        val boards = kanban.boards(id)
        val list = (boards as? NetResult.Success)?.value
        val board = chosen?.takeIf { slug -> list == null || list.boards.any { it.slug == slug && !it.archived } }
            ?: list?.current ?: KanbanJson.DEFAULT_BOARD
        val read = kanban.board(id, board, _state.value.showArchived)
        _state.update { state ->
            val view = (read as? NetResult.Success)?.value
            state.copy(
                boards = list?.boards?.filter { !it.archived } ?: state.boards,
                serverCurrent = list?.current ?: state.serverCurrent,
                board = board,
                view = view ?: state.view,
                loading = false,
                refreshing = false,
                failure = (read as? NetResult.Failure)?.failure ?: (boards as? NetResult.Failure)?.failure,
                lane = if (state.view == null && view != null) firstLane(view) else state.lane,
            )
        }
        val since = (read as? NetResult.Success)?.value?.latestEventId
        if (since != null && watching?.isActive != true) follow(id, board, since)
    }

    /** The first lane with something in it, Ready if none. */
    private fun firstLane(view: BoardView): TaskStatus =
        view.lanes.firstOrNull { (lane, tasks) -> lane.manual && tasks.isNotEmpty() }?.first ?: TaskStatus.Ready

    /** Follows the board's events, coming back after a dropped link with growing waits. */
    private fun follow(id: DashboardId, board: String, since: Long) {
        val scope = liveScope ?: return
        watching = scope.launch {
            var cursor = since
            var backoff = 0
            while (_state.value.board == board) {
                try {
                    events.changes(id, board, cursor).collect { change ->
                        _state.update { it.copy(live = true) }
                        backoff = 0
                        change.cursor?.let { if (it > cursor) cursor = it }
                        if (change.cursor != null || change.taskIds.isNotEmpty()) reloadSoon(id, board)
                    }
                } catch (_: Exception) {
                    // The link dropped or was refused; polling covers the gap.
                }
                _state.update { it.copy(live = false) }
                load(id, board)
                delay(BACKOFF_MS[backoff.coerceAtMost(BACKOFF_MS.lastIndex)])
                backoff++
            }
        }
    }

    /** Events come in bursts: the board is read once they settle. */
    private fun reloadSoon(id: DashboardId, board: String) {
        reloadSoon?.cancel()
        reloadSoon = viewModelScope.launch {
            delay(COALESCE_MS)
            if (!_state.value.busy) load(id, board)
        }
    }

    private fun <T> NetResult<T>.map(): NetResult<Unit> = when (this) {
        is NetResult.Success -> NetResult.Success(Unit)
        is NetResult.Failure -> this
    }

    private companion object {
        const val POLL_MS = 8_000L
        const val LIVE_POLL_MS = 60_000L
        const val COALESCE_MS = 300L
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
    }
}
