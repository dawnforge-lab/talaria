package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.chat.ChatModel
import lab.dawnforge.hermes.core.chat.ChatSettings
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.chat.REASONING_LEVELS
import lab.dawnforge.hermes.core.data.settings.ModelVisibility
import lab.dawnforge.hermes.core.ui.failureText

/** What the model picker can ask of its view model. */
internal class ModelActions(
    val onOpen: () -> Unit,
    val onClose: () -> Unit,
    val onPickModel: (provider: String, model: String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancelConfirm: () -> Unit,
    val onPickReasoning: (String) -> Unit,
    val onFast: (Boolean) -> Unit,
    val onYolo: (Boolean) -> Unit,
    val onEdit: (Boolean) -> Unit,
    val onShowProvider: (provider: String, show: Boolean) -> Unit,
    val onShowModel: (provider: String, model: String, show: Boolean) -> Unit,
    val onShowAll: () -> Unit,
)

/** "glm-5.3-flash · high": the chat's model, with its reasoning when it isn't the provider's default. */
@Composable
internal fun modelLabel(model: ChatModel): String = when (model.reasoning) {
    "" -> model.model
    "none" -> stringResource(R.string.feature_chat_model_no_thinking, model.model)
    else -> stringResource(R.string.feature_chat_model_with_reasoning, model.model, model.reasoning)
}

/**
 * The chat's model and reasoning, switched for this chat only. A model picked while a turn runs
 * starts with the next turn; reasoning applies at once.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelSheet(picker: ModelPicker, current: ChatModel?, settings: ChatSettings?, actions: ModelActions) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = actions.onClose) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.feature_chat_model_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (picker.switching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (picker.choices != null) {
                        TextButton(onClick = { actions.onEdit(!picker.editing) }) {
                            val label = if (picker.editing) {
                                R.string.feature_chat_model_done
                            } else {
                                R.string.feature_chat_model_edit
                            }
                            Text(stringResource(label))
                        }
                    }
                }
            }
            val choices = picker.choices
            val model = current?.let { choices?.model(it.provider, it.model) }
            if (choices != null && picker.editing) {
                visibilityRows(choices, picker.visibility, query, onQuery = { query = it }, actions)
                return@LazyColumn
            }
            if (settings != null) {
                item {
                    ChatSwitches(settings, fastOffered = model?.fast == true, switching = picker.switching, actions)
                }
            }
            if (current != null && model?.reasons != false) {
                item {
                    Text(
                        stringResource(R.string.feature_chat_reasoning_level),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val levels = REASONING_LEVELS.filter { it != "none" || model?.canDisableReasoning != false }
                        for (level in levels) {
                            FilterChip(
                                selected = current.reasoning == level,
                                onClick = { actions.onPickReasoning(level) },
                                enabled = !picker.switching,
                                label = { Text(reasoningLabel(level)) },
                            )
                        }
                    }
                }
            }
            when {
                picker.failure != null -> item {
                    Text(
                        failureText(picker.failure),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                choices == null -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                // The chat's own provider first: most switches stay with it.
                else -> {
                    item(key = "search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.feature_chat_model_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                    val wanted = query.trim()
                    val visibility = picker.visibility

                    // The chat's own model always shows, hidden or not.
                    fun isCurrent(provider: String, id: String) =
                        current?.model == id && (current.provider == null || current.provider == provider)
                    val visible = choices.providers.mapNotNull { provider ->
                        val models = provider.models.filter {
                            visibility.shows(provider.slug, it.id) || isCurrent(provider.slug, it.id)
                        }
                        if (models.isEmpty()) null else provider.copy(models = models)
                    }
                    if (visible.isEmpty() && choices.providers.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.feature_chat_model_all_hidden),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    val shown = visible.sortedByDescending {
                        it.slug == current?.provider
                    }.mapNotNull { provider ->
                        val providerMatches = wanted.isEmpty() || provider.name.contains(wanted, ignoreCase = true) ||
                            provider.slug.contains(wanted, ignoreCase = true)
                        val models = if (providerMatches) {
                            provider.models
                        } else {
                            provider.models.filter { it.id.contains(wanted, ignoreCase = true) }
                        }
                        if (models.isEmpty()) null else provider.copy(models = models)
                    }
                    if (shown.isEmpty() && visible.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.feature_chat_model_no_match),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    modelRows(shown, current, picker, actions)
                }
            }
        }
    }
    picker.confirm?.let { confirm -> ConfirmDialog(confirm, actions) }
}

/** The chat's fast mode (where its model has one) and its approvals. */
@Composable
private fun ChatSwitches(settings: ChatSettings, fastOffered: Boolean, switching: Boolean, actions: ModelActions) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        if (fastOffered || settings.fast) {
            SwitchRow(
                title = stringResource(R.string.feature_chat_fast),
                detail = stringResource(R.string.feature_chat_fast_detail),
                checked = settings.fast,
                enabled = !switching,
                onChange = actions.onFast,
            )
        }
        SwitchRow(
            title = stringResource(R.string.feature_chat_yolo),
            detail = stringResource(
                if (settings.yoloLocked) R.string.feature_chat_yolo_locked else R.string.feature_chat_yolo_detail,
            ),
            checked = settings.yolo,
            enabled = !switching && !settings.yoloLocked,
            onChange = actions.onYolo,
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Which providers and models the picker shows, edited on this phone. */
private fun LazyListScope.visibilityRows(
    choices: ModelChoices,
    visibility: ModelVisibility,
    query: String,
    onQuery: (String) -> Unit,
    actions: ModelActions,
) {
    item(key = "visibility-search") {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.feature_chat_model_visibility_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.feature_chat_model_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            TextButton(onClick = actions.onShowAll, enabled = visibility != ModelVisibility()) {
                Text(stringResource(R.string.feature_chat_model_show_all))
            }
        }
    }
    val wanted = query.trim()
    for (provider in choices.providers) {
        val providerMatches = wanted.isEmpty() || provider.name.contains(wanted, ignoreCase = true) ||
            provider.slug.contains(wanted, ignoreCase = true)
        val models = if (providerMatches) {
            provider.models
        } else {
            provider.models.filter {
                it.id.contains(wanted, ignoreCase = true)
            }
        }
        if (models.isEmpty()) continue
        val shown = visibility.shows(provider.slug)
        item(key = "visibility:${provider.slug}") {
            val label = stringResource(R.string.feature_chat_model_show_provider, provider.name)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .toggleable(shown, role = Role.Switch) { actions.onShowProvider(provider.slug, it) }
                    .semantics { contentDescription = label }
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp),
            ) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = shown, onCheckedChange = null)
            }
        }
        if (!shown) continue
        item(key = "visibility-models:${provider.slug}") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val listed = if (expanded || wanted.isNotEmpty()) models else models.take(MODELS_BEFORE_MORE)
            Column {
                listed.forEach { entry ->
                    val on = visibility.shows(provider.slug, entry.id)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(on, role = Role.Checkbox) { actions.onShowModel(provider.slug, entry.id, it) }
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(
                            entry.id,
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (listed.size < models.size) {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(stringResource(R.string.feature_chat_model_show_more, models.size - listed.size))
                    }
                }
            }
        }
    }
}

