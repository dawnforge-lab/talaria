package lab.dawnforge.hermes.feature.sessions

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.cache.UnsentNewChat
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.LiveStatus
import lab.dawnforge.hermes.core.model.SessionSearchHit
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.core.model.parseSnippet
import lab.dawnforge.hermes.core.ui.BotAvatar
import lab.dawnforge.hermes.core.ui.LiveConnectionLine
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.core.ui.profileGlowBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsScreen(
    viewModel: SessionsViewModel,
    onOpenChat: (ChatTarget) -> Unit,
    onOpenDashboards: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCron: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenBots: () -> Unit,
    onOpenArtifacts: (profile: String) -> Unit,
    onOpenProjects: (profile: String) -> Unit,
    onOpenPlugin: () -> Unit,
    onRepairConnection: () -> Unit,
    onOpenProfiles: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val openChat by rememberUpdatedState(onOpenChat)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event -> if (event is SessionsEvent.OpenChat) openChat(event.target) }
        }
    }
    // Running and needs-approval states, read while the list shows.
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.shown()
            // Back from the plugin or notification pages: setup may be done.
            viewModel.refreshSetup()
            viewModel.watchLiveStatuses()
        }
    }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopBar(
                state = state,
                onSelectProfile = viewModel::selectProfile,
                onManageProfiles = onOpenProfiles,
                onShowArchive = viewModel::showArchive,
                onOpenDashboards = onOpenDashboards,
                onOpenNotifications = onOpenNotifications,
                onOpenCron = onOpenCron,
                onOpenSettings = onOpenSettings,
                onOpenTasks = onOpenTasks,
                onOpenBots = onOpenBots,
                onOpenArtifacts = { onOpenArtifacts(state.profile) },
                onOpenProjects = { onOpenProjects(state.profile) },
                filterActions = FilterActions(
                    onSet = viewModel::setFilter,
                    onClear = viewModel::clearFilters,
                    onMarkAllRead = viewModel::markAllRead,
                ),
            )
        },
        floatingActionButton = {
            if (!state.archived) {
                val label = stringResource(R.string.feature_sessions_new_chat)
                ExtendedFloatingActionButton(
                    onClick = viewModel::newChat,
                    // The button's own text did not reach the accessibility tree on the emulator
                    // (uiautomator showed an unlabelled node), so it is named explicitly.
                    modifier = Modifier.semantics { contentDescription = label },
                    icon = {
                        if (state.startingChat) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    },
                    text = { Text(label) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SearchField(state.query, viewModel::onQueryChange)
            if (!state.archived && !state.filters.isEmpty && state.results == null) {
                FilterLine(state.filters, onClear = viewModel::clearFilters)
            }
            if (state.liveHeld != null ||
                (state.live !is GatewayState.Connected && state.live != GatewayState.Stopped)
            ) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    LiveConnectionLine(
                        state.live,
                        held = state.liveHeld,
                        onRepair = onRepairConnection,
                        onReconnect = viewModel::reconnectAnyway,
                    )
                }
            }
            if (state.savedCopy) {
                Text(
                    stringResource(R.string.feature_sessions_saved_copy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            val setup = state.finishSetup
            if (setup != null && !state.archived && state.results == null) {
                FinishSetupCard(
                    setup = setup,
                    onOpenPlugin = onOpenPlugin,
                    onOpenNotifications = onOpenNotifications,
                    onDismiss = viewModel::dismissSetup,
                )
            } else if (state.pushOffer && !state.archived && state.results == null) {
                PushOffer(onSetUp = onOpenNotifications, onDismiss = viewModel::dismissPushOffer)
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading -> Centered { CircularProgressIndicator() }

                    state.error != null && state.recent.isEmpty() && state.pinned.isEmpty() && state.results == null ->
                        ErrorState(failureText(state.error!!), viewModel::refresh)

                    else -> SessionList(state, viewModel)
                }
            }
        }
    }

    state.renaming?.let { row -> RenameDialog(row, onSave = viewModel::rename, onDismiss = viewModel::dismissDialog) }
    state.moving?.let { row ->
        MoveToProjectDialog(
            chat = row.displayTitle,
            targets = state.moveTargets(row),
            onMove = viewModel::move,
            onDismiss = viewModel::cancelMove,
        )
    }
    state.deleting?.let { row ->
        DeleteDialog(row, onConfirm = viewModel::delete, onDismiss = viewModel::dismissDialog)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: SessionsUiState,
    onSelectProfile: (String) -> Unit,
    onManageProfiles: () -> Unit,
    onShowArchive: (Boolean) -> Unit,
    onOpenDashboards: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCron: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenBots: () -> Unit,
    onOpenArtifacts: () -> Unit,
    onOpenProjects: () -> Unit,
    filterActions: FilterActions,
) {
    var profilesOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.background(profileGlowBrush(state.glow)),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (state.archived) {
                IconButton(onClick = { onShowArchive(false) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.feature_sessions_back),
                    )
                }
            }
        },
        title = {
            if (state.archived) {
                Text(stringResource(R.string.feature_sessions_title_archive))
            } else {
                val description = stringResource(R.string.feature_sessions_switch_profile, state.profileLabel)
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { profilesOpen = true }
                            .semantics { contentDescription = description },
                    ) {
                        Text(state.profileLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = profilesOpen, onDismissRequest = { profilesOpen = false }) {
                        state.profiles.forEach { profile ->
                            val look = state.profileLooks[profile.name]
                            DropdownMenuItem(
                                leadingIcon = {
                                    val color = look?.color ?: BotColor.fallback(profile.name)
                                    BotAvatar(profile.label, color, look?.picture, PROFILE_AVATAR.dp)
                                },
                                text = {
                                    Column {
                                        Text(profile.label)
                                        profile.model?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    profilesOpen = false
                                    onSelectProfile(profile.name)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_sessions_manage_profiles)) },
                            onClick = {
                                profilesOpen = false
                                onManageProfiles()
                            },
                        )
                    }
                }
            }
        },
        actions = {
            if (!state.archived) {
                // Bots are a destination of their own, not a menu item: one tap from the chats.
                IconButton(onClick = onOpenBots) {
                    Icon(Icons.Filled.Face, contentDescription = stringResource(R.string.feature_sessions_show_bots))
                }
                FilterMenu(state, filterActions)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.feature_sessions_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!state.archived) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_sessions_show_archive)) },
                            onClick = {
                                menuOpen = false
                                onShowArchive(true)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_tasks)) },
                        onClick = {
                            menuOpen = false
                            onOpenTasks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_projects)) },
                        onClick = {
                            menuOpen = false
                            onOpenProjects()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_artifacts)) },
                        onClick = {
                            menuOpen = false
                            onOpenArtifacts()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_cron)) },
                        onClick = {
                            menuOpen = false
                            onOpenCron()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_notifications)) },
                        onClick = {
                            menuOpen = false
                            onOpenNotifications()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_dashboards)) },
                        onClick = {
                            menuOpen = false
                            onOpenDashboards()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.feature_sessions_show_settings)) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.feature_sessions_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.feature_sessions_clear_search),
                    )
                }
            }
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The offer after sign-in: notifications are set up per profile, on the Notifications screen. */
@Composable
private fun PushOffer(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.feature_sessions_push_offer_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.feature_sessions_push_offer_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_push_offer_dismiss)) }
                TextButton(onClick = onSetUp) { Text(stringResource(R.string.feature_sessions_push_offer_set_up)) }
            }
        }
    }
}

