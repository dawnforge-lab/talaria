package lab.dawnforge.hermes.feature.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.data.workspace.WorkspaceEntry
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.ui.failureText

/** A folder on the host being chosen: the one listed, and its folders. */
internal data class FolderPick(
    val path: String,
    val folders: List<WorkspaceEntry> = emptyList(),
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
)

/** The folder above [path] on the host, for either separator; null at a root (`C:\`, `/`). */
internal fun parentFolder(path: String): String? {
    val trimmed = path.trimEnd('/', '\\')
    if (trimmed.isEmpty()) return null
    val cut = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
    if (cut < 0) return null
    val parent = trimmed.substring(0, cut)
    return when {
        parent.isEmpty() -> "/"

        // `C:` is the drive's current folder on Windows; its root is `C:\`.
        parent.length == 2 && parent[1] == ':' -> parent + trimmed[cut]

        else -> parent
    }
}

/**
 * Chooses a folder from Hermes' listing, never typed in, as iOS does: open a folder to go into it,
 * Up to go back, Choose to take the one listed.
 */
@Composable
internal fun FolderPickerDialog(
    pick: FolderPick,
    onOpen: (String) -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_sessions_choose_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(
                        pick.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val parent = parentFolder(pick.path)
                if (parent != null) {
                    TextButton(onClick = {
                        onOpen(parent)
                    }) { Text(stringResource(R.string.feature_sessions_folder_up)) }
                }
                val failure = pick.failure
                when {
                    pick.loading -> Row(
                        Modifier.fillMaxWidth().height(80.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.padding(start = 8.dp))
                    }

                    failure != null -> Text(
                        stringResource(R.string.feature_sessions_folder_failed, failureText(failure)),
                        color = MaterialTheme.colorScheme.error,
                    )

                    pick.folders.isEmpty() -> Text(
                        stringResource(R.string.feature_sessions_folder_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(Modifier.height(320.dp)) {
                        items(pick.folders, key = { it.path }) { folder ->
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onOpen(folder.path)
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(pick.path) }, enabled = !pick.loading && pick.failure == null) {
                Text(stringResource(R.string.feature_sessions_folder_choose))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_cancel)) } },
    )
}
