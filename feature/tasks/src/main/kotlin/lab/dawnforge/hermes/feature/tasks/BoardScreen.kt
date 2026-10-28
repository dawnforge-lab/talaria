package lab.dawnforge.hermes.feature.tasks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.tasks.BulkChange
import lab.dawnforge.hermes.core.data.tasks.KanbanJson
import lab.dawnforge.hermes.core.data.tasks.KanbanTask
import lab.dawnforge.hermes.core.data.tasks.TaskStatus
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoardScreen(
    viewModel: BoardViewModel,
    onBack: () -> Unit,
    onOpenTask: (board: String, taskId: String) -> Unit,
    onNewTask: (board: String, lane: TaskStatus) -> Unit,
    onRouting: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val snackbar = remember { SnackbarHostState() }
    val note = when (val current = state.note) {
        null -> null
        BoardNote.Nudged -> stringResource(R.string.feature_tasks_nudged)
        is BoardNote.Failed -> stringResource(R.string.feature_tasks_change_failed, failureText(current.failure))
        is BoardNote.BoardFailed -> stringResource(R.string.feature_tasks_board_failed, failureText(current.failure))
        is BoardNote.Bulk -> bulkNote(current)
    }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    var deleting by remember { mutableStateOf<KanbanTask?>(null) }
    var dialog by remember { mutableStateOf<BoardDialog?>(null) }
    BackHandler(enabled = state.selecting) { viewModel.select(false) }
    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.feature_tasks_selected,
                                state.selected.size,
                                state.selected.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.select(false) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.feature_tasks_cancel))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.feature_tasks_title))
                            val board = listOfNotNull(
                                state.boardName,
                                stringResource(R.string.feature_tasks_live).takeIf { state.live },
                            )
                            if (board.isNotEmpty()) {
                                Text(
                                    board.joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_tasks_back))
                        }
                    },
                    actions = { BoardMenu(state, viewModel, onDialog = { dialog = it }, onRouting = onRouting) },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                BottomAppBar { SelectionBar(state.selected.size, state.busy) { dialog = it } }
            }
        },
        floatingActionButton = {
            val board = state.board
            if (board != null && !state.selecting) {
                ExtendedFloatingActionButton(
                    onClick = { onNewTask(board, if (state.lane.canCreate) state.lane else TaskStatus.Todo) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.feature_tasks_new_task)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchRow(state, viewModel)
            LaneChips(state, viewModel::show)
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val failure = state.failure
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    failure != null && state.view == null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    ) {
                        Text(
                            stringResource(R.string.feature_tasks_unavailable, failureText(failure)),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_tasks_retry)) }
                    }

                    else -> LazyColumn(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        failure?.let {
                            item {
                                Text(
                                    stringResource(R.string.feature_tasks_refresh_failed, failureText(it)),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (!state.lane.manual) {
                            item {
                                Text(
                                    stringResource(R.string.feature_tasks_lane_locked),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val tasks = state.tasks(state.lane)
                        if (tasks.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.feature_tasks_lane_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                        items(tasks, key = { it.id }) { task ->
                            TaskCard(
                                task = task,
                                now = state.view?.now ?: (System.currentTimeMillis() / 1000),
                                hasDefault = state.defaultAssignee != null,
                                selected = if (state.selecting) task.id in state.selected else null,
                                onOpen = {
                                    if (state.selecting) {
                                        viewModel.toggle(task)
                                    } else {
                                        state.board?.let { onOpenTask(it, task.id) }
                                    }
                                },
                                onMove = { viewModel.move(task, it) },
                                onArchive = { viewModel.archive(task) },
                                onDelete = { deleting = task },
                            )
                        }
                        item { Box(Modifier.size(72.dp)) }
                    }
                }
            }
        }
    }
    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.feature_tasks_delete_title)) },
            text = { Text(stringResource(R.string.feature_tasks_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(task)
                }) { Text(stringResource(R.string.feature_tasks_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.feature_tasks_cancel)) }
            },
        )
    }
    dialog?.let { shown -> BoardDialogs(shown, state, viewModel) { dialog = null } }
}

