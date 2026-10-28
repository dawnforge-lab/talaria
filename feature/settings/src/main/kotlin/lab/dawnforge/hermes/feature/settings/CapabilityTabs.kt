package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lab.dawnforge.hermes.core.data.settings.AgentPlugin
import lab.dawnforge.hermes.core.data.settings.CatalogPlugin
import lab.dawnforge.hermes.core.data.settings.McpCatalogEntry
import lab.dawnforge.hermes.core.data.settings.McpServer
import lab.dawnforge.hermes.core.ui.failureText

internal fun LazyListScope.connectorRows(
    state: ConnectorsUiState,
    query: String,
    onToggle: (McpServer, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onInstall: (McpCatalogEntry) -> Unit,
) {
    val connected = state.connected(query)
    val off = state.turnedOff(query)
    val available = state.available(query)
    if (state.loading) {
        item {
            Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        }
        return
    }
    state.failure?.let {
        item { Banner(stringResource(R.string.feature_settings_capabilities_failed, failureText(it))) }
    }
    if (connected.isEmpty() && off.isEmpty() && available.isEmpty()) {
        item {
            Empty(
                if (query.isBlank()) {
                    stringResource(R.string.feature_settings_connectors_empty)
                } else {
                    stringResource(R.string.feature_settings_nothing_matches, query.trim())
                },
            )
        }
    }
    if (connected.isNotEmpty()) {
        item {
            CardHeader(
                stringResource(R.string.feature_settings_connected),
                stringResource(R.string.feature_settings_connected_note),
            )
        }
        items(connected, key = { "mcp:" + it.name }) { ServerRow(state, it, onToggle, onOpen) }
    }
    if (available.isNotEmpty()) {
        item {
            CardHeader(
                stringResource(R.string.feature_settings_connectors_available),
                stringResource(R.string.feature_settings_connectors_available_note),
            )
        }
        items(available, key = { "mcp-catalog:" + it.name }) { entry ->
            val installing = entry.name in state.installing
            ListItem(
                headlineContent = { Text(entry.name) },
                supportingContent = {
                    Column {
                        val signIn = stringResource(R.string.feature_settings_mcp_sign_in_pill)
                        val tags = listOfNotNull(
                            stringResource(R.string.feature_settings_mcp_catalog),
                            signIn.takeIf { entry.authType == "oauth" },
                        )
                        Text(
                            tags.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(entry.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingContent = {
                    if (installing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            onInstall(entry)
                        }) { Text(stringResource(R.string.feature_settings_install)) }
                    }
                },
            )
        }
    }
    if (off.isNotEmpty()) {
        item {
            CardHeader(
                stringResource(R.string.feature_settings_turned_off),
                stringResource(R.string.feature_settings_turned_off_note),
            )
        }
        items(off, key = { "mcp-off:" + it.name }) { ServerRow(state, it, onToggle, onOpen) }
    }
}

@Composable
private fun ServerRow(
    state: ConnectorsUiState,
    server: McpServer,
    onToggle: (McpServer, Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val serverState = state.state(server)
    val catalog = state.catalog.firstOrNull { it.name == server.name }
    ListItem(
        headlineContent = { Text(server.name) },
        supportingContent = {
            Column {
                Text(
                    when (val plugin = server.plugin) {
                        null -> null
                        else -> stringResource(R.string.feature_settings_mcp_plugin, plugin)
                    } ?: when {
                        catalog != null -> stringResource(R.string.feature_settings_mcp_catalog)
                        else -> stringResource(R.string.feature_settings_mcp_custom)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val detail = when (serverState) {
                    is ServerState.Failed -> serverState.error.ifBlank { null }
                    ServerState.NeedsSignIn -> stringResource(R.string.feature_settings_mcp_sign_in_detail)
                    else -> null
                } ?: catalog?.description ?: server.runs
                detail?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
        leadingContent = { StateDot(serverState) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(serverStateText(serverState), style = MaterialTheme.typography.labelMedium)
                if (server.plugin == null) Switch(checked = server.enabled, onCheckedChange = { onToggle(server, it) })
            }
        },
        modifier = Modifier.clickable { onOpen(server.name) },
    )
}

@Composable
internal fun serverStateText(state: ServerState): String = when (state) {
    ServerState.Off -> stringResource(R.string.feature_settings_mcp_off)

    ServerState.Checking -> stringResource(R.string.feature_settings_mcp_checking)

    ServerState.Unknown -> stringResource(R.string.feature_settings_mcp_on)

    is ServerState.On -> if (state.tools.isEmpty()) {
        stringResource(R.string.feature_settings_mcp_on)
    } else {
        pluralStringResource(R.plurals.feature_settings_mcp_tools, state.tools.size, state.tools.size)
    }

    ServerState.NeedsSignIn -> stringResource(R.string.feature_settings_mcp_needs_sign_in)

    is ServerState.Failed -> stringResource(R.string.feature_settings_mcp_error)
}

/** Grey when off, the theme's colour when on, its third while checking or for a sign-in, red on error. */
@Composable
internal fun StateDot(state: ServerState) {
    val color = when (state) {
        ServerState.Off, ServerState.Unknown -> MaterialTheme.colorScheme.outline
        is ServerState.On -> MaterialTheme.colorScheme.primary
        ServerState.Checking, ServerState.NeedsSignIn -> MaterialTheme.colorScheme.tertiary
        is ServerState.Failed -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Asks for a catalog server's keys, showing what it runs, before installing it. */
@Composable
internal fun InstallServerDialog(
    entry: McpCatalogEntry,
    onInstall: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    val ready = entry.keys.filter { it.required }.all { values[it.name]?.isNotBlank() == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.description.isNotBlank()) Text(entry.description)
                entry.runs?.let {
                    Text(
                        stringResource(R.string.feature_settings_mcp_runs),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.feature_settings_mcp_runs_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entry.keys.isNotEmpty()) {
                    Text(
                        stringResource(R.string.feature_settings_mcp_keys),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    entry.keys.forEach { key ->
                        OutlinedTextField(
                            value = values[key.name].orEmpty(),
                            onValueChange = { values[key.name] = it },
                            singleLine = true,
                            label = {
                                val label = if (key.required) {
                                    key.prompt
                                } else {
                                    stringResource(R.string.feature_settings_mcp_key_optional, key.prompt)
                                }
                                Text(label)
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                autoCorrectEnabled = false,
                            ),
                        )
                    }
                    Text(
                        stringResource(R.string.feature_settings_mcp_keys_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInstall(values.toMap())
            }, enabled = ready) { Text(stringResource(R.string.feature_settings_install)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

internal fun LazyListScope.pluginRows(
    state: PluginsUiState,
    query: String,
    onToggle: (AgentPlugin, Boolean) -> Unit,
    onUpdate: (AgentPlugin) -> Unit,
    onUninstall: (AgentPlugin) -> Unit,
    onInstall: (CatalogPlugin) -> Unit,
    onRestart: () -> Unit,
) {
    if (state.loading) {
        item {
            Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        }
        return
    }
    state.failure?.let {
        item { Banner(stringResource(R.string.feature_settings_capabilities_failed, failureText(it))) }
    }
    if (!state.canManage) item { Empty(stringResource(R.string.feature_settings_plugins_default_only)) }
    if (state.needsRestart) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.feature_settings_plugin_restart),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                OutlinedButton(onClick = onRestart) {
                    Text(stringResource(R.string.feature_settings_plugin_open_gateway))
                }
            }
        }
    }
    val installed = state.installed(query)
    item {
        CardHeader(
            stringResource(R.string.feature_settings_installed),
            stringResource(R.string.feature_settings_plugins_installed_note),
        )
    }
    if (installed.isEmpty()) {
        item {
            Empty(
                if (query.isBlank()) {
                    stringResource(R.string.feature_settings_plugins_none)
                } else {
                    stringResource(R.string.feature_settings_nothing_matches, query.trim())
                },
            )
        }
    }
    items(installed, key = { "plugin:" + it.name }) { plugin ->
        PluginRow(state, plugin, onToggle, onUpdate, onUninstall)
    }
    val available = state.available(query)
    if (available.isNotEmpty() && state.canManage) {
        item {
            CardHeader(
                stringResource(R.string.feature_settings_plugin_catalog),
                stringResource(R.string.feature_settings_plugin_catalog_note),
            )
        }
        items(available, key = { "plugin-catalog:" + it.name }) { entry ->
            val busy = entry.name in state.busy
            ListItem(
                headlineContent = { Text(entry.title) },
                supportingContent = {
                    Column {
                        Text(
                            tierLabel(entry.tier),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(entry.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                trailingContent = {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            onInstall(entry)
                        }) { Text(stringResource(R.string.feature_settings_install)) }
                    }
                },
            )
        }
    }
}

@Composable
private fun PluginRow(
    state: PluginsUiState,
    plugin: AgentPlugin,
    onToggle: (AgentPlugin, Boolean) -> Unit,
    onUpdate: (AgentPlugin) -> Unit,
    onUninstall: (AgentPlugin) -> Unit,
) {
    val entry = state.catalogEntry(plugin)
    val busy = plugin.name in state.busy
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(plugin.name) },
        supportingContent = {
            Column {
                val updateLabel = stringResource(R.string.feature_settings_plugin_update_available)
                val signInLabel = stringResource(R.string.feature_settings_plugin_needs_sign_in)
                val tags = listOfNotNull(
                    plugin.version.takeIf(String::isNotBlank)?.let { "v$it" },
                    entry?.let { tierLabel(it.tier) } ?: sourceLabel(plugin.source),
                    updateLabel.takeIf { entry?.updateAvailable == true },
                    signInLabel.takeIf { plugin.signInCommand != null },
                )
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (plugin.description.isNotBlank()) {
                    Text(plugin.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                plugin.signInCommand?.let {
                    Text(
                        stringResource(R.string.feature_settings_plugin_sign_in_command, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Switch(checked = plugin.enabled, onCheckedChange = {
                        onToggle(plugin, it)
                    }, enabled = state.canManage)
                }
                if (state.canManage && (plugin.canUpdate || plugin.canRemove)) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_settings_options))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (entry?.updateAvailable == true) {
                                                R.string.feature_settings_plugin_update
                                            } else {
                                                R.string.feature_settings_plugin_check_update
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    menu = false
                                    onUpdate(plugin)
                                },
                            )
                            if (plugin.canRemove) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.feature_settings_plugin_uninstall)) },
                                    onClick = {
                                        menu = false
                                        onUninstall(plugin)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun tierLabel(tier: String): String = stringResource(
    if (tier == "official") R.string.feature_settings_plugin_official else R.string.feature_settings_plugin_community,
)

@Composable
private fun sourceLabel(source: String): String = stringResource(
    when (source) {
        "bundled" -> R.string.feature_settings_plugin_builtin
        "git" -> R.string.feature_settings_plugin_git
        else -> R.string.feature_settings_plugin_local
    },
)

/** The plugin note a change ends with. */
@Composable
internal fun pluginNoteText(note: PluginNote): String = when (note) {
    is PluginNote.UpToDate -> stringResource(R.string.feature_settings_plugin_up_to_date, note.name)

    is PluginNote.Updated -> stringResource(
        if (note.restart) {
            R.string.feature_settings_plugin_updated_restart
        } else {
            R.string.feature_settings_plugin_updated
        },
        note.name,
    )

    is PluginNote.Installed -> when {
        note.missingKeys.isNotEmpty() ->
            stringResource(
                R.string.feature_settings_plugin_installed_keys,
                note.name,
                note.missingKeys.joinToString(", "),
            )

        note.restart -> stringResource(R.string.feature_settings_plugin_installed_restart, note.name)

        else -> stringResource(R.string.feature_settings_plugin_installed_live, note.name)
    }

    is PluginNote.Failed -> stringResource(
        R.string.feature_settings_plugin_failed,
        note.name,
        failureText(note.failure),
    )
}
