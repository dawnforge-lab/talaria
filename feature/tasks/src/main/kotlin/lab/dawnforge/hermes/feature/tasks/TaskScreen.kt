package lab.dawnforge.hermes.feature.tasks

import android.text.format.DateUtils
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.tasks.KanbanTask
import lab.dawnforge.hermes.core.data.tasks.LinkedTask
import lab.dawnforge.hermes.core.data.tasks.ModelOverride
import lab.dawnforge.hermes.core.data.tasks.TaskDetail
import lab.dawnforge.hermes.core.data.tasks.TaskEvent
import lab.dawnforge.hermes.core.data.tasks.TaskStatus
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskScreen(
    viewModel: TaskViewModel,
    onBack: () -> Unit,
    onOpenTask: (board: String, taskId: String) -> Unit,
    onOpenLog: (board: String, taskId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    val snackbar = remember { SnackbarHostState() }
    val note = state.note?.let { noteText(it) }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    var deleting by remember { mutableStateOf(false) }
    var reassigning by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_tasks_task)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_tasks_back))
                    }
                },
                actions = {
                    val task = state.detail?.task
                    if (task != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = {
                                menu = true
                            }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_tasks_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = {
                                    Text(stringResource(R.string.feature_tasks_copy_id))
                                }, onClick = {
                                    menu = false
                                    copy(context, task.id)
                                    viewModel.copied()
                                })
                                DropdownMenuItem(text = {
                                    Text(stringResource(R.string.feature_tasks_copy_title))
                                }, onClick = {
                                    menu = false
                                    copy(context, task.title)
                                    viewModel.copied()
                                })
                                DropdownMenuItem(text = {
                                    Text(stringResource(R.string.feature_tasks_reassign))
                                }, onClick = {
                                    menu = false
                                    reassigning = true
                                })
                                if (task.status != TaskStatus.Archived) {
                                    DropdownMenuItem(text = {
                                        Text(stringResource(R.string.feature_tasks_archive))
                                    }, onClick = {
                                        menu = false
                                        viewModel.archive()
                                    })
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.feature_tasks_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menu = false
                                        deleting = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val detail = state.detail
        val failure = state.failure
        when {
            detail != null -> TaskBody(
                state = state,
                detail = detail,
                viewModel = viewModel,
                onReassign = { reassigning = true },
                onOpenTask = { onOpenTask(state.board, it) },
                onOpenLog = { onOpenLog(state.board, state.taskId) },
                modifier = Modifier.padding(padding),
            )

            failure != null -> Text(
                stringResource(R.string.feature_tasks_loading_failed, failureText(failure)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(padding).padding(24.dp),
            )

            else -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.feature_tasks_delete_title)) },
            text = { Text(stringResource(R.string.feature_tasks_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.feature_tasks_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.feature_tasks_cancel)) }
            },
        )
    }
    if (reassigning) {
        val current = state.detail?.task?.assignee
        AlertDialog(
            onDismissRequest = { reassigning = false },
            title = { Text(stringResource(R.string.feature_tasks_reassign)) },
            text = {
                Column {
                    state.profiles.forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                reassigning = false
                                viewModel.reassign(profile.name)
                            }.padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = profile.name == current, onClick = null)
                            Text(profile.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (current != null) {
                        TextButton(onClick = {
                            reassigning = false
                            viewModel.reassign(null)
                        }) { Text(stringResource(R.string.feature_tasks_unassign)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { reassigning = false }) { Text(stringResource(R.string.feature_tasks_cancel)) }
            },
        )
    }
}

@Composable
private fun noteText(note: TaskNote): String = when (note) {
    is TaskNote.Failed -> stringResource(R.string.feature_tasks_change_failed, failureText(note.failure))

    is TaskNote.AfterComment -> stringResource(
        if (note.unblock) R.string.feature_tasks_unblock_failed else R.string.feature_tasks_requeue_failed,
        failureText(note.failure),
    )

    is TaskNote.Triaged -> when {
        !note.result.ok -> stringResource(R.string.feature_tasks_triage_refused, note.result.reason.orEmpty())

        note.decomposed -> pluralStringResource(
            R.plurals.feature_tasks_decomposed,
            note.result.childIds.size,
            note.result.childIds.size,
        )

        else -> stringResource(R.string.feature_tasks_specified)
    }

    TaskNote.Copied -> stringResource(R.string.feature_tasks_copied)
}

@Composable
private fun TaskBody(
    state: TaskUiState,
    detail: TaskDetail,
    viewModel: TaskViewModel,
    onReassign: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenLog: () -> Unit,
    modifier: Modifier,
) {
    val task = detail.task
    val context = LocalContext.current
    var pickingModel by remember { mutableStateOf(false) }
    if (pickingModel) {
        TaskModelDialog(
            options = state.modelOptions,
            current = task.model,
            onSave = { model ->
                pickingModel = false
                viewModel.setModel(model)
            },
            onDismiss = { pickingModel = false },
        )
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // The worker's question first, when there is one: it's what the user is here for.
        detail.question?.let { question ->
            Callout(stringResource(R.string.feature_tasks_waiting_on_you), question)
            Comments(state, detail, viewModel)
        }
        Editor(state, task, viewModel)
        LaneRow(task, enabled = !state.busy, onMove = viewModel::move)
        Section(stringResource(R.string.feature_tasks_assignment))
        Assignment(state, task, onReassign, viewModel::setPriority, onModel = {
            viewModel.loadModels()
            pickingModel = true
        })
        if (task.status == TaskStatus.Ready && task.assignee == null && state.defaultAssignee == null) {
            Callout(
                stringResource(R.string.feature_tasks_wont_run),
                stringResource(R.string.feature_tasks_wont_run_detail),
            ) {
                TextButton(onClick = onReassign) { Text(stringResource(R.string.feature_tasks_assign_now)) }
            }
        }
        if (task.status == TaskStatus.Triage) Triage(state.busy, viewModel::triage)
        if (task.diagnostics.isNotEmpty()) {
            Section(stringResource(R.string.feature_tasks_diagnostics))
            task.diagnostics.forEach { diagnostic ->
                Callout(
                    diagnostic.title,
                    diagnostic.detail.orEmpty(),
                    error =
                        diagnostic.severity in setOf("error", "critical"),
                ) {
                    diagnostic.actions.forEach { action ->
                        when (action.kind) {
                            "reclaim" -> TextButton(onClick = viewModel::reclaim) {
                                Text(action.label.ifBlank { stringResource(R.string.feature_tasks_reclaim) })
                            }

                            "cli_hint" -> action.command?.let { command ->
                                TextButton(onClick = {
                                    copy(context, command)
                                    viewModel.copied()
                                }) { Text(stringResource(R.string.feature_tasks_copy_command)) }
                            }
                        }
                    }
                }
            }
        }
        if (detail.parents.isNotEmpty() || detail.children.isNotEmpty()) {
            Section(stringResource(R.string.feature_tasks_dependencies))
            if (detail.parents.isNotEmpty()) {
                Links(stringResource(R.string.feature_tasks_blocked_by), detail.parents, onOpenTask)
            }
            if (detail.children.isNotEmpty()) {
                Links(stringResource(R.string.feature_tasks_blocks), detail.children, onOpenTask)
            }
        }
        if (detail.question == null) Comments(state, detail, viewModel)
        Activity(detail.events)
        Runs(detail)
        ListItem(
            headlineContent = { Text(stringResource(R.string.feature_tasks_worker_log)) },
            modifier = Modifier.clickable(onClick = onOpenLog),
        )
    }
}

@Composable
private fun Editor(state: TaskUiState, task: KanbanTask, viewModel: TaskViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            task.shortId,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.title ?: task.title,
            onValueChange = viewModel::editTitle,
            label = { Text(stringResource(R.string.feature_tasks_field_title)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.body ?: task.body,
            onValueChange = viewModel::editBody,
            label = { Text(stringResource(R.string.feature_tasks_field_body)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.changedUnderEdits) {
            Text(
                stringResource(R.string.feature_tasks_changed_on_server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (state.edited) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val titled = (state.title ?: task.title).isNotBlank()
                Button(onClick = viewModel::saveEdits, enabled = !state.busy && titled) {
                    Text(stringResource(R.string.feature_tasks_save_changes))
                }
                Text(stringResource(R.string.feature_tasks_unsaved), style = MaterialTheme.typography.labelMedium)
            }
        }
        task.result?.let {
            Text(stringResource(R.string.feature_tasks_result), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** The task's lane, and the lanes it can be moved to by hand. */
@Composable
private fun LaneRow(task: KanbanTask, enabled: Boolean, onMove: (TaskStatus) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.feature_tasks_lane), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val lanes = (TaskStatus.MOVABLE + task.status).distinct().sortedBy { it.ordinal }
            lanes.forEach { lane ->
                FilterChip(
                    selected = lane == task.status,
                    onClick = { if (lane != task.status) onMove(lane) },
                    enabled = enabled && (lane.manual || lane == task.status),
                    label = { Text(stringResource(laneName(lane))) },
                )
            }
        }
    }
}

@Composable
private fun Assignment(
    state: TaskUiState,
    task: KanbanTask,
    onReassign: () -> Unit,
    onPriority: (Int) -> Unit,
    onModel: () -> Unit,
) {
    val byDefault = state.defaultAssignee?.takeIf { task.status == TaskStatus.Ready }
    val assignee = when {
        task.assignee != null -> task.assignee!!
        byDefault != null -> stringResource(R.string.feature_tasks_assignee_default, byDefault)
        else -> stringResource(R.string.feature_tasks_unassigned)
    }
    ListItem(
        overlineContent = { Text(stringResource(R.string.feature_tasks_assignee)) },
        headlineContent = { Text(assignee) },
        trailingContent = {
            TextButton(onClick = onReassign, enabled = !state.busy) {
                Text(stringResource(R.string.feature_tasks_reassign))
            }
        },
    )
    ListItem(
        overlineContent = { Text(stringResource(R.string.feature_tasks_priority_label)) },
        headlineContent = { Text(task.priority.toString()) },
        supportingContent = { Text(stringResource(R.string.feature_tasks_priority_help)) },
        trailingContent = {
            Row {
                TextButton(onClick = {
                    onPriority(task.priority - 1)
                }, enabled = !state.busy && task.priority > 0) { Text("−") }
                TextButton(onClick = { onPriority(task.priority + 1) }, enabled = !state.busy) { Text("+") }
            }
        },
    )
    task.workspaceKind?.let { kind ->
        Detail(
            stringResource(R.string.feature_tasks_workspace),
            listOfNotNull(kind, task.workspacePath).joinToString(": "),
        )
    }
    ListItem(
        overlineContent = { Text(stringResource(R.string.feature_tasks_model)) },
        headlineContent = { Text(modelLabel(task.model) ?: stringResource(R.string.feature_tasks_model_inherit)) },
        trailingContent = {
            TextButton(onClick = onModel, enabled = !state.busy) {
                Text(stringResource(R.string.feature_tasks_change_model))
            }
        },
    )
    if (task.status == TaskStatus.Running) {
        task.workerPid?.let {
            Detail(
                stringResource(R.string.feature_tasks_worker),
                stringResource(R.string.feature_tasks_worker_pid, it.toInt()),
            )
        }
    }
    task.createdBy?.let { Detail(stringResource(R.string.feature_tasks_created_by), it) }
    if (task.consecutiveFailures > 0) {
        Detail(stringResource(R.string.feature_tasks_failures), task.consecutiveFailures.toString())
    }
    task.lastFailure?.let { Detail(stringResource(R.string.feature_tasks_last_failure), it, error = true) }
}

@Composable
private fun Triage(busy: Boolean, onTriage: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Section(stringResource(R.string.feature_tasks_triage))
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.feature_tasks_specify_help), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                onTriage(false)
            }, enabled = !busy) { Text(stringResource(R.string.feature_tasks_specify)) }
            OutlinedButton(onClick = {
                confirm = true
            }, enabled = !busy) { Text(stringResource(R.string.feature_tasks_decompose)) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.feature_tasks_decompose_title)) },
            text = { Text(stringResource(R.string.feature_tasks_decompose_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onTriage(true)
                }) { Text(stringResource(R.string.feature_tasks_decompose)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.feature_tasks_cancel)) }
            },
        )
    }
}

