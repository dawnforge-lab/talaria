package lab.dawnforge.hermes.feature.chat

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.workspace.FilePreview
import lab.dawnforge.hermes.core.data.workspace.WorkspaceEntry
import lab.dawnforge.hermes.core.data.workspace.WorkspaceJson
import lab.dawnforge.hermes.core.data.workspace.WorkspaceRepository
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.chat.navigation.WorkspaceRoute

/** A file of the workspace, open in its sheet. */
internal data class FileSheet(
    val entry: WorkspaceEntry,
    val preview: FilePreview? = null,
    val failure: ConnectionFailure? = null,
    /** The file is being fetched to open or share it. */
    val fetching: Boolean = false,
    /** Why the last fetch to open or ([shareFailed]) share it failed: the preview has its own. */
    val fetchFailure: ConnectionFailure? = null,
    val shareFailed: Boolean = false,
)

internal data class WorkspaceUiState(
    val root: String,
    /** Each folder's entries, once read. */
    val entries: Map<String, List<WorkspaceEntry>> = emptyMap(),
    val expanded: Set<String> = emptySet(),
    val loading: Set<String> = emptySet(),
    /** The root couldn't be read. */
    val failure: ConnectionFailure? = null,
    val sheet: FileSheet? = null,
    /** A folder below the root couldn't be read. */
    val note: ConnectionFailure? = null,
) {
    /** The tree as rows: each entry at its depth, a folder's entries under it when it is open. */
    val rows: List<Pair<WorkspaceEntry, Int>>
        get() = buildList {
            fun add(path: String, depth: Int) {
                entries[path].orEmpty().forEach { entry ->
                    add(entry to depth)
                    if (entry.isDirectory && entry.path in expanded) add(entry.path, depth + 1)
                }
            }
            add(root, 0)
        }
}

/** A fetched file, for the screen to hand to another app. */
internal data class FetchedFile(val file: File, val mimeType: String, val share: Boolean)

/** The chat's working folder on the host, read a folder at a time, as the iOS app shows it. */
@HiltViewModel
internal class WorkspaceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val workspace: WorkspaceRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<WorkspaceRoute>()
    private val _state = MutableStateFlow(WorkspaceUiState(route.root))
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()
    private val fetched = Channel<FetchedFile>(Channel.BUFFERED)
    val files = fetched.receiveAsFlow()
    private var previewJob: Job? = null

    init {
        load(route.root)
    }

    fun refresh() {
        _state.update { WorkspaceUiState(route.root, sheet = it.sheet) }
        load(route.root)
    }

    /** Opens or closes a folder; its entries are read the first time it opens. */
    fun toggle(entry: WorkspaceEntry) {
        if (entry.path in _state.value.expanded) {
            _state.update { it.copy(expanded = it.expanded - entry.path) }
            return
        }
        _state.update { it.copy(expanded = it.expanded + entry.path) }
        if (entry.path !in _state.value.entries) load(entry.path)
    }

    fun openFile(entry: WorkspaceEntry) {
        _state.update { it.copy(sheet = FileSheet(entry)) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = workspace.preview(id, route.profile, entry.path)
            _state.update { state ->
                val sheet = state.sheet?.takeIf { it.entry == entry } ?: return@update state
                state.copy(
                    sheet = when (result) {
                        is NetResult.Success -> sheet.copy(preview = result.value)
                        is NetResult.Failure -> sheet.copy(failure = result.failure)
                    },
                )
            }
        }
    }

    fun closeFile() {
        previewJob?.cancel()
        _state.update { it.copy(sheet = null) }
    }

    /** Fetches the open file into [target] for another app: to open it, or ([share]) to share it. */
    fun fetch(share: Boolean, target: File) {
        val sheet = _state.value.sheet ?: return
        if (sheet.fetching) return
        _state.update { it.copy(sheet = sheet.copy(fetching = true, fetchFailure = null)) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = workspace.download(id, route.profile, sheet.entry.path, target)
            _state.update { state ->
                val current = state.sheet?.takeIf { it.entry == sheet.entry } ?: return@update state
                val failure = (result as? NetResult.Failure)?.failure
                state.copy(sheet = current.copy(fetching = false, fetchFailure = failure, shareFailed = share))
            }
            if (result is NetResult.Success) {
                fetched.send(FetchedFile(target, HostFiles.mimeType(sheet.entry.name, sheet.preview?.mimeType), share))
            }
        }
    }

    fun noteShown() = _state.update { it.copy(note = null) }

    private fun load(path: String) {
        _state.update { it.copy(loading = it.loading + path) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = workspace.list(id, route.profile, path)
            _state.update { state ->
                val loading = state.loading - path
                when (result) {
                    is NetResult.Success -> state.copy(
                        loading = loading,
                        entries =
                            state.entries + (path to result.value),
                    )

                    // A folder that can't be read closes again; the root shows why.
                    is NetResult.Failure -> if (path == route.root) {
                        state.copy(loading = loading, failure = result.failure)
                    } else {
                        state.copy(loading = loading, expanded = state.expanded - path, note = result.failure)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScreen(viewModel: WorkspaceViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.files.collect { fetched ->
            if (fetched.share) {
                HostFiles.share(context, fetched.file, fetched.mimeType)
            } else {
                HostFiles.open(context, fetched.file, fetched.mimeType)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val note = state.note?.let { failureText(it) }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        WorkspaceJson.baseName(state.root).ifBlank { stringResource(R.string.feature_chat_workspace) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_chat_back))
                    }
                },
                actions = {
                    if (state.root in state.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.feature_chat_workspace_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val failure = state.failure
            val rows = state.rows
            when {
                failure != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.feature_chat_workspace_failed, failureText(failure)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_chat_retry)) }
                }

                rows.isEmpty() && state.root in state.loading -> CircularProgressIndicator(
                    Modifier.align(Alignment.Center),
                )

                rows.isEmpty() -> Text(
                    stringResource(R.string.feature_chat_workspace_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { it.first.path }) { (entry, depth) ->
                        EntryRow(
                            entry = entry,
                            depth = depth,
                            expanded = entry.path in state.expanded,
                            loading = entry.path in state.loading,
                            onClick = { if (entry.isDirectory) viewModel.toggle(entry) else viewModel.openFile(entry) },
                        )
                    }
                }
            }
        }
    }
    state.sheet?.let { sheet ->
        FileSheetView(
            sheet = sheet,
            onOpen = { viewModel.fetch(share = false, HostFiles.cacheFile(context, sheet.entry.name)) },
            onShare = { viewModel.fetch(share = true, HostFiles.cacheFile(context, sheet.entry.name)) },
            onClose = viewModel::closeFile,
        )
    }
}

