package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.chat.REASONING_LEVELS
import lab.dawnforge.hermes.core.ui.failureText

/** The default model, its reasoning, and Hermes' question before an expensive one. */
internal fun LazyListScope.defaultModelCard(state: HermesPageUiState, viewModel: HermesPageViewModel) {
    item(key = "default-model") {
        val defaults = state.defaults
        val providers = state.catalog?.choices?.providers.orEmpty()
        Column {
            CardHeader(
                stringResource(R.string.feature_settings_default_model),
                stringResource(R.string.feature_settings_default_model_help),
            )
            CatalogFailure(state)
            if (defaults != null) {
                val provider = providers.firstOrNull { it.slug == defaults.provider }
                ProviderPicker(defaults.provider, providers, inherit = null) { viewModel.pickDefault(provider = it) }
                ModelPicker(defaults.model, provider?.models.orEmpty(), inherit = null) {
                    viewModel.pickDefault(model = it)
                }
                Picker(
                    stringResource(R.string.feature_settings_reasoning),
                    defaults.reasoning,
                    REASONING_LEVELS.map { it to it },
                ) { viewModel.pickDefault(reasoning = it) }
                val complete = defaults.provider.isNotBlank() && defaults.model.isNotBlank()
                SaveButton(
                    stringResource(R.string.feature_settings_save_model),
                    enabled = state.defaultsChanged && complete && state.defaultsSave != SaveState.Saving,
                    save = state.defaultsSave,
                    label = stringResource(R.string.feature_settings_default_model),
                    onClick = { viewModel.saveDefaults() },
                )
            }
        }
        state.expensive?.let { message ->
            AlertDialog(
                onDismissRequest = viewModel::dismissExpensive,
                title = { Text(stringResource(R.string.feature_settings_expensive_title)) },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveDefaults(confirmExpensive = true) }) {
                        Text(stringResource(R.string.feature_settings_expensive_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissExpensive) {
                        Text(stringResource(R.string.feature_settings_cancel))
                    }
                },
            )
        }
    }
}

/** The models Hermes falls back to, in order. */
internal fun LazyListScope.fallbacksCard(state: HermesPageUiState, viewModel: HermesPageViewModel) {
    item(key = "fallbacks") {
        val providers = state.catalog?.choices?.providers.orEmpty()
        Column {
            CardHeader(
                stringResource(R.string.feature_settings_fallbacks),
                stringResource(R.string.feature_settings_fallbacks_help),
            )
            if (state.fallbacks.isEmpty()) {
                Text(
                    stringResource(R.string.feature_settings_fallbacks_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            state.fallbacks.forEachIndexed { index, entry ->
                FallbackHeader(index, state.fallbacks.size, viewModel)
                ProviderPicker(entry.provider, providers, inherit = null) {
                    viewModel.setFallback(index, provider = it)
                }
                ModelPicker(
                    entry.model,
                    providers.firstOrNull {
                        it.slug == entry.provider
                    }?.models.orEmpty(),
                    inherit = null,
                ) {
                    viewModel.setFallback(index, model = it)
                }
            }
            OutlinedButton(
                onClick = viewModel::addFallback,
                enabled = providers.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.feature_settings_fallback_add)) }
            val incomplete = state.fallbacks.any { !it.complete }
            if (incomplete) {
                Text(
                    stringResource(R.string.feature_settings_fallback_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            SaveButton(
                stringResource(R.string.feature_settings_fallback_save),
                enabled = state.fallbacksChanged && !incomplete && state.fallbacksSave != SaveState.Saving,
                save = state.fallbacksSave,
                label = stringResource(R.string.feature_settings_fallbacks),
                onClick = viewModel::saveFallbacks,
            )
        }
    }
}

@Composable
private fun FallbackHeader(index: Int, count: Int, viewModel: HermesPageViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
    ) {
        Text(
            stringResource(R.string.feature_settings_fallback, index + 1),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_settings_fallback_more, index + 1))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (index > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_settings_fallback_up)) },
                        onClick = {
                            menu = false
                            viewModel.moveFallback(index, -1)
                        },
                    )
                }
                if (index < count - 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_settings_fallback_down)) },
                        onClick = {
                            menu = false
                            viewModel.moveFallback(index, 1)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_settings_fallback_remove)) },
                    onClick = {
                        menu = false
                        viewModel.removeFallback(index)
                    },
                )
            }
        }
    }
}