@Composable
private fun SessionList(state: SessionsUiState, viewModel: SessionsViewModel) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_MORE_AHEAD
        }
    }
    LaunchedEffect(nearEnd, state.recent.size, state.autoLoad) { if (nearEnd && state.autoLoad) viewModel.loadMore() }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        val results = state.results
        if (results != null) {
            if (results.isEmpty() && !state.searching) item { EmptyRow(R.string.feature_sessions_no_results) }
            items(results, key = { "hit:" + it.sessionId }) { hit -> SearchRow(hit, onClick = { viewModel.open(hit) }) }
            if (state.searching) item { LoadingRow() }
            return@LazyColumn
        }
        val unsent = state.unsent.takeUnless { state.archived }.orEmpty()
        if (unsent.isNotEmpty()) {
            item(key = "header:unsent") { UnsentHeader() }
            items(unsent, key = { "unsent:" + it.chatKey }) { chat ->
                UnsentRow(chat, onClick = { viewModel.open(chat) })
                HorizontalDivider()
            }
        }
        if (state.pinned.isEmpty() && state.recent.isEmpty()) {
            val empty = when {
                state.archived -> R.string.feature_sessions_empty_archive
                !state.filters.isEmpty -> R.string.feature_sessions_empty_filtered
                else -> R.string.feature_sessions_empty
            }
            if (unsent.isEmpty()) item { EmptyRow(empty) }
            return@LazyColumn
        }
        if (state.archived) {
            rows(state.recent, state, viewModel)
        } else {
            for (section in state.sections) {
                val collapsed = section.section in state.collapsed
                item(key = "header:${section.section}") {
                    SectionHeader(section.section, collapsed, onToggle = { viewModel.toggleSection(section.section) })
                }
                if (!collapsed) rows(section.rows, state, viewModel)
            }
        }
        when {
            state.loadingMore -> item { LoadingRow() }
            state.endReached && state.recent.size > END_NOTE_AFTER -> item { EmptyRow(R.string.feature_sessions_end) }
        }
    }
}

