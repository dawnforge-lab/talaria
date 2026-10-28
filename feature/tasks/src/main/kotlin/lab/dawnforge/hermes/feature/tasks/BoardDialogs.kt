package lab.dawnforge.hermes.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** What the board's dialogs are for. */
internal sealed interface BoardDialog {
    data object Move : BoardDialog

    data object Assign : BoardDialog

    data object Priority : BoardDialog

    data object Archive : BoardDialog

    data object Delete : BoardDialog

    data object NewBoard : BoardDialog

    data object EditBoard : BoardDialog

    data object ArchiveBoard : BoardDialog
}

/** Picks one of [options] (value to label). */
@Composable
internal fun <T> PickDialog(title: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (value, label) ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(value) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

/** A whole number at least zero, for the selected tasks' priority. */
@Composable
internal fun PriorityDialog(onSet: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = text.trim().toIntOrNull()?.takeIf { it >= 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_tasks_priority_label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.feature_tasks_priority_help))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && value == null,
                    placeholder = { Text("0") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                value?.let(onSet)
            }, enabled = value != null) { Text(stringResource(R.string.feature_tasks_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

/** A board's name and description: a new board's ([slugged] shows the short name it will keep), or the shown one's. */
@Composable
internal fun BoardDialog(
    title: String,
    name: String,
    description: String,
    slugged: Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf(name) }
    var newDescription by remember { mutableStateOf(description) }
    val slug = boardSlug(newName)
    val ready = newName.isNotBlank() && (!slugged || slug.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.feature_tasks_board_name)) },
                )
                if (slugged && newName.isNotBlank()) {
                    Text(
                        if (slug.isEmpty()) {
                            stringResource(R.string.feature_tasks_board_slug_bad)
                        } else {
                            stringResource(R.string.feature_tasks_board_slug, slug)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = newDescription,
                    onValueChange = { newDescription = it },
                    label = { Text(stringResource(R.string.feature_tasks_board_description)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(newName, newDescription)
            }, enabled = ready) { Text(stringResource(R.string.feature_tasks_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

/** The selected tasks' bar: what can be done to all of them. */
@Composable
internal fun SelectionBar(count: Int, busy: Boolean, onAction: (BoardDialog) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        val enabled = count > 0 && !busy
        listOf(
            BoardDialog.Move to R.string.feature_tasks_bulk_move,
            BoardDialog.Assign to R.string.feature_tasks_bulk_assign,
            BoardDialog.Priority to R.string.feature_tasks_bulk_priority,
            BoardDialog.Archive to R.string.feature_tasks_archive,
            BoardDialog.Delete to R.string.feature_tasks_delete,
        ).forEach { (dialog, label) ->
            TextButton(onClick = { onAction(dialog) }, enabled = enabled) { Text(stringResource(label)) }
        }
    }
}