/** The model delegate agents use, and pausing new ones. */
internal fun LazyListScope.delegationCards(state: HermesPageUiState, viewModel: HermesPageViewModel) {
    item(key = "delegate-model") {
        val providers = state.catalog?.choices?.providers.orEmpty()
        val delegate = state.delegate
        val inherit = stringResource(R.string.feature_settings_inherit_provider)
        Column {
            CardHeader(
                stringResource(R.string.feature_settings_delegate_model),
                stringResource(R.string.feature_settings_delegate_model_help),
            )
            CatalogFailure(state)
            ProviderPicker(delegate.provider, providers, inherit = inherit) { viewModel.pickDelegate(provider = it) }
            if (delegate.provider.isNotBlank() || delegate.model.isNotBlank()) {
                val models = providers.firstOrNull { it.slug == delegate.provider }?.models
                    ?: providers.flatMap { it.models }.distinctBy { it.id }
                ModelPicker(delegate.model, models, inherit = stringResource(R.string.feature_settings_inherit_model)) {
                    viewModel.pickDelegate(model = it)
                }
            }
            val sameReasoning = stringResource(R.string.feature_settings_inherit_reasoning)
            Picker(
                stringResource(R.string.feature_settings_delegate_reasoning),
                delegate.reasoning,
                listOf("" to sameReasoning) + DELEGATE_REASONING.map { it to it },
            ) { viewModel.pickDelegate(reasoning = it) }
            SaveButton(
                stringResource(R.string.feature_settings_save_delegate),
                enabled = state.delegateChanged && state.delegateSave != SaveState.Saving,
                save = state.delegateSave,
                label = stringResource(R.string.feature_settings_delegate_model),
                onClick = viewModel::saveDelegate,
            )
        }
    }
    item(key = "pause-delegation") {
        val paused = state.delegationPaused
        val label = stringResource(R.string.feature_settings_pause_delegation)
        if (paused == null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.feature_settings_pause_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_settings_retry)) }
            }
        } else {
            val saving = state.pauseSave == SaveState.Saving
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(R.string.feature_settings_pause_delegation_help),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SaveLine(state.pauseSave, label)
                    }
                },
                trailingContent = { Switch(checked = paused, onCheckedChange = null, enabled = !saving) },
                modifier = Modifier.padding(top = 16.dp)
                    .toggleable(
                        paused,
                        enabled = !saving,
                        role = Role.Switch,
                        onValueChange = viewModel::pauseDelegation,
                    ),
            )
        }
    }
}

@Composable
private fun CatalogFailure(state: HermesPageUiState) {
    val failure = state.catalogFailure ?: return
    Text(
        stringResource(R.string.feature_settings_models_failed, failureText(failure)),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A provider, by its name; [inherit] names the blank choice, when there is one. */
@Composable
private fun ProviderPicker(
    slug: String,
    providers: List<ModelChoices.Provider>,
    inherit: String?,
    onPick: (String) -> Unit,
) {
    val options = providers.map { it.slug to it.name }
    Picker(
        stringResource(R.string.feature_settings_provider),
        slug,
        (if (inherit != null) listOf("" to inherit) else emptyList()) + options,
        onPick,
    )
}

@Composable
private fun ModelPicker(id: String, models: List<ModelChoices.Model>, inherit: String?, onPick: (String) -> Unit) {
    Picker(
        stringResource(R.string.feature_settings_model),
        id,
        (if (inherit != null) listOf("" to inherit) else emptyList()) + models.map { it.id to it.id },
        onPick,
    )
}

/**
 * A row showing [value]'s label among [options] (value to label), which opens a dialog to pick
 * another. A value not among them (a provider this phone can't list) is shown, and kept, as it is.
 */
@Composable
private fun Picker(title: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second
        ?: value.ifEmpty { stringResource(R.string.feature_settings_model_none) }
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Text(
                shown,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.55f),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.clickable(enabled = options.isNotEmpty()) { picking = true },
    )
    if (picking) {
        ChoiceDialog(
            title = title,
            options = options,
            selected = value,
            onPick = {
                picking = false
                onPick(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun SaveButton(text: String, enabled: Boolean, save: SaveState?, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Button(onClick = onClick, enabled = enabled) { Text(text) }
        SaveLine(save, label)
    }
}