private fun LazyListScope.rows(rows: List<SessionSummary>, state: SessionsUiState, viewModel: SessionsViewModel) {
    items(rows, key = { it.id }) { row ->
        SessionRow(
            row = row,
            starred = row.id in state.starred,
            live = state.liveStatus(row),
            project = state.projectOf(row)?.title,
            actions = RowActions(
                onClick = { viewModel.open(row) },
                onRename = { viewModel.askRename(row) },
                onTogglePin = { viewModel.togglePin(row) },
                onToggleStar = { viewModel.toggleStar(row) },
                onToggleRead = { viewModel.toggleRead(row) },
                onMove = if (state.moveTargets(row).isNotEmpty()) ({ viewModel.askMove(row) }) else null,
                onToggleArchive = { viewModel.toggleArchive(row) },
                onDelete = { viewModel.askDelete(row) },
            ),
        )
        HorizontalDivider()
    }
}

/** A section's header, which opens and closes it. */
@Composable
private fun SectionHeader(section: ChatListSection, collapsed: Boolean, onToggle: () -> Unit) {
    val state =
        stringResource(
            if (collapsed) R.string.feature_sessions_section_closed else R.string.feature_sessions_section_open,
        )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .semantics {
                heading()
                stateDescription = state
            },
    ) {
        Text(
            sectionTitle(section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun sectionTitle(section: ChatListSection): String = stringResource(
    when (section) {
        ChatListSection.Pinned -> R.string.feature_sessions_pinned
        ChatListSection.Today -> R.string.feature_sessions_section_today
        ChatListSection.Yesterday -> R.string.feature_sessions_section_yesterday
        ChatListSection.LastSevenDays -> R.string.feature_sessions_section_week
        ChatListSection.Older -> R.string.feature_sessions_section_older
    },
)

/** What the filter menu can ask of its view model. */
private class FilterActions(
    val onSet: (ChatFilter, Boolean) -> Unit,
    val onClear: () -> Unit,
    val onMarkAllRead: () -> Unit,
)

/**
 * The filter menu. Filters combine, so the menu stays open while they are picked; All clears them.
 * Its icon carries a dot while a chat waits for an approval or an answer.
 */
@Composable
private fun FilterMenu(state: SessionsUiState, actions: FilterActions) {
    var open by remember { mutableStateOf(false) }
    val description = if (state.waiting > 0) {
        pluralStringResource(R.plurals.feature_sessions_filter_waiting, state.waiting, state.waiting)
    } else {
        stringResource(R.string.feature_sessions_filter)
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
            BadgedBox(badge = { if (state.waiting > 0) Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                Icon(
                    painterResource(R.drawable.feature_sessions_ic_filter),
                    contentDescription = null,
                    tint = if (state.filters.isEmpty) LocalContentColor.current else MaterialTheme.colorScheme.primary,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FilterItem(stringResource(R.string.feature_sessions_filter_all), state.filters.isEmpty, count = null) {
                open = false
                actions.onClear()
            }
            val options = state.filterOptions
            options.firstOrNull { it.filter == ChatFilter.Starred }?.let { FilterToggle(it, state, actions) }
            MenuHeading(R.string.feature_sessions_filter_status)
            options.filter { it.filter.isStatus }.forEach { FilterToggle(it, state, actions) }
            MenuHeading(R.string.feature_sessions_filter_sources)
            options.filter { it.filter.isSource }.forEach { FilterToggle(it, state, actions) }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feature_sessions_mark_all_read)) },
                enabled = state.canMarkAllRead,
                onClick = {
                    open = false
                    actions.onMarkAllRead()
                },
            )
        }
    }
}

@Composable
private fun FilterToggle(option: FilterOption, state: SessionsUiState, actions: FilterActions) {
    val chosen = option.filter in state.filters.chosen
    FilterItem(filterLabel(option.filter), chosen, option.count) { actions.onSet(option.filter, !chosen) }
}

@Composable
private fun FilterItem(label: String, chosen: Boolean, count: Int?, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            if (chosen) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
        },
        trailingIcon = count?.let { { Text(it.toString(), style = MaterialTheme.typography.labelMedium) } },
        onClick = onClick,
        modifier = Modifier.semantics { selected = chosen },
    )
}

