package lab.dawnforge.hermes.feature.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.data.settings.Skill
import lab.dawnforge.hermes.core.data.tasks.ModelOption
import lab.dawnforge.hermes.core.data.tasks.ModelOverride

/** The reasoning levels a task can be given, as Hermes takes them. */
internal val REASONING_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/**
 * A model for one task instead of its profile's: one of the models Hermes offers for tasks ([options], null while
 * they load), with a reasoning level or the profile's own. Saving null goes back to the profile's model.
 */
@Composable
internal fun TaskModelDialog(
    options: List<ModelOption>?,
    current: ModelOverride?,
    onSave: (ModelOverride?) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(current?.let { it.provider to it.model }) }
    var reasoning by remember { mutableStateOf(current?.reasoning) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_tasks_model_choose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceRow(stringResource(R.string.feature_tasks_model_inherit), picked == null) { picked = null }
                Text(
                    stringResource(R.string.feature_tasks_model_reasoning),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf<String?>(null) + REASONING_LEVELS).forEach { level ->
                        FilterChip(
                            selected = reasoning == level,
                            enabled = picked != null,
                            onClick = { reasoning = level },
                            label = { Text(level ?: stringResource(R.string.feature_tasks_model_reasoning_profile)) },
                        )
                    }
                }
                when {
                    options == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))

                    options.isEmpty() -> Text(
                        stringResource(R.string.feature_tasks_model_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        options.forEach { option ->
                            item(key = "provider:${option.provider}") {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(option.models, key = { "${option.provider}:$it" }) { model ->
                                ChoiceRow(model, picked == (option.provider to model)) {
                                    picked =
                                        option.provider to model
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(picked?.let { (provider, model) -> ModelOverride(model, provider, reasoning) })
            }) { Text(stringResource(R.string.feature_tasks_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

/** "openrouter: some-model · high", or null for the profile's own model. */
internal fun modelLabel(model: ModelOverride?): String? = model?.let {
    listOfNotNull(it.provider, it.model).joinToString(": ") + it.reasoning?.let { level -> " · $level" }.orEmpty()
}

/**
 * Skills the task's worker loads: picked from the profile's skills ([choices], null while they load), or typed
 * when the search names one that isn't listed.
 */
@Composable
internal fun SkillsDialog(
    choices: List<Skill>?,
    chosen: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(chosen) }
    var query by remember { mutableStateOf("") }
    val typed = query.trim()
    val shown = choices.orEmpty().map { it.name }.plus(chosen).distinct()
        .filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_tasks_skills)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.feature_tasks_skills_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (choices == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (typed.isNotEmpty() && typed !in shown) {
                        item {
                            SkillRow(stringResource(R.string.feature_tasks_skills_add, typed), typed in picked) {
                                picked = picked + typed
                                query = ""
                            }
                        }
                    }
                    items(shown, key = { it }) { name ->
                        SkillRow(name, name in picked) { picked = if (name in picked) picked - name else picked + name }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picked) }) { Text(stringResource(R.string.feature_tasks_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_tasks_cancel)) } },
    )
}

@Composable
private fun SkillRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
