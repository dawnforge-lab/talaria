package lab.dawnforge.hermes.feature.chat

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import lab.dawnforge.hermes.core.data.workspace.Artifact
import lab.dawnforge.hermes.core.data.workspace.ArtifactFilter
import lab.dawnforge.hermes.core.data.workspace.ArtifactKind
import lab.dawnforge.hermes.core.data.workspace.ArtifactSource
import lab.dawnforge.hermes.core.ui.failureText

/** "Show in chat": the chat ([sessionId]) and the message that mentions the artifact. */
internal typealias ShowInChat = (sessionId: String, messageId: Long, title: String?) -> Unit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactsScreen(viewModel: ArtifactsViewModel, onBack: () -> Unit, onShowInChat: ShowInChat) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ArtifactEffect.OpenFile -> HostFiles.open(context, effect.file, effect.mimeType)
                is ArtifactEffect.Browse -> HostFiles.browse(context, effect.url)
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when {
                        state.forChat -> R.string.feature_chat_artifacts_in_chat
                        else -> R.string.feature_chat_artifacts
                    }
                    Text(stringResource(title))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_chat_back))
                    }
                },
                actions = { MoreMenu(state.showMissing, viewModel::showMissing) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.supported) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                false -> Message(
                    stringResource(R.string.feature_chat_artifacts_update_title),
                    stringResource(R.string.feature_chat_artifacts_update),
                    Modifier.align(Alignment.Center),
                )

                true -> ArtifactsList(state, viewModel, onShowInChat)
            }
        }
    }
    state.problem?.let { problem -> ProblemDialog(problem, viewModel::problemShown) }
    state.page?.let { (_, html) -> PageSheet(html, onClose = viewModel::closePage) }
}

@Composable
private fun MoreMenu(showMissing: Boolean, onShowMissing: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_chat_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_artifacts_show_missing)) },
                trailingIcon = { Switch(checked = showMissing, onCheckedChange = null) },
                onClick = { onShowMissing(!showMissing) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtifactsList(state: ArtifactsUiState, viewModel: ArtifactsViewModel, onShowInChat: ShowInChat) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::search,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text(stringResource(R.string.feature_chat_artifacts_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(
                rememberScrollState(),
            ).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            ArtifactFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == state.filter,
                    onClick = { viewModel.filter(filter) },
                    label = { Text(stringResource(filterName(filter))) },
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            val failure = state.failure
            when {
                state.loading && state.items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                failure != null && state.items.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.feature_chat_artifacts_failed),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(failureText(failure), color = MaterialTheme.colorScheme.error)
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.feature_chat_retry)) }
                }

                state.items.isEmpty() && state.nextBefore == null -> EmptyList(
                    state,
                    viewModel,
                    Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize().alpha(if (state.stale) STALE_ALPHA else 1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                ) {
                    days(state, viewModel, onShowInChat)
                    item(key = "more") { More(state, viewModel) }
                }
            }
        }
    }
}

@Composable
private fun EmptyList(state: ArtifactsUiState, viewModel: ArtifactsViewModel, modifier: Modifier) {
    val searching = state.query.isNotBlank() || state.filter != ArtifactFilter.All
    val text = stringResource(
        when {
            searching -> R.string.feature_chat_artifacts_no_results
            state.forChat -> R.string.feature_chat_artifacts_none_chat
            else -> R.string.feature_chat_artifacts_none
        },
    ) + if (state.showMissing) "" else " " + stringResource(R.string.feature_chat_artifacts_missing_hidden)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(24.dp),
    ) {
        Text(
            stringResource(
                when {
                    searching -> R.string.feature_chat_artifacts_no_results_title
                    else -> R.string.feature_chat_artifacts_none_title
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.showMissing) {
            TextButton(onClick = { viewModel.showMissing(true) }) {
                Text(stringResource(R.string.feature_chat_artifacts_show_missing))
            }
        }
    }
}

@Composable
private fun More(state: ArtifactsUiState, viewModel: ArtifactsViewModel) {
    if (state.nextBefore == null || state.stale) return
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (state.moreFailure != null) {
            TextButton(onClick = viewModel::retryMore) { Text(stringResource(R.string.feature_chat_retry)) }
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            LaunchedEffect(state.items.size, state.nextBefore) { viewModel.loadMore() }
        }
    }
}

