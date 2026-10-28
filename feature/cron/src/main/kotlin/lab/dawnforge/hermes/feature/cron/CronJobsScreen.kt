package lab.dawnforge.hermes.feature.cron

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.cron.CronTrigger
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.ui.failureText

/** What the list can do to a job, beyond opening it. */
internal class JobActions(
    val open: (CronJob) -> Unit,
    val edit: (CronJob) -> Unit,
    val pin: (CronJob, Boolean) -> Unit,
    val pause: (CronJob, Boolean) -> Unit,
    val runNow: (CronJob) -> Unit,
    val delete: (CronJob) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronJobsScreen(
    viewModel: CronJobsViewModel,
    onBack: () -> Unit,
    onOpenJob: (profile: String, jobId: String) -> Unit,
    onNewJob: (profile: String) -> Unit,
    onEditJob: (profile: String, jobId: String) -> Unit,
    onBlueprints: (profile: String) -> Unit,
    onShowRuns: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Each time the list shows again, so a job added, edited or deleted on another screen appears.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refresh() }
    }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    val profile = state.profile
    val actions = JobActions(
        open = { job -> profile?.let { onOpenJob(it, job.id) } },
        edit = { job -> profile?.let { onEditJob(it, job.id) } },
        pin = viewModel::setPinned,
        pause = viewModel::setPaused,
        runNow = viewModel::runNow,
        delete = viewModel::askDelete,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.feature_cron_title))
                        profile?.let {
                            Text(
                                stringResource(R.string.feature_cron_profile, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.feature_cron_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val failure = state.failure
            when {
                state.loading -> Centered { CircularProgressIndicator() }

                failure != null && state.isEmpty -> Centered {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            failureText(failure),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_cron_retry)) }
                    }
                }

                // A list, even when empty, so pulling down still refreshes.
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        CreateButtons(
                            enabled = profile != null,
                            onNewJob = { profile?.let(onNewJob) },
                            onBlueprints = { profile?.let(onBlueprints) },
                        )
                    }
                    if (state.isEmpty) {
                        item {
                            Text(
                                stringResource(R.string.feature_cron_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    if (state.pinned.isNotEmpty()) {
                        item { SectionHeader(R.string.feature_cron_pinned) }
                        jobRows(state.pinned, state, pinned = true, actions)
                        // The rest are headed only when there are pins above them.
                        if (state.jobs.isNotEmpty()) item { SectionHeader(R.string.feature_cron_jobs) }
                    }
                    jobRows(state.jobs, state, pinned = false, actions)
                    item {
                        RunsFooter(onShowRuns = {
                            viewModel.showRunsInChats()
                            onShowRuns()
                        })
                    }
                }
            }
        }
    }

    state.confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.feature_cron_delete_title, job.name)) },
            text = { Text(stringResource(R.string.feature_cron_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) { Text(stringResource(R.string.feature_cron_delete)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.feature_cron_cancel)) }
            },
        )
    }
}

private fun LazyListScope.jobRows(jobs: List<CronJob>, state: CronJobsUiState, pinned: Boolean, actions: JobActions) {
    items(jobs, key = { it.id }) { job ->
        JobRow(
            job,
            pinned = pinned,
            muted = state.muted?.contains(job.id) == true,
            zoneLabel = state.zoneLabel,
            working = job.id in state.working,
            actions = actions,
        )
    }
}

@Composable
private fun CreateButtons(enabled: Boolean, onNewJob: () -> Unit, onBlueprints: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onNewJob, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.feature_cron_new_job), Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onBlueprints, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.feature_cron_from_blueprint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JobRow(
    job: CronJob,
    pinned: Boolean,
    muted: Boolean,
    zoneLabel: String?,
    working: Boolean,
    actions: JobActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(onClick = { actions.open(job) }, onLongClick = { menuOpen = true }),
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (pinned) {
                        Icon(
                            painterResource(R.drawable.feature_cron_ic_pin),
                            contentDescription = stringResource(R.string.feature_cron_pinned_mark),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        job.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false),
                    )
                    if (muted) {
                        Icon(
                            painterResource(R.drawable.feature_cron_ic_muted),
                            contentDescription = stringResource(R.string.feature_cron_muted_mark),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            supportingContent = {
                Column {
                    Text(scheduleText(job, zoneLabel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    nextRunText(job)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (job.state == CronState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            },
            trailingContent = {
                // A finished one-off can't be scheduled again.
                if (job.state != CronState.Completed) {
                    val description = stringResource(R.string.feature_cron_toggle, job.name)
                    Box(contentAlignment = Alignment.Center) {
                        Switch(
                            checked = job.state != CronState.Paused,
                            onCheckedChange = { actions.pause(job, !it) },
                            enabled = !working,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                        if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            },
        )
        JobMenu(job, pinned, menuOpen, onDismiss = { menuOpen = false }, actions)
    }
    HorizontalDivider()
}

@Composable
private fun JobMenu(job: CronJob, pinned: Boolean, open: Boolean, onDismiss: () -> Unit, actions: JobActions) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        MenuAction(if (pinned) R.string.feature_cron_unpin else R.string.feature_cron_pin, onDismiss) {
            actions.pin(job, !pinned)
        }
        MenuAction(R.string.feature_cron_run_now, onDismiss) { actions.runNow(job) }
        if (job.state != CronState.Completed) {
            val paused = job.state == CronState.Paused
            MenuAction(if (paused) R.string.feature_cron_resume else R.string.feature_cron_pause, onDismiss) {
                actions.pause(job, !paused)
            }
        }
        MenuAction(R.string.feature_cron_details, onDismiss) { actions.open(job) }
        MenuAction(R.string.feature_cron_edit, onDismiss) { actions.edit(job) }
        MenuAction(R.string.feature_cron_delete, onDismiss) { actions.delete(job) }
    }
}

@Composable
private fun MenuAction(label: Int, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

@Composable
private fun RunsFooter(onShowRuns: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.feature_cron_runs_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onShowRuns) { Text(stringResource(R.string.feature_cron_show_runs)) }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun messageText(message: CronJobMessage): String = when (message) {
    is CronJobMessage.Failed -> failureText(message.failure)

    is CronJobMessage.NotifyFailed ->
        stringResource(R.string.feature_cron_notify_failed, failureText(message.failure))

    is CronJobMessage.Triggered -> stringResource(
        when (message.outcome) {
            CronTrigger.Finished -> R.string.feature_cron_finished
            CronTrigger.Running -> R.string.feature_cron_still_running
            CronTrigger.AlreadyRunning -> R.string.feature_cron_already_running
        },
    )
}

/** "22 Oct, 09:00", in the phone's own format. */
@Composable
internal fun dateTime(epochMs: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochMs,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)