private const val MODELS_BEFORE_MORE = 8

private fun LazyListScope.modelRows(
    providers: List<ModelChoices.Provider>,
    current: ChatModel?,
    picker: ModelPicker,
    actions: ModelActions,
) {
    for (provider in providers) {
        item(key = "provider:${provider.slug}") {
            Text(
                provider.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(provider.models, key = { "model:${provider.slug}:${it.id}" }) { entry ->
            val selected =
                current?.model == entry.id &&
                    (current.provider == null || current.provider == provider.slug)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !picker.switching && !selected) {
                        actions.onPickModel(provider.slug, entry.id)
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.id, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = stringResource(R.string.feature_chat_model_current),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(confirm: ModelPicker.Confirm, actions: ModelActions) {
    AlertDialog(
        onDismissRequest = actions.onCancelConfirm,
        title = { Text(stringResource(R.string.feature_chat_model_confirm_title, confirm.model)) },
        text = { Text(confirm.message ?: stringResource(R.string.feature_chat_model_confirm_body)) },
        confirmButton = {
            TextButton(onClick = actions.onConfirm) { Text(stringResource(R.string.feature_chat_model_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelConfirm) {
                Text(stringResource(R.string.feature_chat_model_cancel))
            }
        },
    )
}

@Composable
private fun reasoningLabel(level: String): String = when (level) {
    "none" -> stringResource(R.string.feature_chat_reasoning_none)
    "minimal" -> stringResource(R.string.feature_chat_reasoning_minimal)
    "low" -> stringResource(R.string.feature_chat_reasoning_low)
    "medium" -> stringResource(R.string.feature_chat_reasoning_medium)
    "high" -> stringResource(R.string.feature_chat_reasoning_high)
    "xhigh" -> stringResource(R.string.feature_chat_reasoning_xhigh)
    "max" -> stringResource(R.string.feature_chat_reasoning_max)
    else -> stringResource(R.string.feature_chat_reasoning_ultra)
}
