package lab.dawnforge.hermes.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.plugin.PluginState
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.plugin.ReplySource
import lab.dawnforge.hermes.core.data.plugin.ReplySources
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.push.PushProblem
import lab.dawnforge.hermes.core.push.PushResult
import lab.dawnforge.hermes.core.push.PushSetup
import lab.dawnforge.hermes.core.push.PushStore

/** A profile's notifications on this phone. */
sealed interface ProfileNotifications {
    data object Checking : ProfileNotifications

    data object Off : ProfileNotifications

    data object On : ProfileNotifications

    /** The profile notifies another device; turning on here takes them over. */
    data object OnElsewhere : ProfileNotifications
}

/** One profile of the active dashboard. */
data class ProfileRow(
    val name: String,
    val label: String,
    val status: ProfileNotifications,
    val preferences: PushStore.NotifyPreferences,
    /** Which senders' replies notify, as the host's plugin keeps them; null until read or unsupported. */
    val replySources: ReplySources? = null,
)

/** A profile of another saved dashboard that notifies this phone. */
data class OtherPairing(val dashboardId: DashboardId, val dashboard: String, val profile: String)

data class NotificationsUiState(
    /** False when this build has no Firebase settings, so it can't receive pushes at all. */
    val available: Boolean = true,
    val dashboard: String? = null,
    val profiles: List<ProfileRow> = emptyList(),
    val others: List<OtherPairing> = emptyList(),
    val relayUrl: String = "",
    /** The profile being turned on or off. */
    val working: String? = null,
    val problem: PushProblem? = null,
    /** A reply source couldn't be read or changed. */
    val sourcesFailure: ConnectionFailure? = null,
    /** A reply source is being changed. */
    val savingSource: Boolean = false,
)

