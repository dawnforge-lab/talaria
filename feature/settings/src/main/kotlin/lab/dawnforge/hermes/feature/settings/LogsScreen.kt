package lab.dawnforge.hermes.feature.settings

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.LogComponent
import lab.dawnforge.hermes.core.data.settings.LogFile
import lab.dawnforge.hermes.core.data.settings.LogLevel
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText

internal data class LogsUiState(
    val file: LogFile = LogFile.Gateway,
    val level: LogLevel = LogLevel.All,
    val component: LogComponent = LogComponent.All,
    val search: String = "",
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
)

/** Hermes' recent log lines, as the desktop's Logs page filters them. */
@HiltViewModel
internal class LogsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val gateway: GatewayStatusRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()
    private var reading: Job? = null

    init {
        refresh()
    }

    fun show(file: LogFile? = null, level: LogLevel? = null, component: LogComponent? = null, search: String? = null) {
        _state.update {
            it.copy(
                file = file ?: it.file,
                level = level ?: it.level,
                component = component ?: it.component,
                search = search ?: it.search,
            )
        }
        refresh()
    }

    fun refresh() {
        reading?.cancel()
        reading = viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(loading = true) }
            val current = _state.value
            when (val result = gateway.logs(id, current.file, current.level, current.component, current.search)) {
                is NetResult.Success -> _state.update { it.copy(lines = result.value, loading = false, failure = null) }
                is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogsScreen(viewModel: LogsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val title = stringResource(R.string.feature_settings_logs)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.feature_settings_refresh))
                    }
                    IconButton(
                        enabled = state.lines.isNotEmpty(),
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, title)
                                .putExtra(Intent.EXTRA_TEXT, state.lines.joinToString("\n"))
                            context.startActivity(Intent.createChooser(send, null))
                        },
                    ) { Icon(Icons.Filled.Share, stringResource(R.string.feature_settings_log_share)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Filter(R.string.feature_settings_log_file, state.file, LogFile.entries, ::fileLabel) {
                    viewModel.show(file = it)
                }
                Filter(R.string.feature_settings_log_level, state.level, LogLevel.entries, ::levelLabel) {
                    viewModel.show(level = it)
                }
                Filter(
                    R.string.feature_settings_log_component,
                    state.component,
                    LogComponent.entries,
                    ::componentLabel,
                ) {
                    viewModel.show(component = it)
                }
            }
            var search by rememberSaveable { mutableStateOf(state.search) }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.feature_settings_log_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.show(search = search) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.fillMaxSize()) {
                val failure = state.failure
                when {
                    state.loading && state.lines.isEmpty() -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )

                    failure != null -> Text(
                        stringResource(R.string.feature_settings_log_failed, failureText(failure)),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )

                    state.lines.isEmpty() -> Text(
                        stringResource(R.string.feature_settings_log_empty),
                        Modifier.padding(16.dp),
                    )

                    else -> SelectionContainer {
                        Text(
                            state.lines.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            softWrap = false,
                            modifier = Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A chip naming the current [value], with a menu of the others. */
@Composable
private fun <T> Filter(@StringRes title: Int, value: T, values: List<T>, label: (T) -> Int, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != values.first(),
            onClick = { open = true },
            label = { Text(stringResource(title) + ": " + stringResource(label(value))) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(label(option))) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

@StringRes
private fun fileLabel(file: LogFile): Int = when (file) {
    LogFile.Agent -> R.string.feature_settings_log_file_agent
    LogFile.Errors -> R.string.feature_settings_log_file_errors
    LogFile.Gateway -> R.string.feature_settings_log_file_gateway
}

@StringRes
private fun levelLabel(level: LogLevel): Int = when (level) {
    LogLevel.All -> R.string.feature_settings_log_level_all
    LogLevel.Debug -> R.string.feature_settings_log_level_debug
    LogLevel.Info -> R.string.feature_settings_log_level_info
    LogLevel.Warning -> R.string.feature_settings_log_level_warning
    LogLevel.Error -> R.string.feature_settings_log_level_error
}

@StringRes
private fun componentLabel(component: LogComponent): Int = when (component) {
    LogComponent.All -> R.string.feature_settings_log_component_all
    LogComponent.Gateway -> R.string.feature_settings_log_component_gateway
    LogComponent.Agent -> R.string.feature_settings_log_component_agent
    LogComponent.Tools -> R.string.feature_settings_log_component_tools
    LogComponent.Cli -> R.string.feature_settings_log_component_cli
    LogComponent.Cron -> R.string.feature_settings_log_component_cron
}