@Composable
private fun Links(title: String, tasks: List<LinkedTask>, onOpen: (String) -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    tasks.forEach { linked ->
        ListItem(
            headlineContent = { Text(linked.title.ifBlank { linked.id }) },
            supportingContent = {
                Text(stringResource(laneName(linked.status)) + " · " + linked.id.removePrefix("t_").take(6))
            },
            modifier = Modifier.clickable { onOpen(linked.id) },
        )
    }
}

@Composable
private fun Comments(state: TaskUiState, detail: TaskDetail, viewModel: TaskViewModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    val task = detail.task
    Section(stringResource(R.string.feature_tasks_comments_title))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail.comments.isEmpty()) {
            Text(stringResource(R.string.feature_tasks_no_comments), style = MaterialTheme.typography.bodySmall)
        }
        detail.comments.forEach { comment ->
            Column {
                Text(
                    comment.author + " · " + DateUtils.getRelativeTimeSpanString(comment.createdAt * 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer { Text(comment.body) }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text(stringResource(R.string.feature_tasks_comment_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        val ready = draft.isNotBlank() && !state.busy
        val send = { then: CommentThen ->
            viewModel.comment(draft, then)
            draft = ""
        }
        if (task.status == TaskStatus.Running) {
            Text(stringResource(R.string.feature_tasks_running_note), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                task.status == TaskStatus.Blocked && detail.question != null ->
                    Button(onClick = {
                        send(CommentThen.Unblock)
                    }, enabled = ready) { Text(stringResource(R.string.feature_tasks_reply_unblock)) }

                task.status == TaskStatus.Running ->
                    OutlinedButton(onClick = {
                        send(CommentThen.Requeue)
                    }, enabled = ready) { Text(stringResource(R.string.feature_tasks_note_requeue)) }
            }
            OutlinedButton(onClick = { send(CommentThen.Nothing) }, enabled = ready) {
                val label = if (task.status == TaskStatus.Running) {
                    R.string.feature_tasks_send
                } else {
                    R.string.feature_tasks_add_comment
                }
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun Activity(events: List<TaskEvent>) {
    if (events.isEmpty()) return
    val shown = events.sortedByDescending { it.id }.take(MAX_EVENTS)
    Section(stringResource(R.string.feature_tasks_activity) + " (${events.size})")
    shown.forEach { event ->
        Text(
            eventLine(event) + " · " + DateUtils.getRelativeTimeSpanString(event.createdAt * 1000),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
    if (events.size > shown.size) {
        Text(
            stringResource(R.string.feature_tasks_earlier_hidden, events.size - shown.size),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Runs(detail: TaskDetail) {
    if (detail.runs.isEmpty()) return
    Section(stringResource(R.string.feature_tasks_runs) + " (${detail.runs.size})")
    detail.runs.forEach { run ->
        val duration = run.startedAt?.let { start -> (run.endedAt ?: (System.currentTimeMillis() / 1000)) - start }
        ListItem(
            headlineContent = {
                Text(
                    listOfNotNull(
                        run.outcome?.replace('_', ' ')?.replaceFirstChar(Char::titlecase)
                            ?: stringResource(R.string.feature_tasks_run_running),
                        run.stepKey,
                        duration?.let { DateUtils.formatElapsedTime(it.coerceAtLeast(0)) },
                    ).joinToString(" · "),
                    color = if (run.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(
                            run.profile,
                            run.workerPid?.let {
                                "pid $it"
                            },
                            run.startedAt?.let { DateUtils.getRelativeTimeSpanString(it * 1000).toString() },
                        )
                            .joinToString(" · "),
                    )
                    run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (run.error ==
                        null
                    ) {
                        run.summary?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            },
        )
    }
}

/** The task's own model, if it has one. */
private val KanbanTask.model: ModelOverride?
    get() = modelOverride?.let { ModelOverride(it, providerOverride, reasoningEffort) }

/** An event as a line: its kind, then what it carries (`status: ready`). */
internal fun eventLine(event: TaskEvent): String {
    val kind = event.kind.replace('_', ' ').replaceFirstChar(Char::titlecase)
    val details = event.payload.entries.take(MAX_PAYLOAD).mapNotNull { (key, value) ->
        val text = (value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
        "$key: ${text.take(MAX_VALUE)}"
    }
    return if (details.isEmpty()) kind else "$kind (${details.joinToString(", ")})"
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Detail(title: String, value: String, error: Boolean = false) {
    ListItem(
        overlineContent = { Text(title) },
        headlineContent = {
            Text(value, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
    )
}

@Composable
private fun Callout(title: String, body: String, error: Boolean = false, actions: @Composable () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (body.isNotBlank()) SelectionContainer { Text(body, style = MaterialTheme.typography.bodyMedium) }
            Row { actions() }
        }
    }
}

private const val MAX_EVENTS = 60
private const val MAX_PAYLOAD = 3
private const val MAX_VALUE = 80
