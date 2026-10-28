package lab.dawnforge.hermes.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.bots.BotProfile.Companion.CHAT_TITLE as BOT_CHAT_TITLE
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.cache.ChatCache
import lab.dawnforge.hermes.core.data.cache.UnsentNewChat
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.plugin.HostedPlugin
import lab.dawnforge.hermes.core.data.plugin.PluginState
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.profiles.ProfileGlows
import lab.dawnforge.hermes.core.data.session.ChatListSettings
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.data.session.Project
import lab.dawnforge.hermes.core.data.session.ProjectsRepository
import lab.dawnforge.hermes.core.data.session.SessionPage
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.ProjectChats
import lab.dawnforge.hermes.core.data.share.ChatShortcuts
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.LiveStatus
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.model.SessionSearchHit
import lab.dawnforge.hermes.core.model.SessionSummary
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.push.PushSetup

/** How a profile looks in the switcher: its bot colour and picture, when Hermes has them. */
data class ProfileLook(val color: Int, val picture: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is ProfileLook && other.color == color && other.picture.contentEquals(picture)

    override fun hashCode(): Int = color
}

data class SessionsUiState(
    val dashboardId: DashboardId? = null,
    val profiles: List<Profile> = emptyList(),
    val profile: String = Profile.DEFAULT,
    /** Showing the archive instead of the main list. */
    val archived: Boolean = false,
    val pinned: List<SessionSummary> = emptyList(),
    val recent: List<SessionSummary> = emptyList(),
    /** [pinned] and [recent] by day, for the main list; empty for the archive, which is one list. */
    val sections: List<ChatSection> = emptyList(),
    val collapsed: Set<ChatListSection> = ChatListSection.defaultCollapsed,
    val filters: ChatFilters = ChatFilters(),
    /** The filter menu's choices, each with how many chats it would list. */
    val filterOptions: List<FilterOption> = emptyList(),
    val starred: Set<String> = emptySet(),
    /** What each live chat is doing, by every id it is known under. */
    val liveStatuses: Map<String, LiveStatus> = emptyMap(),
    /** False when this Hermes can't report live chats, so the Running and Needs approval filters are hidden. */
    val liveAvailable: Boolean = false,
    /** Listed chats waiting on an approval or an answer. */
    val waiting: Int = 0,
    val canMarkAllRead: Boolean = false,
    /** Whether reaching the end of the list loads older chats: not while they would go in a closed section. */
    val autoLoad: Boolean = true,
    /** The first page is loading and nothing is shown yet. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** Why the list could not be loaded at all. */
    val error: ConnectionFailure? = null,
    val query: String = "",
    val searching: Boolean = false,
    /** Search results, or null when not searching. */
    val results: List<SessionSearchHit>? = null,
    val renaming: SessionSummary? = null,
    val deleting: SessionSummary? = null,
    val startingChat: Boolean = false,
    /**
     * New chats started here whose message hasn't reached Hermes, which can't list them until it
     * has: shown first, so the message is never out of reach.
     */
    val unsent: List<UnsentNewChat> = emptyList(),
    val live: GatewayState = GatewayState.Stopped,
    /** Set while the connection waits for the user to repair it: the state it failed in. */
    val liveHeld: GatewayState? = null,
    /** The profiles' pictures and colours, by name, for the switcher. */
    val profileLooks: Map<String, ProfileLook> = emptyMap(),
    /** The profile's glow behind the top bar (`0xRRGGBB`); null for the app's accent. */
    val glow: Int? = null,
    val message: SessionsMessage? = null,
    /** The list shown is the copy saved on the phone: the dashboard did not answer. */
    val savedCopy: Boolean = false,
    /** Offer to set up notifications: this phone gets none from the profile, and the user hasn't said no. */
    val pushOffer: Boolean = false,
    /** The profile's projects, once read: for naming a chat's project and moving chats. */
    val projects: List<Project> = emptyList(),
    val projectChats: ProjectChats = ProjectChats.All,
    /** A chat being moved to a project. */
    val moving: SessionSummary? = null,
    /** "Finish setting up" for a dashboard just added; null once done or put off. */
    val finishSetup: FinishSetup? = null,
) {
    val profileLabel: String get() = profiles.firstOrNull { it.name == profile }?.label ?: profile

    /** The project a chat is in, other than Home. */
    fun projectOf(row: SessionSummary): Project? = projects.firstOrNull { !it.isHome && row.id in it.sessionIds }

    /** The projects a chat can move to: those with a folder, but not its own. A scheduled run keeps its job's. */
    fun moveTargets(row: SessionSummary): List<Project> {
        if (row.source == CRON_SOURCE) return emptyList()
        val own = projectOf(row)?.id
        return projects.filter { it.canTakeChats && it.id != own }
    }

    fun liveStatus(row: SessionSummary): LiveStatus? = liveStatuses[row.id]
}