/** The dialog [shown] asks for; [onDone] closes it. */
@Composable
private fun BoardDialogs(shown: BoardDialog, state: BoardUiState, viewModel: BoardViewModel, onDone: () -> Unit) {
    val count = state.selected.size
    when (shown) {
        BoardDialog.Move -> PickDialog(
            title = stringResource(R.string.feature_tasks_bulk_move),
            options = TaskStatus.MOVABLE.map { it to stringResource(laneName(it)) },
            onPick = {
                onDone()
                viewModel.bulk(BulkChange(status = it))
            },
            onDismiss = onDone,
        )

        BoardDialog.Assign -> PickDialog(
            title = stringResource(R.string.feature_tasks_bulk_assign),
            options = state.profiles.map { it to it } + ("" to stringResource(R.string.feature_tasks_bulk_unassign)),
            onPick = {
                onDone()
                viewModel.bulk(BulkChange(assignee = it))
            },
            onDismiss = onDone,
        )

        BoardDialog.Priority -> PriorityDialog(
            onSet = {
                onDone()
                viewModel.bulk(BulkChange(priority = it))
            },
            onDismiss = onDone,
        )

        BoardDialog.Archive -> ConfirmDialog(
            title = pluralStringResource(R.plurals.feature_tasks_bulk_archive_title, count, count),
            message = stringResource(R.string.feature_tasks_bulk_archive_message),
            confirm = stringResource(R.string.feature_tasks_archive),
            onConfirm = {
                onDone()
                viewModel.bulk(BulkChange(archive = true))
            },
            onDismiss = onDone,
        )

        BoardDialog.Delete -> ConfirmDialog(
            title = pluralStringResource(R.plurals.feature_tasks_bulk_delete_title, count, count),
            message = stringResource(R.string.feature_tasks_bulk_delete_message),
            confirm = stringResource(R.string.feature_tasks_delete),
            onConfirm = {
                onDone()
                viewModel.deleteSelected()
            },
            onDismiss = onDone,
        )

        BoardDialog.NewBoard -> BoardDialog(
            title = stringResource(R.string.feature_tasks_new_board),
            name = "",
            description = "",
            slugged = true,
            onSave = { name, description ->
                onDone()
                viewModel.createBoard(name, description)
            },
            onDismiss = onDone,
        )

        BoardDialog.EditBoard -> {
            val board = state.boards.firstOrNull { it.slug == state.board }
            BoardDialog(
                title = stringResource(R.string.feature_tasks_board_settings),
                name = board?.name ?: state.board.orEmpty(),
                description = board?.description.orEmpty(),
                slugged = false,
                onSave = { name, description ->
                    onDone()
                    viewModel.updateBoard(name, description)
                },
                onDismiss = onDone,
            )
        }

        BoardDialog.ArchiveBoard -> ConfirmDialog(
            title = stringResource(R.string.feature_tasks_archive_board_title, state.boardName.orEmpty()),
            message = stringResource(R.string.feature_tasks_archive_board_message),
            confirm = stringResource(R.string.feature_tasks_archive),
            onConfirm = {
                onDone()
                viewModel.archiveBoard()
            },
            onDismiss = onDone,
        )
    }
}

/** How a change to several tasks went: the count, and the first task that failed with why. */
@Composable
private fun bulkNote(note: BoardNote.Bulk): String {
    val first = note.failed.firstOrNull() ?: return stringResource(R.string.feature_tasks_bulk_done, note.updated)
    val reason =
        first.error ?: first.failure?.let { failureText(it) } ?: stringResource(R.string.feature_tasks_bulk_refused)
    return stringResource(R.string.feature_tasks_bulk_failed, note.updated, note.failed.size) + "\n" +
        stringResource(R.string.feature_tasks_bulk_failed_detail, first.id, reason)
}

@Composable
private fun BoardMenu(
    state: BoardUiState,
    viewModel: BoardViewModel,
    onDialog: (BoardDialog) -> Unit,
    onRouting: () -> Unit,
) {
    var boards by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { boards = true }) { Text(stringResource(R.string.feature_tasks_choose_board)) }
        DropdownMenu(expanded = boards, onDismissRequest = { boards = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_board_current, state.serverCurrent)) },
                onClick = {
                    boards = false
                    viewModel.choose(null)
                },
            )
            state.boards.forEach { board ->
                DropdownMenuItem(
                    text = { Text(board.name) },
                    leadingIcon = { RadioButton(selected = board.slug == state.board, onClick = null) },
                    onClick = {
                        boards = false
                        viewModel.choose(board.slug)
                    },
                )
            }
        }
    }
    Box {
        IconButton(onClick = {
            more = true
        }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_tasks_more)) }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_refresh)) },
                onClick = {
                    more = false
                    viewModel.refresh()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_nudge)) },
                onClick = {
                    more = false
                    viewModel.nudge()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_select)) },
                enabled = state.view != null,
                onClick = {
                    more = false
                    viewModel.select(true)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_new_board)) },
                onClick = {
                    more = false
                    onDialog(BoardDialog.NewBoard)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_board_settings)) },
                enabled = state.board != null,
                onClick = {
                    more = false
                    onDialog(BoardDialog.EditBoard)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_archive_board)) },
                // Hermes keeps its default board.
                enabled = state.board != null && state.board != KanbanJson.DEFAULT_BOARD,
                onClick = {
                    more = false
                    onDialog(BoardDialog.ArchiveBoard)
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_routing)) },
                onClick = {
                    more = false
                    onRouting()
                },
            )
        }
    }
}

