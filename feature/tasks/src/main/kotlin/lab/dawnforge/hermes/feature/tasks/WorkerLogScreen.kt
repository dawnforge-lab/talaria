package lab.dawnforge.hermes.feature.tasks

import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import lab.dawnforge.hermes.core.data.tasks.KanbanRepository
import lab.dawnforge.hermes.core.data.tasks.WorkerLog
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.tasks.navigation.WorkerLogRoute

internal data class WorkerLogUiState(
    val log: WorkerLog? = null,
    val logFailure: ConnectionFailure? = null,
    val logLoading: Boolean = false,
)

/** A task's worker log, read on demand: it is plain text the worker wrote, up to the last 64 KiB. */
@HiltViewModel
internal class WorkerLogViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val kanban: KanbanRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<WorkerLogRoute>()
    private val _state = MutableStateFlow(WorkerLogUiState())
    val state: StateFlow<WorkerLogUiState> = _state.asStateFlow()

    fun loadLog() {
        _state.update { it.copy(logLoading = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val read = kanban.log(id, route.board, route.taskId)) {
                is NetResult.Success -> _state.update {
                    it.copy(log = read.value, logFailure = null, logLoading = false)
                }

                is NetResult.Failure -> _state.update { it.copy(logFailure = read.failure, logLoading = false) }
            }
        }
    }
}

/** The task's worker log: read once on opening, then again on Refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkerLogScreen(viewModel: WorkerLogViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.loadLog() }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.feature_tasks_worker_log))
                        state.log?.takeIf { it.exists }?.let { log ->
                            val size = Formatter.formatShortFileSize(context, log.sizeBytes)
                            Text(
                                if (log.truncated) stringResource(R.string.feature_tasks_log_truncated, size) else size,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_tasks_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::loadLog, enabled = !state.logLoading) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.feature_tasks_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val log = state.log
            val failure = state.logFailure
            when {
                failure != null -> Text(
                    stringResource(R.string.feature_tasks_log_failed, failureText(failure)),
                    Modifier.padding(16.dp),
                )

                log == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                !log.exists -> Text(stringResource(R.string.feature_tasks_log_never), Modifier.padding(16.dp))

                log.content.isBlank() -> Text(stringResource(R.string.feature_tasks_log_empty), Modifier.padding(16.dp))

                else -> {
                    val text = if (log.content.length > MAX_CHARS) {
                        stringResource(R.string.feature_tasks_log_omitted) + "\n" + log.content.takeLast(MAX_CHARS)
                    } else {
                        log.content
                    }
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = false,
                            // Opens at the end, where the newest output is.
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                                .horizontalScroll(rememberScrollState())
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_CHARS = 60_000