/** One choice in the filter menu. */
data class FilterOption(val filter: ChatFilter, val count: Int)

/** How the push plugin stands, for "Finish setting up". */
enum class SetupPlugin { Checking, Install, Update, Restart, NotRunning, Done }

/** A new dashboard's setup: the push plugin, and notifications for the active profile. */
data class FinishSetup(val plugin: SetupPlugin, val notifications: Boolean) {
    val done: Boolean get() = plugin == SetupPlugin.Done && notifications
}

/** A one-off note for the snackbar. */
sealed interface SessionsMessage {
    data class Failed(val action: Action, val failure: ConnectionFailure) : SessionsMessage

    /** Hermes answered "ok" but kept the session, which it does while a reply is still running. */
    data object DeleteSkipped : SessionsMessage

    enum class Action { Rename, Pin, Archive, Delete, LoadMore, NewChat, Read, Move }
}

/**
 * A chat to open: a listed one by its stored [sessionId], a new one by its live [runtimeId]. A new
 * chat with an unsent message also has the stored id Hermes gave it ([reservedId]) and its folder.
 */
data class ChatTarget(
    val profile: String,
    val sessionId: String?,
    val runtimeId: String?,
    val title: String?,
    val reservedId: String? = null,
    val newChatCwd: String? = null,
)

sealed interface SessionsEvent {
    data class OpenChat(val target: ChatTarget) : SessionsEvent
}