@Composable
private fun SearchRow(state: BoardUiState, viewModel: BoardViewModel) {
    var filters by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::search,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (state.query.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        viewModel.search("")
                    }) { Icon(Icons.Filled.Clear, stringResource(R.string.feature_tasks_clear_search)) }
                }
            } else {
                null
            },
            placeholder = { Text(stringResource(R.string.feature_tasks_search)) },
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { filters = true }) {
                Text(
                    stringResource(R.string.feature_tasks_filters) +
                        if (state.assignee != null || state.showArchived) " •" else "",
                )
            }
            DropdownMenu(expanded = filters, onDismissRequest = { filters = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_tasks_all_profiles)) },
                    leadingIcon = { RadioButton(selected = state.assignee == null, onClick = null) },
                    onClick = { viewModel.filter(null) },
                )
                state.view?.assignees.orEmpty().forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = { RadioButton(selected = state.assignee == name, onClick = null) },
                        onClick = { viewModel.filter(name) },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_tasks_show_archived)) },
                    trailingIcon = { Switch(checked = state.showArchived, onCheckedChange = null) },
                    onClick = { viewModel.showArchived(!state.showArchived) },
                )
            }
        }
    }
}

@Composable
private fun LaneChips(state: BoardUiState, onShow: (TaskStatus) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(
            rememberScrollState(),
        ).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.lanes.forEach { lane ->
            FilterChip(
                selected = lane == state.lane,
                onClick = { onShow(lane) },
                label = { Text("${stringResource(laneName(lane))} ${state.tasks(lane).size}") },
                leadingIcon = if (!lane.manual) {
                    { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: KanbanTask,
    now: Long,
    hasDefault: Boolean,
    /** Whether the task is chosen, while tasks are being chosen; null otherwise. */
    selected: Boolean?,
    onOpen: () -> Unit,
    onMove: (TaskStatus) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = if (selected == null) 16.dp else 4.dp, top = 12.dp, bottom = 12.dp)) {
            if (selected !=
                null
            ) {
                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                (task.latestSummary ?: task.body.ifBlank { null })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                CardFooter(task, now, hasDefault)
            }
            if (selected != null) Box(Modifier.size(16.dp)) else TaskMenu(task, onMove, onArchive, onDelete)
        }
    }
}

@Composable
private fun TaskMenu(task: KanbanTask, onMove: (TaskStatus) -> Unit, onArchive: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            menu = true
        }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_tasks_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            TaskStatus.MOVABLE.filter { it != task.status }.forEach { lane ->
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.feature_tasks_move_to, stringResource(laneName(lane))))
                    },
                    onClick = {
                        menu = false
                        onMove(lane)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_copy_id)) },
                onClick = {
                    menu = false
                    copy(context, task.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_tasks_copy_title)) },
                onClick = {
                    menu = false
                    copy(context, task.title)
                },
            )
            if (task.status != TaskStatus.Archived) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_tasks_archive)) },
                    onClick = {
                        menu = false
                        onArchive()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(stringResource(R.string.feature_tasks_delete), color = MaterialTheme.colorScheme.error)
                },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun CardFooter(task: KanbanTask, now: Long, hasDefault: Boolean) {
    val parts = buildList {
        when {
            task.silent(now) -> add(stringResource(R.string.feature_tasks_no_heartbeat))

            task.status == TaskStatus.Running && task.startedAt != null ->
                add(DateUtils.formatElapsedTime((now - task.startedAt!!).coerceAtLeast(0)))
        }
        if (task.status == TaskStatus.Ready && task.assignee == null &&
            !hasDefault
        ) {
            add(stringResource(R.string.feature_tasks_wont_run))
        }
        add(task.assignee ?: stringResource(R.string.feature_tasks_unassigned))
        if (task.progressTotal != null && task.progressTotal!! > 0) {
            add(stringResource(R.string.feature_tasks_progress, task.progressDone ?: 0, task.progressTotal!!))
        }
        if (task.commentCount >
            0
        ) {
            add(pluralStringResource(R.plurals.feature_tasks_comments, task.commentCount, task.commentCount))
        }
        if (task.priority > 0) add(stringResource(R.string.feature_tasks_priority, task.priority))
        if (task.warningCount >
            0
        ) {
            add(pluralStringResource(R.plurals.feature_tasks_warnings, task.warningCount, task.warningCount))
        }
        add(task.shortId)
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = if (task.warningCount > 0 ||
            task.silent(now)
        ) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

internal fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(text, text))
}

@StringRes
internal fun laneName(lane: TaskStatus): Int = when (lane) {
    TaskStatus.Triage -> R.string.feature_tasks_lane_triage
    TaskStatus.Todo -> R.string.feature_tasks_lane_todo
    TaskStatus.Scheduled -> R.string.feature_tasks_lane_scheduled
    TaskStatus.Ready -> R.string.feature_tasks_lane_ready
    TaskStatus.Running -> R.string.feature_tasks_lane_running
    TaskStatus.Blocked -> R.string.feature_tasks_lane_blocked
    TaskStatus.Review -> R.string.feature_tasks_lane_review
    TaskStatus.Done -> R.string.feature_tasks_lane_done
    TaskStatus.Archived -> R.string.feature_tasks_lane_archived
    TaskStatus.Other -> R.string.feature_tasks_lane_other
}