@Composable
private fun EntryRow(entry: WorkspaceEntry, depth: Int, expanded: Boolean, loading: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp + (depth * 18).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Icon(
            painterResource(entryIcon(entry)),
            contentDescription = null,
            tint = with(MaterialTheme.colorScheme) { if (entry.isDirectory) primary else onSurfaceVariant },
        )
        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)

            entry.isDirectory -> Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun entryIcon(entry: WorkspaceEntry): Int =
    if (entry.isDirectory) R.drawable.feature_chat_ic_folder else R.drawable.feature_chat_ic_file

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheetView(sheet: FileSheet, onOpen: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sheet.entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                sheet.entry.path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = !sheet.fetching) {
                    Text(stringResource(R.string.feature_chat_workspace_open))
                }
                OutlinedButton(onClick = onShare, enabled = !sheet.fetching) {
                    Text(stringResource(R.string.feature_chat_workspace_share))
                }
                if (sheet.fetching) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp).align(Alignment.CenterVertically),
                        strokeWidth = 2.dp,
                    )
                }
            }
            sheet.fetchFailure?.let { failure ->
                Text(
                    stringResource(
                        if (sheet.shareFailed) {
                            R.string.feature_chat_workspace_share_failed
                        } else {
                            R.string.feature_chat_workspace_open_failed
                        },
                        failureText(failure),
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val preview = sheet.preview
            val failure = sheet.failure
            when {
                failure != null -> Text(
                    stringResource(R.string.feature_chat_workspace_preview_failed, failureText(failure)),
                    color = MaterialTheme.colorScheme.error,
                )

                preview == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

                else -> {
                    Text(
                        listOf(
                            preview.language,
                            Formatter.formatShortFileSize(context, preview.byteSize),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        preview.binary -> Text(stringResource(R.string.feature_chat_workspace_binary))

                        preview.text.isEmpty() -> Text(stringResource(R.string.feature_chat_workspace_file_empty))

                        else -> {
                            if (preview.truncated) {
                                Text(
                                    stringResource(R.string.feature_chat_workspace_truncated),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SelectionContainer {
                                Text(
                                    preview.text.take(PREVIEW_CHARS),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    softWrap = false,
                                    modifier = Modifier
                                        .heightIn(max = 480.dp)
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
            Box(Modifier.size(24.dp))
        }
    }
}

/** A text drawn whole costs layout time: the sheet shows the start, the app opened shows the rest. */
private const val PREVIEW_CHARS = 100_000
