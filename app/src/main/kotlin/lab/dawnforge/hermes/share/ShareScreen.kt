package lab.dawnforge.hermes.share

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lab.dawnforge.hermes.R

/** Where a share goes: the workspace (when there are several), New chat or a recent chat, and what came. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareScreen(viewModel: ShareViewModel, onCancel: () -> Unit, onAdd: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, stringResource(R.string.app_share_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = onAdd, enabled = state.canAdd) { Text(stringResource(R.string.app_share_add)) }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Preview(state) }
            if (state.workspaces.isEmpty()) {
                item { Note(stringResource(R.string.app_share_no_dashboard)) }
                return@LazyColumn
            }
            if (state.workspaces.size > 1) {
                item { Heading(stringResource(R.string.app_share_workspace)) }
                items(state.workspaces, key = { "w-" + it.dashboard.value }) { workspace ->
                    Choice(
                        title = workspace.label,
                        subtitle = workspace.profile,
                        selected = workspace == state.workspace,
                    ) { viewModel.choose(workspace) }
                }
            }
            item { Heading(stringResource(R.string.app_share_chat)) }
            item {
                Choice(stringResource(R.string.app_share_new_chat), null, state.target.sessionId == null) {
                    viewModel.target(ShareTarget(null, null))
                }
            }
            items(state.chats, key = { "c-" + it.id }) { chat ->
                Choice(chat.displayTitle, null, chat.id == state.target.sessionId) {
                    viewModel.target(ShareTarget(chat.id, chat.displayTitle))
                }
            }
            item { Note(stringResource(R.string.app_share_review)) }
        }
    }
}

@Composable
private fun Preview(state: ShareUiState) {
    val context = LocalContext.current
    if (state.nothing) Note(stringResource(R.string.app_share_nothing))
    if (state.text.isNotBlank()) {
        Text(
            state.text,
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
    state.files.forEach { file ->
        ListItem(
            headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(Formatter.formatShortFileSize(context, file.bytes.size.toLong())) },
        )
    }
    if (state.leftOut.isNotEmpty()) {
        Heading(stringResource(R.string.app_share_left_out))
        state.leftOut.forEach { left ->
            val reason = when (left) {
                is LeftOut.TooLarge -> stringResource(
                    R.string.app_share_too_large,
                    left.name,
                    Formatter.formatShortFileSize(context, left.limitBytes),
                )

                is LeftOut.Unreadable -> stringResource(R.string.app_share_unreadable, left.name)

                is LeftOut.TooMany -> stringResource(R.string.app_share_too_many, left.name)

                LeftOut.TextCut -> stringResource(R.string.app_share_text_cut)
            }
            Text(
                reason,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp))
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

private const val PREVIEW_LINES = 8
