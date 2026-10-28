package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.settings.AgentPlugin
import lab.dawnforge.hermes.core.data.settings.CatalogPlugin
import lab.dawnforge.hermes.core.data.settings.CatalogSkill
import lab.dawnforge.hermes.core.data.settings.McpCatalogEntry
import lab.dawnforge.hermes.core.data.settings.Skill
import lab.dawnforge.hermes.core.data.settings.SkillOrigin
import lab.dawnforge.hermes.core.data.settings.Toolset
import lab.dawnforge.hermes.core.data.settings.prettyCategory
import lab.dawnforge.hermes.core.data.settings.usageLabel
import lab.dawnforge.hermes.core.ui.failureText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel,
    connectorsModel: ConnectorsViewModel,
    pluginsModel: PluginsViewModel,
    onBack: () -> Unit,
    onOpenSkill: (String) -> Unit,
    onOpenCatalogSkill: (CatalogSkill) -> Unit,
    onOpenToolset: (String) -> Unit,
    onOpenConnector: (String) -> Unit,
    onOpenGateway: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connectors by connectorsModel.state.collectAsStateWithLifecycle()
    val plugins by pluginsModel.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Each time it shows, so a change made on a detail page is listed; a tab's own list loads
    // when it is first shown, and again on return while it shows.
    LaunchedEffect(viewModel, lifecycle, state.tab) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            when (state.tab) {
                CapabilitiesTab.Skills, CapabilitiesTab.Tools -> viewModel.refresh()
                CapabilitiesTab.Connectors -> connectorsModel.refresh()
                CapabilitiesTab.Plugins -> pluginsModel.refresh()
            }
        }
    }
    var installing by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var installingPlugin by remember { mutableStateOf<CatalogPlugin?>(null) }
    var uninstalling by remember { mutableStateOf<AgentPlugin?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val connectorMessage = connectors.message?.let { message ->
        stringResource(
            message.what,
            message.subject,
            message.failure?.let { failureText(it) }
                ?: message.detail.ifEmpty { stringResource(R.string.feature_settings_install_unfinished) },
        )
    }
    LaunchedEffect(connectors.message) {
        if (connectorMessage != null) {
            snackbar.showSnackbar(connectorMessage)
            connectorsModel.messageShown()
        }
    }
    val pluginNote = plugins.note?.let { pluginNoteText(it) }
    LaunchedEffect(plugins.note) {
        if (pluginNote != null) {
            snackbar.showSnackbar(pluginNote)
            pluginsModel.noteShown()
        }
    }
    val toggleFailure = state.toggleFailure
    val failureMessage = toggleFailure?.let {
        stringResource(R.string.feature_settings_skill_failed, it.name, failureText(it.failure))
    }
    LaunchedEffect(toggleFailure) {
        if (failureMessage != null) {
            snackbar.showSnackbar(failureMessage)
            viewModel.toggleFailureShown()
        }
    }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.feature_settings_capabilities))
                            state.profile?.let {
                                Text(
                                    stringResource(R.string.feature_settings_profile, it),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                        }
                    },
                    actions = {
                        if (state.tab == CapabilitiesTab.Skills ||
                            state.tab == CapabilitiesTab.Tools
                        ) {
                            OptionsMenu(state, viewModel)
                        }
                    },
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::search,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = if (state.query.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(Icons.Filled.Clear, stringResource(R.string.feature_settings_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text(stringResource(searchHint(state.tab))) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                // Scrollable, so four titles fit at a large text size.
                PrimaryScrollableTabRow(selectedTabIndex = state.tab.ordinal, edgePadding = 8.dp) {
                    CapabilitiesTab.entries.forEach { tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { viewModel.show(tab) },
                            text = {
                                Text(
                                    stringResource(tabTitle(tab)) + countFor(state, connectors, plugins, tab),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        installing?.let { entry ->
            InstallServerDialog(
                entry,
                onInstall = { keys ->
                    installing = null
                    connectorsModel.install(entry, keys)
                },
                onDismiss = { installing = null },
            )
        }
        installingPlugin?.let { entry ->
            ConfirmDialog(
                title = stringResource(R.string.feature_settings_plugin_install_title, entry.title),
                message = entry.summary,
                confirm = stringResource(R.string.feature_settings_install),
                onConfirm = {
                    installingPlugin = null
                    pluginsModel.install(entry)
                },
                onDismiss = { installingPlugin = null },
            )
        }
        uninstalling?.let { plugin ->
            ConfirmDialog(
                title = stringResource(R.string.feature_settings_plugin_uninstall_title, plugin.name),
                message = stringResource(R.string.feature_settings_plugin_uninstall_message),
                confirm = stringResource(R.string.feature_settings_plugin_uninstall),
                onConfirm = {
                    uninstalling = null
                    pluginsModel.uninstall(plugin)
                },
                onDismiss = { uninstalling = null },
            )
        }
        plugins.consent?.let { (plugin, lines) ->
            ConfirmDialog(
                title = stringResource(R.string.feature_settings_plugin_more_title, plugin.name),
                message = stringResource(R.string.feature_settings_plugin_more_message, lines.joinToString("\n")),
                confirm = stringResource(R.string.feature_settings_plugin_apply),
                onConfirm = pluginsModel::acceptConsent,
                onDismiss = pluginsModel::dismissConsent,
            )
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = {
                when (state.tab) {
                    CapabilitiesTab.Connectors -> connectorsModel.refresh(retest = true)
                    CapabilitiesTab.Plugins -> pluginsModel.refresh()
                    else -> viewModel.refresh()
                }
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val failure = state.failure
            when {
                state.tab == CapabilitiesTab.Connectors -> LazyColumn(Modifier.fillMaxSize()) {
                    connectorRows(
                        connectors,
                        state.query,
                        onToggle = connectorsModel::setEnabled,
                        onOpen = onOpenConnector,
                        onInstall = { entry ->
                            if (entry.keys.isEmpty()) {
                                connectorsModel.install(entry, emptyMap())
                            } else {
                                installing =
                                    entry
                            }
                        },
                    )
                }

                state.tab == CapabilitiesTab.Plugins -> LazyColumn(Modifier.fillMaxSize()) {
                    pluginRows(
                        plugins,
                        state.query,
                        onToggle = pluginsModel::setEnabled,
                        onUpdate = { pluginsModel.update(it) },
                        onUninstall = { uninstalling = it },
                        onInstall = { installingPlugin = it },
                        onRestart = onOpenGateway,
                    )
                }

                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                failure != null && state.skills.isEmpty() && state.toolsets.isEmpty() -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                ) {
                    Text(
                        stringResource(R.string.feature_settings_capabilities_failed, failureText(failure)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = viewModel::refresh) { Text(stringResource(R.string.feature_settings_retry)) }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    failure?.let {
                        item {
                            Banner(
                                stringResource(R.string.feature_settings_capabilities_refresh_failed, failureText(it)),
                            )
                        }
                    }
                    if (state.tab == CapabilitiesTab.Skills) {
                        skillRows(state, viewModel, onOpenSkill, onOpenCatalogSkill)
                    } else {
                        toolRows(state, viewModel, onOpenToolset)
                    }
                }
            }
        }
    }
}

/** While searching, how many of each loaded tab's rows match, so a hit on another tab shows. */
private fun countFor(
    state: CapabilitiesUiState,
    connectors: ConnectorsUiState,
    plugins: PluginsUiState,
    tab: CapabilitiesTab,
): String {
    val query = state.query
    if (query.isBlank()) return ""
    val count = when (tab) {
        CapabilitiesTab.Skills -> state.shownSkills.size + state.shownCatalog.size

        CapabilitiesTab.Tools -> state.shownToolsets.size

        CapabilitiesTab.Connectors -> if (connectors.loading) {
            return ""
        } else {
            connectors.connected(query).size + connectors.turnedOff(query).size + connectors.available(query).size
        }

        CapabilitiesTab.Plugins -> if (plugins.loading) {
            return ""
        } else {
            plugins.installed(query).size +
                plugins.available(query).size
        }
    }
    return " ($count)"
}

private fun tabTitle(tab: CapabilitiesTab): Int = when (tab) {
    CapabilitiesTab.Skills -> R.string.feature_settings_tab_skills
    CapabilitiesTab.Tools -> R.string.feature_settings_tab_tools
    CapabilitiesTab.Connectors -> R.string.feature_settings_tab_connectors
    CapabilitiesTab.Plugins -> R.string.feature_settings_tab_plugins
}

private fun searchHint(tab: CapabilitiesTab): Int = when (tab) {
    CapabilitiesTab.Skills -> R.string.feature_settings_search_skills
    CapabilitiesTab.Tools -> R.string.feature_settings_search_tools
    CapabilitiesTab.Connectors -> R.string.feature_settings_search_connectors
    CapabilitiesTab.Plugins -> R.string.feature_settings_search_plugins
}

@Composable
private fun OptionsMenu(state: CapabilitiesUiState, viewModel: CapabilitiesViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = {
            open = true
        }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.feature_settings_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val skills = state.tab == CapabilitiesTab.Skills
            val mostUsed = if (skills) state.skillsMostUsedFirst else state.toolsMostUsedFirst
            listOf(
                true to R.string.feature_settings_most_used_first,
                false to R.string.feature_settings_least_used_first,
            )
                .forEach { (most, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { RadioButton(selected = mostUsed == most, onClick = null) },
                        onClick = {
                            open = false
                            if (skills) viewModel.sortSkills(most) else viewModel.sortTools(most)
                        },
                    )
                }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_settings_turn_all_on)) },
                onClick = {
                    open = false
                    if (skills) viewModel.setAllSkills(true) else viewModel.setAllToolsets(true)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_settings_turn_all_off)) },
                onClick = {
                    open = false
                    if (skills) viewModel.setAllSkills(false) else viewModel.setAllToolsets(false)
                },
            )
            if (skills) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feature_settings_turn_off_unused)) },
                    onClick = {
                        open = false
                        viewModel.setAllSkills(false, unusedOnly = true)
                    },
                )
            }
        }
    }
}

