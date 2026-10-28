package lab.dawnforge.hermes.feature.cron

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronRun
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CronJobScreen(
    viewModel: CronJobViewModel,
    onBack: () -> Unit,
    onEdit: (profile: String, jobId: String) -> Unit,
    onOpenRun: (profile: String, sessionId: String, title: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.refresh() }
    }
    LaunchedEffect(viewModel) { viewModel.deleted.collect { back() } }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }
    val job = state.job
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(job?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.feature_cron_back),
                        )
                    }
                },
                actions = {
                    if (job != null) {
                        IconButton(onClick = { onEdit(viewModel.profile, job.id) }, enabled = !state.working) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.feature_cron_edit))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.feature_cron_more),
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.feature_cron_delete)) },
                                    enabled = !state.working,
                                    onClick = {
                                        menuOpen = false
                                        viewModel.askDelete()
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
        val failure = state.failure
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            job == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    failure?.let { failureText(it) } ?: stringResource(R.string.feature_cron_gone),
                    color = MaterialTheme.colorScheme.error,
                )
                if (failure != null) {
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_cron_retry)) }
                }
            }

            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item { JobDetails(job, state, viewModel) }
                item {
                    Text(
                        stringResource(R.string.feature_cron_runs),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                if (state.runs.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.feature_cron_no_runs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(state.runs, key = { it.id }) { run ->
                    RunRow(run, onOpen = { onOpenRun(run.profile ?: viewModel.profile, run.id, run.title) })
                }
            }
        }
    }

    if (state.confirmDelete && job != null) {
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

@Composable
private fun JobDetails(job: CronJob, state: CronJobUiState, viewModel: CronJobViewModel) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (job.isScript) R.string.feature_cron_script else R.string.feature_cron_prompt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (job.prompt.isNotEmpty()) Text(job.prompt, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = viewModel::runNow, enabled = !state.working) {
                if (state.triggering) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.feature_cron_running), Modifier.padding(start = 8.dp))
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.feature_cron_run_now), Modifier.padding(start = 8.dp))
                }
            }
            if (job.state != CronState.Completed) {
                val paused = job.state == CronState.Paused
                OutlinedButton(onClick = { viewModel.setPaused(!paused) }, enabled = !state.working) {
                    Text(stringResource(if (paused) R.string.feature_cron_resume else R.string.feature_cron_pause))
                }
            }
        }
        Detail(R.string.feature_cron_schedule, scheduleText(job, state.zoneLabel))
        Detail(R.string.feature_cron_status, stateText(job.state))
        if (job.state == CronState.Scheduled) {
            Detail(R.string.feature_cron_next, job.nextRunAtMs?.let { dateTime(it) } ?: "—")
        }
        Detail(R.string.feature_cron_last, lastRunText(job))
        job.lastError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Detail(R.string.feature_cron_delivers, deliveryTitle(job.deliver, state.targets))
        job.model?.let { Detail(R.string.feature_cron_model, it) }
        state.notifies?.let { notifies ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_cron_notify), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.feature_cron_notify_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notifies, onCheckedChange = viewModel::setNotifies, enabled = !state.changingNotify)
            }
        }
    }
}

@Composable
private fun Detail(label: Int, value: String) {
    Column {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RunRow(run: CronRun, onOpen: () -> Unit) {
    ListItem(
        modifier = if (run.opensAsChat) Modifier.clickable(onClick = onOpen) else Modifier,
        headlineContent = { Text(run.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val parts = buildList {
                run.startedAtMs?.let { add(dateTime(it)) }
                if (!run.opensAsChat) add(stringResource(R.string.feature_cron_run_text_only))
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        },
    )
    HorizontalDivider()
}

@Composable
private fun stateText(state: CronState): String = stringResource(
    when (state) {
        CronState.Scheduled -> R.string.feature_cron_status_scheduled
        CronState.Paused -> R.string.feature_cron_state_paused
        CronState.Completed -> R.string.feature_cron_state_completed
        CronState.Error -> R.string.feature_cron_state_error
    },
)

@Composable
private fun lastRunText(job: CronJob): String {
    val at = job.lastRunAtMs ?: return stringResource(R.string.feature_cron_never)
    val time = dateTime(at)
    return when (job.lastStatus) {
        "ok" -> stringResource(R.string.feature_cron_last_ok, time)
        "error" -> stringResource(R.string.feature_cron_last_failed, time)
        "delivery_failed" -> stringResource(R.string.feature_cron_last_delivery_failed, time)
        else -> time
    }
}
