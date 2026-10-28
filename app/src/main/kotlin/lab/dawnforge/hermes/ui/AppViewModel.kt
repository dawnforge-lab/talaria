package lab.dawnforge.hermes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.dashboard.AppUnlock
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.net.SessionLink
import lab.dawnforge.hermes.core.push.HermesNotification
import lab.dawnforge.hermes.core.push.PushLinkInbox
import lab.dawnforge.hermes.feature.connect.ConnectLinkInbox
import lab.dawnforge.hermes.feature.connect.navigation.RepairRoute
import lab.dawnforge.hermes.feature.connect.setup.repairRoute
import lab.dawnforge.hermes.launch.LaunchInbox

sealed interface AppStart {
    data object Loading : AppStart

    data object NoDashboard : AppStart

    data object HasDashboard : AppStart
}

/** Decides the first screen, and passes on setup links and tapped notifications that arrive while the app runs. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: DashboardRepository,
    val linkInbox: ConnectLinkInbox,
    val pushInbox: PushLinkInbox,
    val sessionLinks: SessionLinkInbox,
    val sharedOpens: SharedOpenInbox,
    val launches: LaunchInbox,
    private val profiles: ProfileRepository,
    private val sessions: SessionRepository,
    private val bots: BotsRepository,
    private val gateway: GatewayManager,
    private val unlock: AppUnlock,
    private val signIn: SignInService,
) : ViewModel() {
    /** The dashboard waiting for the phone's unlock before it shows, or null. */
    val locked: StateFlow<DashboardId?> = unlock.locked.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun unlocked(id: DashboardId) = unlock.unlocked(id)

    /** Repair connection for the active dashboard, opened where its failure points. */
    fun repairRoute(): RepairRoute? = gateway.status.value.repairRoute()

    /** Signs out of the locked dashboard instead: its saved sign-in goes, and the lock with it. */
    suspend fun signOutLocked(id: DashboardId): Dashboard? {
        val dashboard = repository.dashboard(id)
        signIn.signOut(id)
        unlock.forget(id)
        gateway.restart()
        return dashboard
    }

    val start: StateFlow<AppStart> = repository.activeDashboardId
        .map { if (it == null) AppStart.NoDashboard else AppStart.HasDashboard }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppStart.Loading)

    /**
     * Makes a tapped notification's dashboard and profile the active ones, which the chat screen
     * reads. False when the dashboard is no longer saved.
     */
    suspend fun prepare(target: HermesNotification.Target): Boolean {
        repository.dashboard(target.dashboardId) ?: return false
        repository.setActive(target.dashboardId)
        repository.setActiveProfile(target.dashboardId, target.profile)
        return true
    }

    /**
     * Makes a chat link's dashboard and profile the active ones, as iOS opens them: the link's
     * dashboard (else the active one) and profile (else the active one), checked against the
     * dashboard's profiles when they can be read. A bot's chat opens as its Bot Chat.
     */
    suspend fun prepare(link: SessionLink): LinkOpening {
        val dashboard = link.dashboard?.let { DashboardId(it) } ?: repository.activeDashboardId.first()
            ?: return LinkOpening.Unavailable
        repository.dashboard(dashboard) ?: return LinkOpening.Unavailable
        val profile = link.profile ?: repository.activeProfile(dashboard).first()
        val known = (profiles.profiles(dashboard) as? NetResult.Success)?.value?.map { it.name }
        if (known != null && profile !in known) return LinkOpening.Unavailable
        // A chat that's gone says so; one that can't be checked (offline) still opens.
        val found = (sessions.exists(dashboard, profile, link.sessionId) as? NetResult.Success)?.value
        if (found == false) return LinkOpening.Gone
        repository.setActive(dashboard)
        repository.setActiveProfile(dashboard, profile)
        val bot = (bots.state.value as? BotsState.Available)?.roster?.bots?.firstOrNull { bot ->
            bot.name == profile &&
                bot.canonicalSession?.let { link.sessionId == it.id || link.sessionId == it.resolvedId } == true
        }
        return LinkOpening.Open(profile, link.sessionId, bot?.label, botChat = bot != null)
    }

    /**
     * A new chat's live id on [dashboard], once its connection is up (20 s at most): a chat opened
     * without ids never attaches, so a share's message would only wait. Null when it couldn't start.
     */
    suspend fun startChat(dashboard: DashboardId, profile: String): String? {
        withTimeoutOrNull(CONNECT_WAIT_MS) {
            gateway.status.first { it.dashboardId == dashboard && it.state is GatewayState.Connected }
        } ?: return null
        return (sessions.startChat(profile) as? NetResult.Success)?.value?.runtimeId
    }

    /**
     * A new chat for the widget, a shortcut or the tile: on its dashboard and profile when it names
     * them, else the ones the app shows. Answers the profile and the chat's live id.
     */
    suspend fun startNewChat(dashboard: String?, profile: String?): Pair<String, String>? {
        val id = dashboard?.let(::DashboardId) ?: repository.activeDashboardId.first() ?: return null
        repository.dashboard(id) ?: return null
        val name = profile ?: repository.activeProfile(id).first()
        repository.setActive(id)
        repository.setActiveProfile(id, name)
        return startChat(id, name)?.let { name to it }
    }

    private companion object {
        const val CONNECT_WAIT_MS = 20_000L
    }
}

/** Where opening a chat link got to. */
sealed interface LinkOpening {
    data class Open(val profile: String, val sessionId: String, val title: String?, val botChat: Boolean) : LinkOpening

    /** Its dashboard or profile isn't here. */
    data object Unavailable : LinkOpening

    /** Hermes no longer has the chat. */
    data object Gone : LinkOpening
}