/** Each day, newest first: its pictures in a grid of three, then the rest in rows. */
private fun LazyListScope.days(state: ArtifactsUiState, viewModel: ArtifactsViewModel, onShowInChat: ShowInChat) {
    val zone = ZoneId.systemDefault()
    state.items.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }.forEach { (day, items) ->
        item(key = "day:$day") { DayHeader(day) }
        items.filter { it.kind.isVisual }.chunked(GRID_COLUMNS).forEach { row ->
            item(key = "grid:${row.first().id}") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                ) {
                    row.forEach { artifact -> Tile(artifact, state, viewModel, onShowInChat, Modifier.weight(1f)) }
                    repeat(GRID_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
        items(items.filter { !it.kind.isVisual }, key = { "row:${it.id}" }) { artifact ->
            ArtifactRow(artifact, state, viewModel, onShowInChat)
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val label = when (day) {
        today -> stringResource(R.string.feature_chat_artifacts_today)
        today.minusDays(1) -> stringResource(R.string.feature_chat_artifacts_yesterday)
        else -> dayLabel(context, day, day.year == today.year)
    }
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

private fun dayLabel(context: Context, day: LocalDate, sameYear: Boolean): String {
    val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val flags = if (sameYear) {
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    artifact: Artifact,
    state: ArtifactsUiState,
    viewModel: ArtifactsViewModel,
    onShowInChat: ShowInChat,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, artifact.id) { value = viewModel.thumbnail(artifact) }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (artifact.exists) 1f else MISSING_ALPHA)
            .combinedClickable(
                enabled = !state.stale,
                onClick = { viewModel.open(artifact, HostFiles.cacheFile(context, artifact.name)) },
                onLongClick = { menu = true },
            ),
    ) {
        val shown = image
        if (shown != null) {
            Image(
                shown,
                contentDescription = artifact.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(
                    painterResource(kindIcon(artifact.kind)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    artifact.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!artifact.exists) {
            Surface(shape = RoundedCornerShape(50), modifier = Modifier.padding(6.dp).align(Alignment.TopStart)) {
                Text(
                    stringResource(R.string.feature_chat_artifacts_missing),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (state.opening == artifact.id) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
        }
        ArtifactMenu(menu, artifact, viewModel, onShowInChat, onDismiss = { menu = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtifactRow(
    artifact: Artifact,
    state: ArtifactsUiState,
    viewModel: ArtifactsViewModel,
    onShowInChat: ShowInChat,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (artifact.exists) 1f else MISSING_ROW_ALPHA)
                .combinedClickable(
                    enabled = !state.stale,
                    onClick = { viewModel.open(artifact, HostFiles.cacheFile(context, artifact.name)) },
                    onLongClick = { menu = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(
                Modifier.size(
                    40.dp,
                ).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(kindIcon(artifact.kind)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    artifact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    detailLine(context, artifact, state.forChat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.opening == artifact.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        ArtifactMenu(menu, artifact, viewModel, onShowInChat, onDismiss = { menu = false })
    }
}

/** Kind, "You sent", the size or that it's gone, the link's host, then the time or the chat. */
@Composable
private fun detailLine(context: Context, artifact: Artifact, forChat: Boolean): String = buildList {
    add(stringResource(kindName(artifact.kind)))
    if (artifact.source == ArtifactSource.Attached) add(stringResource(R.string.feature_chat_artifacts_you_sent))
    when {
        artifact.kind == ArtifactKind.Link -> artifact.host?.let(::add)
        !artifact.exists -> add(stringResource(R.string.feature_chat_artifacts_gone))
        else -> artifact.size?.let { add(Formatter.formatShortFileSize(context, it)) }
    }
    if (forChat) {
        add(DateUtils.formatDateTime(context, artifact.timestamp * 1000, DateUtils.FORMAT_SHOW_TIME))
    } else {
        artifact.sessionTitle?.let(::add)
    }
}.joinToString(" · ")

@Composable
private fun ArtifactMenu(
    expanded: Boolean,
    artifact: Artifact,
    viewModel: ArtifactsViewModel,
    onShowInChat: ShowInChat,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.feature_chat_workspace_open)) },
            onClick = {
                onDismiss()
                viewModel.open(artifact, HostFiles.cacheFile(context, artifact.name))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.feature_chat_artifacts_show_in_chat)) },
            onClick = {
                onDismiss()
                onShowInChat(artifact.sessionId, artifact.messageId, artifact.sessionTitle)
            },
        )
        val url = artifact.url
        val path = artifact.path
        if (url != null && path == null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_workspace_share)) },
                onClick = {
                    onDismiss()
                    HostFiles.shareText(context, url)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_artifacts_copy_link)) },
                onClick = {
                    onDismiss()
                    HostFiles.copy(context, url)
                },
            )
        }
        if (path != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_chat_artifacts_copy_path)) },
                onClick = {
                    onDismiss()
                    HostFiles.copy(context, path)
                },
            )
        }
    }
}

