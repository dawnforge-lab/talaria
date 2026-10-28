package lab.dawnforge.hermes.feature.connect.dashboards

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.AppUnlock
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.dashboard.ProxyHeadersStore
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.signin.SessionState
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.ProxyHeader
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.feature.connect.PhoneUnlock
import lab.dawnforge.hermes.feature.connect.allValid
import lab.dawnforge.hermes.feature.connect.navigation.RepairRoute
import lab.dawnforge.hermes.feature.connect.setup.RepairFocus
import lab.dawnforge.hermes.feature.connect.setup.repairRoute

sealed interface SessionUi {
    data object Checking : SessionUi

    data class Ready(val hermesVersion: String?) : SessionUi

    data class NeedsSignIn(val reason: ConnectionFailure?) : SessionUi

    data class Unreachable(val failure: ConnectionFailure) : SessionUi
}

data class DashboardsUiState(
    val loading: Boolean = true,
    val active: Dashboard? = null,
    val session: SessionUi = SessionUi.Checking,
    val others: List<Dashboard> = emptyList(),
    val pendingRemoval: Dashboard? = null,
    /** The active dashboard's live chat connection. */
    val live: GatewayState = GatewayState.Stopped,
    /** Set while the connection waits for the user to repair it: the state it failed in. */
    val liveHeld: GatewayState? = null,
)

/** The saved dashboards, and whether the active one's session still works. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardsViewModel @Inject constructor(
    private val repository: DashboardRepository,
    private val signIn: SignInService,
    private val gateway: GatewayManager,
    private val proxyHeaders: ProxyHeadersStore,
    private val unlock: AppUnlock,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val canUnlock = PhoneUnlock.available(context)

    /**
     * Whether the active dashboard opens with the phone's unlock; null when it can't (no saved
     * password, or no fingerprint, face or screen lock on the phone).
     */
    val unlockSetting: StateFlow<Boolean?> = combine(repository.activeDashboardId, repository.unlockRequired) {
            id,
            required,
        ->
        id?.takeIf { canUnlock && repository.credentials(it) != null }?.let { it in required }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setUnlock(on: Boolean) {
        val id = state.value.active?.id ?: return
        viewModelScope.launch {
            repository.setUnlockRequired(id, on)
            // Turned on here, with the app already open: it asks from the next start.
            unlock.unlocked(id)
        }
    }

    private val _headers = MutableStateFlow<List<ProxyHeader>?>(null)

    /** The active dashboard's extra headers being edited, or null when the editor is closed. */
    val headers: StateFlow<List<ProxyHeader>?> = _headers.asStateFlow()

    private val refreshes = MutableStateFlow(0)
    private val pendingRemoval = MutableStateFlow<Dashboard?>(null)

    private val session = combine(repository.activeDashboardId, refreshes) { id, _ -> id }
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<SessionUi>(SessionUi.Checking)
            } else {
                flow<SessionUi> {
                    emit(SessionUi.Checking)
                    emit(signIn.checkSession(id).toUi())
                }
            }
        }
        // A session that works again (signed in, or renewed) should not wait out the socket's backoff.
        .onEach { if (it is SessionUi.Ready) gateway.reconnectNow() }

    val state: StateFlow<DashboardsUiState> = combine(
        repository.dashboards,
        repository.activeDashboardId,
        session,
        pendingRemoval,
        gateway.status,
    ) { dashboards, activeId, session, removal, live ->
        val active = dashboards.firstOrNull { it.id == activeId }
        DashboardsUiState(
            loading = false,
            active = active,
            session = session,
            others = dashboards.filter { it.id != activeId }.sortedByDescending { it.addedAt },
            pendingRemoval = removal,
            live = if (live.dashboardId == activeId) live.state else GatewayState.Stopped,
            liveHeld = live.held.takeIf { live.dashboardId == activeId },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardsUiState())

    /** Retry, which the user asked for: it ends a hold for repair too. */
    fun retry() {
        refreshes.update { it + 1 }
        gateway.endHold(reconnect = true)
    }

    /** Repair connection for the active dashboard, or with [edit] its Connection setup. */
    fun repairRoute(edit: Boolean): RepairRoute? {
        val id = state.value.active?.id ?: return null
        return gateway.status.value.takeIf { it.dashboardId == id }?.repairRoute(edit)
            ?: RepairRoute(id.value, if (edit) RepairFocus.Details else RepairFocus.Test, edit)
    }

    fun use(id: DashboardId) {
        viewModelScope.launch { repository.setActive(id) }
    }

    fun signOutActive() {
        val active = state.value.active ?: return
        viewModelScope.launch {
            signIn.signOut(active.id)
            unlock.forget(active.id)
            // The open socket was authorised by the old session; drop it.
            gateway.restart()
            refreshes.update { it + 1 }
        }
    }

    /** Opens the active dashboard's extra headers (an HTTPS dashboard's only: they're never sent otherwise). */
    fun editHeaders() {
        val origin = state.value.active?.url?.let(ProxyHeaderRules::origin) ?: return
        viewModelScope.launch { _headers.value = proxyHeaders.rows(origin) }
    }

    fun changeHeaders(rows: List<ProxyHeader>) = _headers.update { rows }

    fun closeHeaders() = _headers.update { null }

    /** Saves them; the live connection starts again so the socket carries them too. */
    fun saveHeaders() {
        val rows = _headers.value ?: return
        val origin = state.value.active?.url?.let(ProxyHeaderRules::origin) ?: return
        if (!rows.allValid()) return
        viewModelScope.launch {
            proxyHeaders.save(origin, rows)
            _headers.value = null
            gateway.restart()
            refreshes.update { it + 1 }
        }
    }

    fun askToRemove(dashboard: Dashboard) = pendingRemoval.update { dashboard }

    fun cancelRemoval() = pendingRemoval.update { null }

    fun confirmRemoval() {
        val dashboard = pendingRemoval.value ?: return
        pendingRemoval.update { null }
        viewModelScope.launch { signIn.remove(dashboard.id) }
    }

    private fun SessionState?.toUi(): SessionUi = when (this) {
        null -> SessionUi.Checking
        is SessionState.Ready -> SessionUi.Ready(hermesVersion)
        is SessionState.NeedsSignIn -> SessionUi.NeedsSignIn(reason)
        is SessionState.Unreachable -> SessionUi.Unreachable(failure)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