private fun LazyListScope.skillRows(
    state: CapabilitiesUiState,
    viewModel: CapabilitiesViewModel,
    onOpenSkill: (String) -> Unit,
    onOpenCatalogSkill: (CatalogSkill) -> Unit,
) {
    val skills = state.shownSkills
    val catalog = state.shownCatalog
    item {
        CardHeader(
            stringResource(R.string.feature_settings_installed),
            stringResource(R.string.feature_settings_new_sessions),
        )
    }
    if (skills.isEmpty()) {
        item {
            Empty(
                if (state.query.isBlank()) {
                    stringResource(R.string.feature_settings_skills_none)
                } else {
                    stringResource(R.string.feature_settings_nothing_matches, state.query.trim())
                },
            )
        }
    }
    items(skills, key = { "skill:" + it.name }) { skill ->
        CapabilityRow(
            title = skill.name,
            subtitle = listOfNotNull(prettyCategory(skill.category), originLabel(skill.origin)).joinToString(" · "),
            detail = skill.description,
            meta = skill.usage.takeIf { it > 0 }?.let(::usageLabel),
            enabled = skill.enabled,
            onToggle = { viewModel.setSkill(skill.name, it) },
            onOpen = { onOpenSkill(skill.name) },
        )
    }
    if (catalog.isNotEmpty()) {
        item {
            CardHeader(
                stringResource(R.string.feature_settings_skills_available),
                stringResource(R.string.feature_settings_skills_available_note),
            )
        }
        items(catalog, key = { "catalog:" + it.identifier }) { skill ->
            ListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = {
                    Column {
                        Text(
                            prettyCategory(skill.category),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(skill.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                },
                modifier = Modifier.clickable { onOpenCatalogSkill(skill) },
            )
        }
    }
}

@Composable
private fun originLabel(origin: SkillOrigin): String? = when (origin) {
    SkillOrigin.Agent -> stringResource(R.string.feature_settings_skill_learned)
    SkillOrigin.Hub -> stringResource(R.string.feature_settings_skill_hub)
    else -> null
}

private fun LazyListScope.toolRows(
    state: CapabilitiesUiState,
    viewModel: CapabilitiesViewModel,
    onOpenToolset: (String) -> Unit,
) {
    val toolsets = state.shownToolsets
    item {
        CardHeader(
            stringResource(R.string.feature_settings_toolsets),
            stringResource(R.string.feature_settings_new_sessions),
        )
    }
    if (toolsets.isEmpty() && state.query.isNotBlank()) {
        item { Empty(stringResource(R.string.feature_settings_nothing_matches, state.query.trim())) }
    }
    items(toolsets, key = { "toolset:" + it.name }) { toolset ->
        val calls = state.calls(toolset)
        CapabilityRow(
            title = toolset.label,
            subtitle = if (toolset.configured) null else stringResource(R.string.feature_settings_needs_keys),
            subtitleIsWarning = true,
            detail = toolset.description,
            meta = if (calls > 0) {
                usageLabel(calls)
            } else {
                pluralStringResource(R.plurals.feature_settings_tool_count, toolset.tools.size, toolset.tools.size)
            },
            enabled = toolset.enabled,
            onToggle = { viewModel.setToolset(toolset.name, it) },
            onOpen = { onOpenToolset(toolset.name) },
        )
    }
}

/** A skill or toolset: its name, a line about it, its use, and its switch; tapping it opens it. */
@Composable
private fun CapabilityRow(
    title: String,
    subtitle: String?,
    detail: String,
    meta: String?,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    subtitleIsWarning: Boolean = false,
) {
    val toggleLabel = stringResource(R.string.feature_settings_skill_toggle, title)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (subtitleIsWarning) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (detail.isNotBlank()) Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                meta?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription =
                            toggleLabel
                    },
                )
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_settings_cancel)) } },
    )
}

@Composable
internal fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
internal fun Banner(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
