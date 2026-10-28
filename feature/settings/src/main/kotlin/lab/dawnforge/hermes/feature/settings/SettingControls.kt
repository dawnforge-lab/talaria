package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import lab.dawnforge.hermes.core.data.settings.ConfigValue
import lab.dawnforge.hermes.core.data.settings.formatNumber
import lab.dawnforge.hermes.core.ui.failureText

/** A section of a settings page. */
@Composable
internal fun CardHeader(title: String, detail: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One setting, edited the way its control says. */
@Composable
internal fun FieldRow(
    field: Field,
    value: ConfigValue?,
    choices: List<String>,
    save: SaveState?,
    onSet: (ConfigValue) -> Unit,
    onSetText: (String) -> Unit,
) {
    val label = stringResource(field.label)
    val saving = save == SaveState.Saving
    when (val control = field.control) {
        is Control.Toggle, is Control.TextToggle -> {
            val on = when (control) {
                is Control.Toggle -> (value as? ConfigValue.Flag)?.on ?: control.default

                is Control.TextToggle -> (value as? ConfigValue.Text)?.text?.let { it != control.off }
                    ?: control.default
            }
            val toggle = { checked: Boolean ->
                onSet(
                    when (control) {
                        is Control.TextToggle -> ConfigValue.Text(if (checked) control.on else control.off)
                        else -> ConfigValue.Flag(checked)
                    },
                )
            }
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Help(field, save, label) },
                trailingContent = { Switch(checked = on, onCheckedChange = null, enabled = !saving) },
                modifier = Modifier.toggleable(on, enabled = !saving, role = Role.Switch, onValueChange = toggle),
            )
        }

        is Control.Choice, Control.TimeZone -> {
            val current = (value as? ConfigValue.Text)?.text ?: (control as? Control.Choice)?.default.orEmpty()
            var picking by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Help(field, save, label) },
                trailingContent = {
                    Text(
                        choiceLabel(field.key, current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.4f),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.clickable(enabled = !saving) { picking = true },
            )
            if (picking) {
                val phone = TimeZone.getDefault().id
                val options = if (field.key == "timezone") listOf("", phone) + (choices - "" - phone) else choices
                ChoiceDialog(
                    title = label,
                    options = (if (current in options) options else listOf(current) + options)
                        .map { it to optionText(field.key, it, phone) },
                    selected = current,
                    onPick = {
                        picking = false
                        onSet(ConfigValue.Text(it))
                    },
                    onDismiss = { picking = false },
                )
            }
        }

        is Control.Text, is Control.Number, Control.Lines -> {
            val stored = when (value) {
                is ConfigValue.Number -> formatNumber(value.number)

                is ConfigValue.Text -> value.text

                is ConfigValue.Items -> value.items.joinToString("\n")

                is ConfigValue.Flag -> value.on.toString()

                null -> when (control) {
                    is Control.Number -> formatNumber(control.default)
                    is Control.Text -> control.default
                    else -> ""
                }
            }
            var draft by rememberSaveable(stored) { mutableStateOf(stored) }
            val lines = control == Control.Lines
            val submit = { if (draft != stored) onSetText(draft) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Help(field, save, label)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = !saving,
                        singleLine = !lines,
                        minLines = if (lines) 3 else 1,
                        placeholder = if (lines) {
                            (
                                {
                                    Text(stringResource(R.string.feature_settings_lines_hint))
                                }
                                )
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (control is Control.Number) KeyboardType.Decimal else KeyboardType.Text,
                            imeAction = if (lines) ImeAction.Default else ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f).padding(top = 4.dp),
                    )
                    if (draft != stored) {
                        TextButton(onClick = submit, enabled = !saving) {
                            Text(stringResource(R.string.feature_settings_save))
                        }
                    }
                }
            }
        }
    }
}

/** The field's help, or how its save went. */
@Composable
private fun Help(field: Field, save: SaveState?, label: String) {
    Column {
        Text(stringResource(field.help), style = MaterialTheme.typography.bodySmall)
        SaveLine(save, label)
    }
}

@Composable
internal fun SaveLine(save: SaveState?, label: String) {
    val (text, error) = when (save) {
        null -> return

        SaveState.Saving -> stringResource(R.string.feature_settings_saving) to false

        SaveState.Saved -> stringResource(R.string.feature_settings_saved) to false

        SaveState.NotANumber -> stringResource(R.string.feature_settings_invalid_number, label) to true

        is SaveState.Failed -> stringResource(
            R.string.feature_settings_save_failed,
            label,
            failureText(save.failure),
        ) to
            true
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** A choice's name: its own label, a personality capitalised, or the value as it is. */
@Composable
internal fun choiceLabel(key: String, value: String): String {
    optionLabel(key, value)?.let { return stringResource(it) }
    return if (key == "display.personality") value.replaceFirstChar(Char::titlecase) else value
}

@Composable
private fun optionText(key: String, value: String, phoneZone: String): String =
    if (key == "timezone" && value == phoneZone && value.isNotEmpty()) {
        stringResource(R.string.feature_settings_timezone_phone, value)
    } else {
        choiceLabel(key, value)
    }

/** Picks one of [options] (value to label), with a search when there are many. */
@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = options.size > SEARCH_FROM
    val shown = if (query.isBlank()) {
        options
    } else {
        options.filter { (value, label) ->
            value.contains(query, ignoreCase = true) ||
                label.contains(query, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (searchable) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.feature_settings_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.feature_settings_no_matches), modifier = Modifier.padding(top = 16.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.first }) { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .selectable(value == selected, role = Role.RadioButton) { onPick(value) }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

private const val SEARCH_FROM = 12