@Composable
private fun ProblemDialog(problem: ArtifactProblem, onDismiss: () -> Unit) {
    val title = stringResource(
        when (problem) {
            is ArtifactProblem.Missing -> R.string.feature_chat_artifacts_gone_title
            ArtifactProblem.TooLarge -> R.string.feature_chat_artifacts_too_large
            is ArtifactProblem.Failed -> R.string.feature_chat_artifacts_open_failed
        },
    )
    val text = when (problem) {
        is ArtifactProblem.Missing -> problem.path.orEmpty()
        ArtifactProblem.TooLarge -> ""
        is ArtifactProblem.Failed -> failureText(problem.failure)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (text.isBlank()) null else ({ Text(text) }),
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_chat_artifacts_ok)) }
        },
    )
}

@Composable
private fun Message(title: String, text: String, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun filterName(filter: ArtifactFilter): Int = when (filter) {
    ArtifactFilter.All -> R.string.feature_chat_artifacts_all
    ArtifactFilter.Images -> R.string.feature_chat_artifacts_images
    ArtifactFilter.Pages -> R.string.feature_chat_artifacts_pages
    ArtifactFilter.Files -> R.string.feature_chat_artifacts_files
    ArtifactFilter.Links -> R.string.feature_chat_artifacts_links
}

private fun kindName(kind: ArtifactKind): Int = when (kind) {
    ArtifactKind.Image -> R.string.feature_chat_artifact_image
    ArtifactKind.Video -> R.string.feature_chat_artifact_video
    ArtifactKind.Audio -> R.string.feature_chat_artifact_audio
    ArtifactKind.Page -> R.string.feature_chat_artifact_page
    ArtifactKind.Document -> R.string.feature_chat_artifact_document
    ArtifactKind.File -> R.string.feature_chat_artifact_file
    ArtifactKind.Link -> R.string.feature_chat_artifact_link
}

private fun kindIcon(kind: ArtifactKind): Int = when (kind) {
    ArtifactKind.Image -> R.drawable.feature_chat_ic_image
    ArtifactKind.Video -> R.drawable.feature_chat_ic_video
    ArtifactKind.Audio -> R.drawable.feature_chat_ic_audio
    ArtifactKind.Page -> R.drawable.feature_chat_ic_page
    ArtifactKind.Document -> R.drawable.feature_chat_ic_document
    ArtifactKind.File -> R.drawable.feature_chat_ic_file
    ArtifactKind.Link -> R.drawable.feature_chat_ic_link
}

private const val GRID_COLUMNS = 3
private const val STALE_ALPHA = 0.45f
private const val MISSING_ALPHA = 0.45f
private const val MISSING_ROW_ALPHA = 0.55f