@Composable
private fun MenuHeading(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun filterLabel(filter: ChatFilter): String = when (filter) {
    ChatFilter.Starred -> stringResource(R.string.feature_sessions_filter_starred)
    ChatFilter.Unread -> stringResource(R.string.feature_sessions_filter_unread)
    ChatFilter.NeedsApproval -> stringResource(R.string.feature_sessions_filter_needs_approval)
    ChatFilter.Running -> stringResource(R.string.feature_sessions_filter_running)
    ChatFilter.Scheduled -> stringResource(R.string.feature_sessions_filter_scheduled)
    is ChatFilter.Source -> sourceLabel(filter.source)
}

/** "Telegram", "API": how a source reads in the menu and on rows. */
private fun sourceLabel(source: String): String =
    if (source == "api") "API" else source.replaceFirstChar(Char::uppercaseChar)

/** The filters in use, under the search box, with a way to clear them. */
@Composable
private fun FilterLine(filters: ChatFilters, onClear: () -> Unit) {
    val names = filters.chosen.sortedBy { filterOrder(it) }.map { filterLabel(it) }.joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
    ) {
        Text(
            stringResource(R.string.feature_sessions_filtered, names),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text(stringResource(R.string.feature_sessions_clear_filters)) }
    }
}

/** The menu's order: Starred, the statuses, Scheduled, then the other sources by name. */
private fun filterOrder(filter: ChatFilter): String = when (filter) {
    ChatFilter.Starred -> "0"
    ChatFilter.Unread -> "1"
    ChatFilter.NeedsApproval -> "2"
    ChatFilter.Running -> "3"
    ChatFilter.Scheduled -> "4"
    is ChatFilter.Source -> "5" + filter.source
}