/** Notifications for every profile of the active dashboard, and the other dashboards' pairings. */
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    private val push: PushSetup,
    private val plugin: PushPluginRepository,
) : ViewModel() {
    private val state = MutableStateFlow(NotificationsUiState(available = push.available))
    val uiState: StateFlow<NotificationsUiState> = state.asStateFlow()

    /** The active dashboard's profiles, and how each stands as Hermes reported it. */
    private val known = MutableStateFlow<List<Profile>>(emptyList())
    private val statuses = MutableStateFlow<Map<String, ProfileNotifications>>(emptyMap())
    private val sources = MutableStateFlow<Map<String, ReplySources>>(emptyMap())

    init {
        viewModelScope.launch {
            val checkedSources = combine(statuses, sources) { checked, read -> checked to read }
            combine(dashboards.dashboards, dashboards.activeDashboardId, push.pairings, known, checkedSources) {
                    saved,
                    activeId,
                    pairings,
                    list,
                    (checked, read),
                ->
                val here = pairings.filter { it.dashboardId == activeId }
                val rows = list.map { profile ->
                    ProfileRow(
                        name = profile.name,
                        label = profile.label,
                        status = checked[profile.name] ?: ProfileNotifications.Checking,
                        preferences = here.firstOrNull { it.profile == profile.name }?.preferences
                            ?: PushStore.NotifyPreferences(),
                        replySources = read[profile.name],
                    )
                }
                val others = pairings.filter { it.dashboardId != activeId }.map { pairing ->
                    OtherPairing(
                        pairing.dashboardId,
                        saved.firstOrNull {
                            it.id == pairing.dashboardId
                        }?.url?.toString() ?: pairing.dashboardId.value,
                        pairing.profile,
                    )
                }
                Triple(saved.firstOrNull { it.id == activeId }?.url?.toString(), rows, others)
            }.collect { (dashboard, rows, others) ->
                state.update { it.copy(dashboard = dashboard, profiles = rows, others = others) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            state.update { it.copy(relayUrl = push.relayUrl().orEmpty()) }
            val list = when (val reply = profiles.profiles(id)) {
                is NetResult.Success -> reply.value

                // Unreachable for now: still offer the profile the app uses.
                is NetResult.Failure -> {
                    state.update { it.copy(problem = PushProblem.Dashboard(reply.failure)) }
                    listOf(
                        Profile(
                            profiles.activeProfile(id).first(),
                            null,
                            isDefault = false,
                            model = null,
                            provider = null,
                        ),
                    )
                }
            }
            known.value = list
            if (!push.available) return@launch
            for (profile in list) check(id, profile.name)
        }
    }

    fun onRelayUrlChange(url: String) = state.update { it.copy(relayUrl = url) }

    fun turnOn(profile: String) = change(profile) { id ->
        push.setRelayUrl(state.value.relayUrl)
        enable(id, profile, AllProfiles.preferences(state.value.profiles), AllProfiles.sources(state.value.profiles))
    }

    fun turnOff(profile: String) = change(profile) { id ->
        push.disable(id, profile)
        check(id, profile)
    }

    /** Every profile on this phone that isn't, with the choices they all share; stops at a failure. */
    fun turnAllOn() = change(ALL) { id ->
        push.setRelayUrl(state.value.relayUrl)
        val rows = state.value.profiles
        val preferences = AllProfiles.preferences(rows)
        val sources = AllProfiles.sources(rows)
        for (profile in AllProfiles.toTurnOn(rows)) {
            if (!enable(id, profile, preferences, sources)) break
        }
    }

    /** Every profile off on this phone. */
    fun turnAllOff() = change(ALL) { id ->
        for (profile in AllProfiles.toTurnOff(state.value.profiles)) {
            push.disable(id, profile)
            check(id, profile)
        }
    }

    /** Turns [profile] on with the shared [preferences] and [sources]; false when it failed. */
    private suspend fun enable(
        id: DashboardId,
        profile: String,
        preferences: PushStore.NotifyPreferences,
        sources: ReplySources?,
    ): Boolean = when (val result = push.enable(id, profile)) {
        is PushResult.Done -> {
            push.setPreferences(id, profile, preferences)
            statuses.update { it + (profile to ProfileNotifications.On) }
            readSources(id, profile)
            val read = this.sources.value[profile]
            if (sources != null && read != null && read != sources) {
                for (source in ReplySource.entries) {
                    if (read.of(source) !=
                        sources.of(source)
                    ) {
                        plugin.setReplySource(id, profile, source, sources.of(source))
                    }
                }
                readSources(id, profile)
            }
            true
        }

        is PushResult.Failed -> {
            state.update { it.copy(problem = result.problem) }
            false
        }
    }

    /** Stops another dashboard's profile from notifying this phone. */
    fun turnOffOther(other: OtherPairing) {
        viewModelScope.launch { push.disable(other.dashboardId, other.profile) }
    }

    /** Whether [type] notifies, for every profile on this phone. */
    fun setMuted(type: String, muted: Boolean) = updatePreferences {
        it.copy(muted = if (muted) it.muted + type else it.muted - type)
    }

    /** Whether notifications show the message text, for every profile on this phone. */
    fun setHideText(hide: Boolean) = updatePreferences { it.copy(hideText = hide) }

    /** Whether replies to messages from [source] notify, for every profile on this phone whose host keeps them. */
    fun setReplySource(source: ReplySource, notify: Boolean) {
        if (state.value.savingSource) return
        val profiles = state.value.profiles
            .filter { it.status == ProfileNotifications.On && sources.value[it.name] != null }
            .map { it.name }
        if (profiles.isEmpty()) return
        sources.update { all -> all + profiles.associateWith { all.getValue(it).with(source, notify) } }
        state.update { it.copy(savingSource = true, sourcesFailure = null) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            for (profile in profiles) {
                when (val result = plugin.setReplySource(id, profile, source, notify)) {
                    is NetResult.Success -> sources.update { it + (profile to result.value) }

                    is NetResult.Failure -> {
                        state.update { it.copy(sourcesFailure = result.failure) }
                        readSources(id, profile)
                    }
                }
            }
            state.update { it.copy(savingSource = false) }
        }
    }

    /** Reads the profile's reply sources when the host's plugin keeps them. */
    private suspend fun readSources(id: DashboardId, profile: String) {
        val served = (plugin.state(id, profile) as? NetResult.Success)?.value as? PluginState.Reported
        if (served?.status?.reports(REPLY_SOURCES) != true) {
            sources.update { it - profile }
            return
        }
        when (val result = plugin.replySources(id, profile)) {
            is NetResult.Success -> sources.update { it + (profile to result.value) }
            is NetResult.Failure -> state.update { it.copy(sourcesFailure = result.failure) }
        }
    }

    /** Changes the shared choices and writes them to every profile on this phone. */
    private fun updatePreferences(change: (PushStore.NotifyPreferences) -> PushStore.NotifyPreferences) {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val rows = state.value.profiles
            val shared = change(AllProfiles.preferences(rows))
            for (profile in AllProfiles.toTurnOff(rows)) push.setPreferences(id, profile, shared)
        }
    }

    private fun change(profile: String, block: suspend (DashboardId) -> Unit) {
        if (state.value.working != null) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            state.update { it.copy(working = profile, problem = null) }
            try {
                block(id)
            } finally {
                state.update { it.copy(working = null) }
            }
        }
    }

    private suspend fun check(id: DashboardId, profile: String) {
        statuses.update { it + (profile to ProfileNotifications.Checking) }
        val status = when (val checked = push.check(id, profile)) {
            is PushResult.Done -> when {
                checked.value.pairedHere -> ProfileNotifications.On
                checked.value.pairedElsewhere -> ProfileNotifications.OnElsewhere
                else -> ProfileNotifications.Off
            }

            is PushResult.Failed -> {
                state.update { it.copy(problem = checked.problem) }
                ProfileNotifications.Off
            }
        }
        statuses.update { it + (profile to status) }
        if (status == ProfileNotifications.On) readSources(id, profile)
    }
}

/** The plugin's capability for reply sources (0.35+). */
private const val REPLY_SOURCES = "reply-sources"

/** [NotificationsUiState.working] while the all-profiles switch turns profiles on or off. */
const val ALL = "*"
