package lab.dawnforge.hermes.feature.sessions

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectsScreen(viewModel: ProjectsViewModel, onBack: () -> Unit, onOpenProject: (Project) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val snackbar = remember { SnackbarHostState() }
    val note = state.note?.let { stringResource(R.string.feature_sessions_project_change_failed, failureText(it)) }
    LaunchedEffect(state.note) {
        if (note != null) {
            snackbar.showSnackbar(note)
            viewModel.noteShown()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_sessions_projects)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_sessions_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.unsupported && state.projects != null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startNew,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.feature_sessions_new_project)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::search,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Filled.Clear, null) }
                    }
                } else {
                    null
                },
                placeholder = { Text(stringResource(R.string.feature_sessions_search_projects)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                ProjectsBody(state, viewModel, onOpenProject)
            }
        }
    }
    state.creating?.let { draft -> NewProjectDialog(draft, viewModel) }
    state.picking?.let { pick ->
        FolderPickerDialog(
            pick,
            onOpen = viewModel::openFolder,
            onChoose = viewModel::chooseFolder,
            onDismiss = viewModel::closePicker,
        )
    }
    state.renaming?.let { project ->
        RenameProjectDialog(project, onRename = viewModel::rename, onDismiss = viewModel::dismiss)
    }
    state.deleting?.let { project ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.feature_sessions_delete_project_title)) },
            text = { Text(stringResource(R.string.feature_sessions_delete_project_body, project.title)) },
            confirmButton = {
                TextButton(onClick = viewModel::delete) {
                    Text(
                        stringResource(R.string.feature_sessions_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.feature_sessions_cancel)) }
            },
        )
    }
}

@Composable
private fun ProjectsBody(state: ProjectsUiState, viewModel: ProjectsViewModel, onOpenProject: (Project) -> Unit) {
    val projects = state.projects
    val failure = state.failure
    Box(Modifier.fillMaxSize()) {
        when {
            state.unsupported -> CenteredNote(
                stringResource(R.string.feature_sessions_projects_unsupported),
                Modifier.align(Alignment.Center),
            )

            projects == null && failure != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Text(
                    stringResource(R.string.feature_sessions_projects_failed, failureText(failure)),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_sessions_retry)) }
            }

            projects == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.feature_sessions_projects_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.shown.isEmpty() -> CenteredNote(
                stringResource(
                    when {
                        state.query.isBlank() -> R.string.feature_sessions_projects_none
                        else -> R.string.feature_sessions_projects_no_match
                    },
                ),
                Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.shown, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        busy = state.busy,
                        onOpen = { onOpenProject(project) },
                        onRename = { viewModel.askRename(project) },
                        onDelete = { viewModel.askDelete(project) },
                    )
                    HorizontalDivider()
                }
                item { Box(Modifier.size(72.dp)) }
            }
        }
    }
}

@Composable
private fun CenteredNote(text: String, modifier: Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(24.dp))
}

@Composable
private fun ProjectRow(
    project: Project,
    busy: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { ProjectTile(project, 44) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(project.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (project.isAuto) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(
                            stringResource(R.string.feature_sessions_project_discovered),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        },
        supportingContent = project.primaryPath?.let { path ->
            {
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(project.sessionCount.toString(), style = MaterialTheme.typography.labelLarge)
                if (project.editable) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) {
                            Icon(
                                Icons.Filled.MoreVert,
                                stringResource(R.string.feature_sessions_row_actions, project.title),
                            )
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_sessions_action_rename)) },
                                onClick = {
                                    menu = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_sessions_action_delete)) },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/** A project's square: a house for Home, a folder otherwise, in the project's colour. */
@Composable
internal fun ProjectTile(project: Project, size: Int) {
    val tint = projectColor(project.color) ?: if (project.isHome) MaterialTheme.colorScheme.primary else ORANGE
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * TILE_ROUNDING).dp)).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (project.isHome) {
            Icon(Icons.Filled.Home, contentDescription = null, tint = tint)
        } else {
            Icon(painterResource(R.drawable.feature_sessions_ic_folder), contentDescription = null, tint = tint)
        }
    }
}

/** `#rrggbb` (or `#rgb`) as a colour; null when it isn't one. */
internal fun projectColor(hex: String?): Color? {
    val value = hex?.trim()?.removePrefix("#") ?: return null
    val full = if (value.length == 3) value.map { "$it$it" }.joinToString("") else value
    if (full.length != 6) return null
    return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

@Composable
private fun NewProjectDialog(draft: NewProject, viewModel: ProjectsViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelNew,
        title = { Text(stringResource(R.string.feature_sessions_new_project)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> viewModel.editNew { it.copy(name = name) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.feature_sessions_project_name)) },
                    placeholder = { Text(stringResource(R.string.feature_sessions_project_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.feature_sessions_project_folders),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (draft.folders.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_sessions_project_no_folders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                draft.folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folder,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.removeFolder(folder) }) {
                            Icon(Icons.Filled.Clear, stringResource(R.string.feature_sessions_project_remove_folder))
                        }
                    }
                }
                TextButton(onClick = viewModel::pickFolder, enabled = !draft.saving) {
                    Text(stringResource(R.string.feature_sessions_project_add_folder))
                }
                OutlinedTextField(
                    value = draft.idea,
                    onValueChange = { idea -> viewModel.editNew { it.copy(idea = idea) } },
                    label = { Text(stringResource(R.string.feature_sessions_project_idea)) },
                    supportingText = { Text(stringResource(R.string.feature_sessions_project_idea_help)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                draft.failure?.let {
                    Text(
                        stringResource(R.string.feature_sessions_project_create_failed, failureText(it)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::create, enabled = draft.ready) {
                if (draft.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.feature_sessions_project_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelNew) { Text(stringResource(R.string.feature_sessions_cancel)) }
        },
    )
}

@Composable
private fun RenameProjectDialog(project: Project, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(project.id) { mutableStateOf(project.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_sessions_rename_project)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.feature_sessions_project_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.feature_sessions_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_cancel)) } },
    )
}

private val ORANGE = Color(0xFFF59E0B)
private const val TILE_ROUNDING = 0.3f