/**
 * The active profile's chats on the active dashboard, as the dashboard sidebar shows them: pinned
 * first, then the most recent. Refreshes itself when Hermes reports a change (`sessions.changed`)
 * or the live connection comes back.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SessionsViewModel internal constructor(
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    private val sessions: SessionRepository,
    private val cache: ChatCache,
    private val push: PushSetup,
    private val listSettings: ChatListSettings,
    private val gateway: GatewayManager,
    private val projectsRepository: ProjectsRepository,
    private val appSettings: AppSettings,
    private val plugin: PushPluginRepository,
    private val shortcuts: ChatShortcuts,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    /** The bots roster, whose pictures the profiles share; none in tests. */
    private val bots: BotsRepository? = null,
    /** The profiles' glow colours; none in tests. */
    private val glows: ProfileGlows? = null,
) : ViewModel() {
    @Inject
    constructor(
        dashboards: DashboardRepository,
        profiles: ProfileRepository,
        sessions: SessionRepository,
        cache: ChatCache,
        push: PushSetup,
        listSettings: ChatListSettings,
        gateway: GatewayManager,
        projectsRepository: ProjectsRepository,
        appSettings: AppSettings,
        plugin: PushPluginRepository,
        shortcuts: ChatShortcuts,
        bots: BotsRepository,
        glows: ProfileGlows,
    ) : this(
        dashboards,
        profiles,
        sessions,
        cache,
        push,
        listSettings,
        gateway,
        projectsRepository,
        appSettings,
        plugin,
        shortcuts,
        System::currentTimeMillis,
        ZoneId::systemDefault,
        bots,
        glows,
    )

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    /**
     * When Hermes Desktop or the web dashboard last had each chat on screen (host seconds), from
     * the push plugin; the cursor for the next read, when it was last read, and for which profile.
     */
    private val desktopSeen = HashMap<String, Double>()
    private var desktopCursor: Double? = null
    private var desktopReadAt = 0L
    private var desktopProfile: String? = null

    /** Whether the host's plugin reports Desktop views; null until asked. */
    private var desktopViewsServed: Boolean? = null

    /** When the projects were last read, and for which profile. */
    private var projectsReadAt = 0L
    private var projectsProfile: String? = null

    private val _events = Channel<SessionsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Every row loaded so far, by id; pages overlap because Hermes repeats pinned rows. */
    private val rows = LinkedHashMap<String, SessionSummary>()

    /** The latest scheduled-job runs, listed only under the Scheduled filter (and pinned ones in All). */
    private val scheduledRows = LinkedHashMap<String, SessionSummary>()

    /** Wakes the live-state watch early, when Hermes reports a change. */
    private val livePokes = Channel<Unit>(Channel.CONFLATED)
    private var nextOffset = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull()
                .flatMapLatest { id -> profiles.activeProfile(id).map { id to it } }
                .distinctUntilChanged()
                .collectLatest { (id, profile) ->
                    desktopViewsServed = null
                    _state.update {
                        it.copy(
                            dashboardId = id,
                            profile = profile,
                            archived = false,
                            query = "",
                            results = null,
                            projects = emptyList(),
                        )
                    }
                    loadProfiles(id)
                    reload(spinner = true)
                    loadProjects(force = true)
                    // The order or the main profile's name changed in the profile manager.
                    profiles.appearance(id).drop(1).collect { loadProfiles(id) }
                }
        }
        glows?.let { colors ->
            viewModelScope.launch {
                _state.map { it.dashboardId to it.profile }.distinctUntilChanged()
                    .flatMapLatest { (id, profile) -> id?.let { colors.tint(it, profile) } ?: flowOf(null) }
                    .collect { glow -> _state.update { it.copy(glow = glow) } }
            }
        }
        bots?.let { roster ->
            viewModelScope.launch {
                roster.state.combine(roster.avatars) { state, pictures ->
                    (state as? BotsState.Available)?.roster?.bots.orEmpty().associate { bot ->
                        bot.name to ProfileLook(BotColor.of(bot), pictures[bot.name])
                    }
                }.collect { looks -> _state.update { it.copy(profileLooks = looks) } }
            }
        }
        viewModelScope.launch {
            _state.map { it.dashboardId to it.profile }.distinctUntilChanged()
                .flatMapLatest { (id, profile) -> id?.let { cache.unsentNewChats(it, profile) } ?: flowOf(emptyList()) }
                .collect { unsent -> _state.update { it.copy(unsent = unsent) } }
        }
        viewModelScope.launch {
            appSettings.preferences.map { it.projectChats }.distinctUntilChanged().collect { choice ->
                _state.update { it.copy(projectChats = choice) }
                publish()
            }
        }
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull()
                .flatMapLatest { id -> profiles.activeProfile(id).flatMapLatest { profile -> push.offer(id, profile) } }
                .collect { offer -> _state.update { it.copy(pushOffer = offer) } }
        }
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull().flatMapLatest { id ->
                combine(dashboards.finishingSetup, profiles.activeProfile(id), push.pairings) {
                        pending,
                        profile,
                        pairings,
                    ->
                    Triple(
                        id,
                        profile,
                        pairings.any { it.dashboardId == id && it.profile == profile }.takeIf {
                            id in
                                pending
                        },
                    )
                }
            }.distinctUntilChanged().collectLatest { (id, profile, notifications) ->
                if (notifications == null) {
                    _state.update { it.copy(finishSetup = null) }
                    return@collectLatest
                }
                val plugin = _state.value.finishSetup?.plugin ?: SetupPlugin.Checking
                _state.update { it.copy(finishSetup = FinishSetup(plugin, notifications)) }
                checkSetup(id, profile)
            }
        }
        viewModelScope.launch {
            gateway.events.filter {
                it.type == SESSIONS_CHANGED
            }.debounce(CHANGE_DEBOUNCE_MS).collect {
                refreshQuietly()
                livePokes.trySend(Unit)
            }
        }
        viewModelScope.launch {
            listSettings.collapsed(ChatListSection.defaultCollapsed.names()).collect { names ->
                _state.update { it.copy(collapsed = names.mapNotNull(ChatListSection::fromName).toSet()) }
                publish()
            }
        }
        viewModelScope.launch {
            listSettings.filters.collect { keys ->
                _state.update { it.copy(filters = ChatFilters.fromKeys(keys)) }
                publish()
            }
        }
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull()
                .flatMapLatest { id -> profiles.activeProfile(id).flatMapLatest { listSettings.starred(id, it) } }
                .collect { starred ->
                    _state.update { it.copy(starred = starred) }
                    publish()
                }
        }
        viewModelScope.launch {
            // A reconnect may have missed changes; the first value is the current state, not a reconnect.
            gateway.status.map { it.state is GatewayState.Connected }.distinctUntilChanged().drop(1)
                .filter { it }.collect {
                    refreshQuietly()
                    loadLooks()
                }
        }
        viewModelScope.launch {
            gateway.status.collect { status -> _state.update { it.copy(live = status.state, liveHeld = status.held) } }
        }
    }

    // ---- Loading -------------------------------------------------------------------------------

    fun refresh() = reload(spinner = false)

    /**
     * The list shows again (back from a chat, or the app back in front): reads it once more, since
     * a branch or a chat made elsewhere may not have been announced. The first showing loads anyway.
     */
    fun shown() {
        if (shownBefore) refreshQuietly()
        shownBefore = true
    }

    private var shownBefore = false

    fun dismissPushOffer() {
        viewModelScope.launch { push.dismissOffer() }
    }

    fun loadMore() {
        val current = _state.value
        val id = current.dashboardId ?: return
        if (current.loading || current.loadingMore || current.endReached || current.results != null) return
        _state.update { it.copy(loadingMore = true) }
        loadJob = viewModelScope.launch {
            val offset = nextOffset
            when (val page = sessions.page(id, current.profile, current.archived, offset, PAGE_SIZE)) {
                is NetResult.Success -> {
                    val added = merge(page.value)
                    nextOffset = offset + PAGE_SIZE
                    publish(endReached = added < PAGE_SIZE)
                    _state.update { it.copy(loadingMore = false) }
                }

                is NetResult.Failure -> _state.update {
                    it.copy(
                        loadingMore = false,
                        message = SessionsMessage.Failed(SessionsMessage.Action.LoadMore, page.failure),
                    )
                }
            }
        }
    }

    private fun reload(spinner: Boolean) {
        val current = _state.value
        val id = current.dashboardId ?: return
        loadJob?.cancel()
        _state.update { it.copy(loading = spinner, refreshing = !spinner, error = null) }
        if (spinner) {
            rows.clear()
            scheduledRows.clear()
        }
        loadJob = viewModelScope.launch {
            if (spinner) showSaved(id, current.profile, current.archived)
            val page = sessions.page(id, current.profile, current.archived, 0, PAGE_SIZE)
            applyFirstPage(page, PAGE_SIZE)
            loadScheduled(id, current)
        }
    }

    /** Re-reads what is already loaded (one request), keeping the list on screen meanwhile. */
    private fun refreshQuietly() {
        val current = _state.value
        val id = current.dashboardId ?: return
        if (current.loading || current.results != null) return
        loadJob?.cancel()
        val limit = nextOffset.coerceIn(PAGE_SIZE, MAX_LIMIT)
        loadJob = viewModelScope.launch {
            applyFirstPage(sessions.page(id, current.profile, current.archived, 0, limit), limit)
            loadScheduled(id, current)
        }
    }

    /** The latest scheduled runs, for the Scheduled filter; the main list leaves them out. */
    private suspend fun loadScheduled(id: DashboardId, at: SessionsUiState) {
        if (at.archived) return
        val page = sessions.page(
            id,
            at.profile,
            archived = false,
            offset = 0,
            limit = SCHEDULED_LIMIT,
            scheduled = true,
        )
        if (page !is NetResult.Success) return
        scheduledRows.clear()
        page.value.rows.forEach { scheduledRows[it.id] = it }
        publish()
    }

    /** The list as last loaded, while the dashboard is asked: it opens at once, and with no network. */
    private suspend fun showSaved(id: DashboardId, profile: String, archived: Boolean) {
        val saved = cache.sessions(id, profile, archived)
        if (saved.isEmpty()) return
        saved.forEach { rows[it.id] = it }
        // No paging past the saved page until the dashboard answers.
        publish(endReached = true)
        _state.update { it.copy(loading = false) }
    }

    private suspend fun applyFirstPage(page: NetResult<SessionPage>, limit: Int) {
        when (page) {
            is NetResult.Success -> {
                rows.clear()
                val added = merge(page.value)
                nextOffset = limit
                publish(endReached = added < limit)
                _state.update { it.copy(loading = false, refreshing = false, error = null, savedCopy = false) }
                val current = _state.value
                current.dashboardId?.let { cache.saveSessions(it, current.profile, current.archived, page.value.rows) }
                // The recent chats for Direct Share and the launcher.
                if (!current.archived) {
                    current.dashboardId?.let {
                        shortcuts.publish(it, current.profile, page.value.rows)
                    }
                }
            }

            is NetResult.Failure -> {
                val current = _state.value
                // A profile removed on the host: fall back to the default one.
                if (page.failure is ConnectionFailure.NotFound && current.profile != Profile.DEFAULT) {
                    current.dashboardId?.let { profiles.setActiveProfile(it, Profile.DEFAULT) }
                    return
                }
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = page.failure.takeIf { rows.isEmpty() },
                        savedCopy = rows.isNotEmpty(),
                    )
                }
            }
        }
    }

    /** Adds a page's rows; returns how many were new and not pinned (pinned rows repeat on every page). */
    private fun merge(page: SessionPage): Int {
        var added = 0
        for (row in page.rows) {
            val isNew = rows.put(row.id, row) == null
            if (isNew && !row.pinned) added++
        }
        return added
    }

    private fun publish(endReached: Boolean = _state.value.endReached) {
        val current = _state.value
        if (current.archived) {
            val visible = rows.values.filter { it.archived }.sortedByDescending { it.lastActiveAtMs ?: 0 }
            _state.update {
                it.copy(pinned = emptyList(), recent = visible, sections = emptyList(), endReached = endReached)
            }
            return
        }
        // Every row a filter could list: the chats, and the scheduled runs the main list leaves out.
        val pool = LinkedHashMap(rows)
        scheduledRows.forEach { (id, row) -> pool.putIfAbsent(id, row) }
        // A chat Desktop had on screen through its latest activity isn't unread here either.
        pool.replaceAll { _, row -> if (row.unread && desktopSaw(row)) row.copy(unread = false) else row }
        // A bot's one chat is opened from Bots, not listed here, as on iOS.
        val candidates = pool.values.filterNot { it.archived || it.title == BOT_CHAT_TITLE }
        fun listed(filters: ChatFilters) = candidates.filter { row ->
            filters.matches(row, row.id in current.starred, current.liveStatuses[row.id])
        }
        // A filter looks through every chat; the plain list leaves project chats to their project, as chosen.
        val visible = listed(current.filters)
            .filter { current.filters.isEmpty.not() || showsInList(it, current) }
            .sortedByDescending { it.lastActiveAtMs ?: 0 }
        val pinned = visible.filter(SessionSummary::pinned)
        val recent = visible.filterNot(SessionSummary::pinned)
        val sections = chatSections(pinned, recent, clock(), zone())
        val options = buildList {
            add(ChatFilter.Starred)
            add(ChatFilter.Unread)
            if (current.liveAvailable) {
                add(ChatFilter.NeedsApproval)
                add(ChatFilter.Running)
            }
            add(ChatFilter.Scheduled)
            addAll(ChatFilters.sources(rows.values))
        }.map { FilterOption(it, listed(current.filters.facet(it)).size) }
        val all = listed(ChatFilters())
        val olderLoaded = sections.any { it.section == ChatListSection.Older }
        _state.update {
            it.copy(
                pinned = pinned,
                recent = recent,
                sections = sections,
                filterOptions = options,
                waiting = all.count { row -> current.liveStatuses[row.id] == LiveStatus.NeedsInput },
                canMarkAllRead = !current.savedCopy && all.any(SessionSummary::unread),
                autoLoad = !(olderLoaded && ChatListSection.Older in current.collapsed),
                endReached = endReached,
            )
        }
    }

    private fun desktopSaw(row: SessionSummary): Boolean {
        if (row.markedUnread) return false
        val seen = desktopSeen[row.id] ?: return false
        val active = row.lastActiveAtMs ?: return false
        return active / MS_PER_SECOND <= seen
    }

    /**
     * Reads which chats Hermes Desktop had on screen since the last read, at most every
     * [DESKTOP_VIEWS_EVERY_MS], when the host's plugin reports them.
     */
    private suspend fun readDesktopViews() {
        val current = _state.value
        val id = current.dashboardId ?: return
        if (current.profile != desktopProfile) {
            desktopSeen.clear()
            desktopCursor = null
            desktopReadAt = 0L
            desktopProfile = current.profile
        }
        if (clock() - desktopReadAt < DESKTOP_VIEWS_EVERY_MS) return
        desktopReadAt = clock()
        if (desktopViewsServed == null) {
            val state = (plugin.state(id, current.profile) as? NetResult.Success)?.value ?: return
            desktopViewsServed = (state as? PluginState.Reported)?.status?.reports(DESKTOP_VIEWS) == true
        }
        if (desktopViewsServed != true) return
        val views = (plugin.desktopViews(id, current.profile, desktopCursor) as? NetResult.Success)?.value ?: return
        if (_state.value.profile != current.profile) return
        var changed = false
        views.views.forEach { (session, view) ->
            if ((desktopSeen[session] ?: 0.0) < view.seenThrough) {
                desktopSeen[session] = view.seenThrough
                changed = true
            }
        }
        // A chat still on screen there reports "now": it doesn't move the cursor on.
        views.views.values.filterNot { it.open }.maxOfOrNull { it.seenThrough }?.let { latest ->
            desktopCursor = maxOf(desktopCursor ?: 0.0, latest)
        }
        if (changed) publish()
    }

    /** Whether the plain list shows [row], by the "Project chats" setting. Pinned chats always show. */
    private fun showsInList(row: SessionSummary, current: SessionsUiState): Boolean {
        if (row.pinned || current.projectChats == ProjectChats.All || current.projectOf(row) == null) return true
        if (current.projectChats == ProjectChats.Hidden) return false
        val yesterday = LocalDate.ofInstant(Instant.ofEpochMilli(clock()), zone()).minusDays(1)
            .atStartOfDay(zone()).toInstant().toEpochMilli()
        return (row.lastActiveAtMs ?: 0) >= yesterday
    }

    /** The profile's projects; read again at most once a minute, or when [force]d. */
    private suspend fun loadProjects(force: Boolean = false) {
        val profile = _state.value.profile
        if (!force && profile == projectsProfile && clock() - projectsReadAt < PROJECTS_EVERY_MS) return
        if (gateway.status.value.state !is GatewayState.Connected) return
        projectsReadAt = clock()
        projectsProfile = profile
        val result = projectsRepository.tree(profile)
        val projects = (result as? NetResult.Success)?.value ?: return
        if (_state.value.profile != profile) return
        _state.update { it.copy(projects = projects) }
        publish()
    }

    /** Reads how the push plugin stands for "Finish setting up"; done setup ends the card. */
    fun refreshSetup() {
        val current = _state.value
        val id = current.dashboardId ?: return
        if (current.finishSetup == null) return
        viewModelScope.launch { checkSetup(id, current.profile) }
    }

    fun dismissSetup() {
        val id = _state.value.dashboardId ?: return
        viewModelScope.launch { dashboards.endFinishSetup(id) }
    }

    private suspend fun checkSetup(id: DashboardId, profile: String) {
        val plugin = when (val result = plugin.state(id, profile)) {
            is NetResult.Failure -> return
            is NetResult.Success -> setupPlugin(result.value)
        }
        _state.update { state -> state.copy(finishSetup = state.finishSetup?.copy(plugin = plugin)) }
        if (_state.value.finishSetup?.done == true) dashboards.endFinishSetup(id)
    }

    private fun setupPlugin(state: PluginState): SetupPlugin = when (state) {
        is PluginState.Absent -> when (state.hosted) {
            HostedPlugin.Missing -> SetupPlugin.Install
            HostedPlugin.NotServed -> SetupPlugin.NotRunning
            HostedPlugin.Old, HostedPlugin.Unknown -> SetupPlugin.Update
        }

        is PluginState.Reported -> when {
            state.status.restartNeeded -> SetupPlugin.Restart
            SETUP_NEEDS.any { !state.status.reports(it) } -> SetupPlugin.Update
            else -> SetupPlugin.Done
        }
    }

    fun askMove(row: SessionSummary) = _state.update { it.copy(moving = row) }

    fun cancelMove() = _state.update { it.copy(moving = null) }

    /** Moves the chat being moved to [project]'s folder; Hermes moves no files. */
    fun move(project: Project) {
        val row = _state.value.moving ?: return
        val path = project.primaryPath ?: return
        _state.update { it.copy(moving = null) }
        viewModelScope.launch {
            when (val result = projectsRepository.move(_state.value.profile, row.id, path)) {
                is NetResult.Success -> loadProjects(force = true)

                is NetResult.Failure -> _state.update {
                    it.copy(message = SessionsMessage.Failed(SessionsMessage.Action.Move, result.failure))
                }
            }
        }
    }

    private suspend fun loadProfiles(id: DashboardId) {
        when (val list = profiles.profiles(id)) {
            is NetResult.Success -> _state.update { it.copy(profiles = list.value) }
            is NetResult.Failure -> Unit // The switcher then offers only the current profile.
        }
        loadLooks()
    }

    /** The profiles' pictures, read over the live connection once it is open. */
    private suspend fun loadLooks() {
        val roster = bots ?: return
        if (gateway.status.value.state !is GatewayState.Connected) return
        (roster.refresh() as? BotsState.Available)?.roster?.bots?.forEach { roster.loadAvatar(it) }
    }

    /** Reconnects a connection held for repair, without repairing it. */
    fun reconnectAnyway() = gateway.endHold(reconnect = true)

    // ---- Search --------------------------------------------------------------------------------

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        val text = query.trim()
        if (text.length < MIN_QUERY) {
            _state.update { it.copy(results = null, searching = false) }
            return
        }
        val current = _state.value
        val id = current.dashboardId ?: return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            when (val hits = sessions.search(id, current.profile, text)) {
                is NetResult.Success -> _state.update { state ->
                    state.copy(searching = false, results = hits.value.filter { it.archived == state.archived })
                }

                is NetResult.Failure -> _state.update {
                    it.copy(searching = false, results = emptyList(), error = hits.failure)
                }
            }
        }
    }

    // ---- Profiles and views --------------------------------------------------------------------

    fun selectProfile(name: String) {
        val id = _state.value.dashboardId ?: return
        if (name == _state.value.profile) return
        viewModelScope.launch { profiles.setActiveProfile(id, name) }
    }

    fun showArchive(show: Boolean) {
        if (show == _state.value.archived) return
        _state.update { it.copy(archived = show, query = "", results = null) }
        reload(spinner = true)
    }

    // ---- Sections, filters, stars and live state -----------------------------------------------

    fun toggleSection(section: ChatListSection) {
        val collapsed = _state.value.collapsed
        val next = if (section in collapsed) collapsed - section else collapsed + section
        viewModelScope.launch { listSettings.setCollapsed(next.names()) }
    }

    fun setFilter(filter: ChatFilter, on: Boolean) {
        val next = _state.value.filters.with(filter, on)
        viewModelScope.launch { listSettings.setFilters(next.keys()) }
    }

    fun clearFilters() {
        viewModelScope.launch { listSettings.setFilters(emptySet()) }
    }

    /** Stars live on this phone: Hermes has no star. */
    fun toggleStar(row: SessionSummary) {
        val current = _state.value
        val id = current.dashboardId ?: return
        viewModelScope.launch { listSettings.setStarred(id, current.profile, row.id, row.id !in current.starred) }
    }

    fun toggleRead(row: SessionSummary) = change(row, row.copy(unread = !row.unread), SessionsMessage.Action.Read) {
            id,
            profile,
        ->
        sessions.setUnread(id, profile, row.id, !row.unread)
    }

    /** Marks every chat All lists as read, one request at a time. */
    fun markAllRead() {
        val current = _state.value
        val id = current.dashboardId ?: return
        val unread = (rows.values + scheduledRows.values.filter(SessionSummary::pinned))
            .filter { it.unread && !it.archived }
            .distinctBy(SessionSummary::id)
        if (unread.isEmpty()) return
        unread.forEach { row -> update(row.copy(unread = false)) }
        publish()
        viewModelScope.launch {
            for (row in unread) {
                val result = sessions.setUnread(id, current.profile, row.id, false)
                if (result is NetResult.Failure) {
                    update(row)
                    publish()
                    _state.update {
                        it.copy(message = SessionsMessage.Failed(SessionsMessage.Action.Read, result.failure))
                    }
                    return@launch
                }
            }
        }
    }

    /**
     * Keeps the running and needs-approval states fresh while the list shows: the screen runs it
     * while started. Hermes has no event for them, so it asks every few seconds, less often while
     * nothing changes, and again at once when Hermes reports a change.
     */
    suspend fun watchLiveStatuses() {
        var step = 0
        while (true) {
            val changed = readLiveStatuses() ?: return
            step = if (changed) 0 else (step + 1).coerceAtMost(LIVE_STEPS_MS.lastIndex)
            if (withTimeoutOrNull(LIVE_STEPS_MS[step]) { livePokes.receive() } != null) step = 0
        }
    }

    /** Reads the live states; whether they changed, or null when this Hermes can't report them. */
    private suspend fun readLiveStatuses(): Boolean? {
        readDesktopViews()
        if (gateway.status.value.state !is GatewayState.Connected) return false
        loadProjects()
        return when (val result = sessions.liveStatuses()) {
            is NetResult.Success -> {
                val statuses = result.value
                if (statuses == null) {
                    _state.update { it.copy(liveAvailable = false, liveStatuses = emptyMap()) }
                    publish()
                    return null
                }
                val changed = statuses != _state.value.liveStatuses || !_state.value.liveAvailable
                if (changed) {
                    _state.update { it.copy(liveStatuses = statuses, liveAvailable = true) }
                    publish()
                }
                changed
            }

            // A failed read keeps the last states; the next tick asks again.
            is NetResult.Failure -> false
        }
    }

    // ---- Changes -------------------------------------------------------------------------------

    fun open(row: SessionSummary) = openChat(ChatTarget(_state.value.profile, row.id, null, row.displayTitle))

    fun open(hit: SessionSearchHit) = openChat(ChatTarget(_state.value.profile, hit.sessionId, null, hit.displayTitle))

    /** A new chat whose message hasn't reached Hermes: opening it sends the message, or settles it. */
    fun open(chat: UnsentNewChat) = openChat(
        ChatTarget(
            profile = _state.value.profile,
            sessionId = null,
            runtimeId = chat.chatKey,
            title = null,
            reservedId = chat.chat.reservedId,
            newChatCwd = chat.chat.cwd,
        ),
    )

    fun newChat() {
        val current = _state.value
        if (current.startingChat) return
        _state.update { it.copy(startingChat = true) }
        viewModelScope.launch {
            when (val chat = sessions.startChat(current.profile)) {
                is NetResult.Success -> openChat(ChatTarget(chat.value.profile, null, chat.value.runtimeId, null))

                is NetResult.Failure -> _state.update {
                    it.copy(message = SessionsMessage.Failed(SessionsMessage.Action.NewChat, chat.failure))
                }
            }
            _state.update { it.copy(startingChat = false) }
        }
    }

    fun togglePin(row: SessionSummary) = change(row, row.copy(pinned = !row.pinned), SessionsMessage.Action.Pin) {
            id,
            profile,
        ->
        sessions.setPinned(id, profile, row.id, !row.pinned)
    }

    fun toggleArchive(row: SessionSummary) {
        val archive = !row.archived
        val updated = row.copy(archived = archive, pinned = if (archive) false else row.pinned)
        change(row, updated, SessionsMessage.Action.Archive) { id, profile ->
            sessions.setArchived(id, profile, row.id, archive)
        }
    }

    fun askRename(row: SessionSummary) = _state.update { it.copy(renaming = row) }

    fun rename(title: String) {
        val row = _state.value.renaming ?: return
        _state.update { it.copy(renaming = null) }
        val wanted = title.trim()
        if (wanted == row.title.orEmpty()) return
        val id = _state.value.dashboardId ?: return
        val profile = _state.value.profile
        rows[row.id] = row.copy(title = wanted.ifEmpty { null })
        publish()
        viewModelScope.launch {
            when (val result = sessions.rename(id, profile, row.id, wanted)) {
                // Hermes tidies titles (control characters, spacing), so keep its version.
                is NetResult.Success -> rows[row.id]?.let { rows[row.id] = it.copy(title = result.value) }

                is NetResult.Failure -> {
                    rows[row.id] = row
                    _state.update {
                        it.copy(message = SessionsMessage.Failed(SessionsMessage.Action.Rename, result.failure))
                    }
                }
            }
            publish()
        }
    }

    fun askDelete(row: SessionSummary) = _state.update { it.copy(deleting = row) }

    fun delete() {
        val row = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        val id = _state.value.dashboardId ?: return
        val current = _state.value
        rows.remove(row.id)
        publish()
        viewModelScope.launch {
            when (val result = sessions.delete(id, current.profile, row.id)) {
                is NetResult.Success -> {
                    cache.forgetSession(id, current.profile, row.id)
                    if (row.id in current.starred) listSettings.setStarred(id, current.profile, row.id, starred = false)
                    confirmDeleted(id, current, row)
                }

                is NetResult.Failure -> {
                    rows[row.id] = row
                    publish()
                    _state.update {
                        it.copy(message = SessionsMessage.Failed(SessionsMessage.Action.Delete, result.failure))
                    }
                }
            }
        }
    }

    fun dismissDialog() = _state.update { it.copy(renaming = null, deleting = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Hermes reports success even when it kept a session whose reply is running; check the list. */
    private suspend fun confirmDeleted(id: DashboardId, at: SessionsUiState, row: SessionSummary) {
        val limit = nextOffset.coerceIn(PAGE_SIZE, MAX_LIMIT)
        val page = sessions.page(id, at.profile, at.archived, 0, limit) as? NetResult.Success ?: return
        if (page.value.rows.any { it.id == row.id }) {
            rows[row.id] = row
            publish()
            _state.update { it.copy(message = SessionsMessage.DeleteSkipped) }
        }
    }

    private fun change(
        row: SessionSummary,
        updated: SessionSummary,
        action: SessionsMessage.Action,
        call: suspend (DashboardId, String) -> NetResult<Unit>,
    ) {
        val id = _state.value.dashboardId ?: return
        val profile = _state.value.profile
        update(updated)
        publish()
        viewModelScope.launch {
            val result = call(id, profile)
            if (result is NetResult.Failure) {
                update(row)
                publish()
                _state.update { it.copy(message = SessionsMessage.Failed(action, result.failure)) }
            }
        }
    }

    /** Replaces a row wherever it is listed: the chats or the scheduled runs. */
    private fun update(row: SessionSummary) {
        if (row.id in rows) rows[row.id] = row
        if (row.id in scheduledRows) scheduledRows[row.id] = row
    }

    private fun openChat(target: ChatTarget) {
        viewModelScope.launch { _events.send(SessionsEvent.OpenChat(target)) }
    }

    private companion object {
        /** Building the project tree walks every chat: read it at most this often. */
        const val PROJECTS_EVERY_MS = 60_000L

        /** What the plugin must serve for setup to count as done. */
        val SETUP_NEEDS = listOf("pairing", "e2e-notifications", "session-takeover")

        /** Desktop's views are read this often while the list shows, as iOS. */
        const val DESKTOP_VIEWS_EVERY_MS = 15_000L
        const val DESKTOP_VIEWS = "desktop-views"
        const val MS_PER_SECOND = 1000.0
        const val PAGE_SIZE = 50
        const val MAX_LIMIT = 500
        const val MIN_QUERY = 2
        const val SEARCH_DEBOUNCE_MS = 300L
        const val CHANGE_DEBOUNCE_MS = 1_000L
        const val SESSIONS_CHANGED = "sessions.changed"
        const val SCHEDULED_LIMIT = 100

        /** How long the live-state watch waits between reads: longer while nothing changes. */
        val LIVE_STEPS_MS = longArrayOf(4_000, 8_000, 15_000, 30_000, 60_000)
    }
}

private fun Set<ChatListSection>.names(): Set<String> = mapTo(mutableSetOf(), ChatListSection::name)

/** A scheduled job's runs keep the job's folder. */
private const val CRON_SOURCE = "cron"