/** What a row's tap and menu do. */
private class RowActions(
    val onClick: () -> Unit,
    val onRename: () -> Unit,
    val onTogglePin: () -> Unit,
    val onToggleStar: () -> Unit,
    val onToggleRead: () -> Unit,
    /** Null when the chat can't be moved to a project. */
    val onMove: (() -> Unit)?,
    val onToggleArchive: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun SessionRow(
    row: SessionSummary,
    starred: Boolean,
    live: LiveStatus?,
    project: String?,
    actions: RowActions,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = actions.onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (starred) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(R.string.feature_sessions_starred),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 2.dp),
                    )
                }
                Text(
                    row.displayTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (row.unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        },
        supportingContent = {
            Column {
                if (live == LiveStatus.NeedsInput) {
                    Text(
                        stringResource(R.string.feature_sessions_needs_you),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val preview = row.preview?.takeIf { row.title != null && it != row.title }
                if (preview != null) Text(preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(project, metaLine(row)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live == LiveStatus.Working) {
                    val running = stringResource(R.string.feature_sessions_running)
                    CircularProgressIndicator(
                        Modifier
                            .padding(end = 8.dp)
                            .size(16.dp)
                            .semantics { contentDescription = running },
                        strokeWidth = 2.dp,
                    )
                }
                if (row.unread) {
                    val unread = stringResource(R.string.feature_sessions_unread)
                    Badge(Modifier.semantics { contentDescription = unread })
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(
                                R.string.feature_sessions_row_actions,
                                row.displayTitle,
                            ),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MenuAction(R.string.feature_sessions_action_rename) {
                            menuOpen = false
                            actions.onRename()
                        }
                        if (!row.archived) {
                            val pinLabel = if (row.pinned) {
                                R.string.feature_sessions_action_unpin
                            } else {
                                R.string.feature_sessions_action_pin
                            }
                            MenuAction(pinLabel) {
                                menuOpen = false
                                actions.onTogglePin()
                            }
                            val starLabel = if (starred) {
                                R.string.feature_sessions_action_unstar
                            } else {
                                R.string.feature_sessions_action_star
                            }
                            MenuAction(starLabel) {
                                menuOpen = false
                                actions.onToggleStar()
                            }
                            val readLabel = if (row.unread) {
                                R.string.feature_sessions_action_mark_read
                            } else {
                                R.string.feature_sessions_action_mark_unread
                            }
                            MenuAction(readLabel) {
                                menuOpen = false
                                actions.onToggleRead()
                            }
                            actions.onMove?.let { move ->
                                MenuAction(R.string.feature_sessions_action_move) {
                                    menuOpen = false
                                    move()
                                }
                            }
                        }
                        val archiveLabel = if (row.archived) {
                            R.string.feature_sessions_action_restore
                        } else {
                            R.string.feature_sessions_action_archive
                        }
                        MenuAction(archiveLabel) {
                            menuOpen = false
                            actions.onToggleArchive()
                        }
                        MenuAction(R.string.feature_sessions_action_delete) {
                            menuOpen = false
                            actions.onDelete()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun MenuAction(label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun SearchRow(hit: SessionSearchHit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(hit.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val snippet = hit.snippet
            if (snippet != null) {
                val matchStyle = SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    buildAnnotatedString {
                        parseSnippet(snippet).forEach { part ->
                            if (part.match) withStyle(matchStyle) { append(part.text) } else append(part.text)
                        }
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            hit.lastActiveAtMs?.let { Text(relativeTime(it), style = MaterialTheme.typography.bodySmall) }
        },
    )
    HorizontalDivider()
}

@Composable
private fun metaLine(row: SessionSummary): String = buildList {
    row.lastActiveAtMs?.let { add(relativeTime(it)) }
    if (row.isMessagingSource) row.source?.let { add(it.replaceFirstChar(Char::uppercaseChar)) }
    if (row.messageCount > 0) add(stringResource(R.string.feature_sessions_messages, row.messageCount))
}.joinToString(" · ")

internal fun relativeTime(epochMs: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

@Composable
private fun RenameDialog(row: SessionSummary, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    // Opens focused with the old title selected, so typing replaces it.
    var title by remember(row.id) {
        val current = row.title.orEmpty()
        mutableStateOf(TextFieldValue(current, selection = TextRange(0, current.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(row.id) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_sessions_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.text.length <= MAX_TITLE) title = it },
                label = { Text(stringResource(R.string.feature_sessions_rename_label)) },
                supportingText = { Text(stringResource(R.string.feature_sessions_rename_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSave(title.text) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(title.text) }) { Text(stringResource(R.string.feature_sessions_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_cancel)) }
        },
    )
}

@Composable
private fun DeleteDialog(row: SessionSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feature_sessions_delete_title)) },
        text = { Text(stringResource(R.string.feature_sessions_delete_body, row.displayTitle)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.feature_sessions_action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_sessions_cancel)) }
        },
    )
}

@Composable
private fun messageText(message: SessionsMessage): String = when (message) {
    SessionsMessage.DeleteSkipped -> stringResource(R.string.feature_sessions_delete_skipped)

    is SessionsMessage.Failed -> stringResource(
        when (message.action) {
            SessionsMessage.Action.Rename -> R.string.feature_sessions_failed_rename
            SessionsMessage.Action.Pin -> R.string.feature_sessions_failed_pin
            SessionsMessage.Action.Archive -> R.string.feature_sessions_failed_archive
            SessionsMessage.Action.Delete -> R.string.feature_sessions_failed_delete
            SessionsMessage.Action.LoadMore -> R.string.feature_sessions_failed_more
            SessionsMessage.Action.NewChat -> R.string.feature_sessions_failed_new_chat
            SessionsMessage.Action.Read -> R.string.feature_sessions_failed_read
            SessionsMessage.Action.Move -> R.string.feature_sessions_failed_move
        },
        failureText(message.failure),
    )
}

@Composable
private fun ErrorState(text: String, onRetry: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.feature_sessions_retry)) }
        }
    }
}

@Composable
private fun EmptyRow(text: Int) {
    Text(
        stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
    )
}

/** The heading over the new chats whose message hasn't reached Hermes. */
@Composable
private fun UnsentHeader() {
    Text(
        stringResource(R.string.feature_sessions_unsent_header),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** A new chat whose message hasn't reached Hermes; opening it sends the message. */
@Composable
private fun UnsentRow(chat: UnsentNewChat, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.feature_sessions_unsent_title)) },
        supportingContent = {
            Column {
                Text(chat.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.feature_sessions_unsent_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun LoadingRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private const val LOAD_MORE_AHEAD = 5
private const val END_NOTE_AFTER = 20
private const val MAX_TITLE = 100

private const val PROFILE_AVATAR = 32
