package lab.dawnforge.hermes.feature.connect.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.gateway.GatewayStatus
import lab.dawnforge.hermes.core.data.signin.ConnectionTest
import lab.dawnforge.hermes.core.data.signin.RepairSave
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.data.signin.TestOutcome
import lab.dawnforge.hermes.core.gateway.GatewayFailure
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.HandshakeFailure
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.feature.connect.navigation.RepairRoute

/** Where a repair opens, on top of the editable address, from what failed. */
enum class RepairFocus { Details, Credentials, Tls, Cloudflare, Test }

/** Why a repair's reconnect didn't work. */
sealed interface ReconnectFailure {
    /** Another saved dashboard already has the tested address. */
    data object AddressInUse : ReconnectFailure

    /** Saved, but the live connection didn't open; [failure] says why, when known. */
    data class NotConnected(val failure: ConnectionFailure?) : ReconnectFailure
}

/** The repair's focus for what the live connection last said, as iOS chooses it. */
fun GatewayState.repairFocus(): RepairFocus {
    val failure = connectionFailure() ?: return RepairFocus.Test
    if (failure is ConnectionFailure.SessionTicketFailure) return RepairFocus.Test
    return when (failure.troubleshootStart()) {
        SetupStart.Credentials -> RepairFocus.Credentials
        SetupStart.Tls -> RepairFocus.Tls
        SetupStart.Cloudflare -> RepairFocus.Cloudflare
        SetupStart.AccessMethod, SetupStart.Dashboard -> RepairFocus.Details
        null -> RepairFocus.Test
    }
}

/** Repair connection for the active dashboard, opened where its failure points; null with none. */
fun GatewayStatus.repairRoute(edit: Boolean = false): RepairRoute? =
    dashboardId?.let { RepairRoute(it.value, if (edit) RepairFocus.Details else (held ?: state).repairFocus(), edit) }

/** What the live connection's state says went wrong, as a connection failure. */
fun GatewayState.connectionFailure(): ConnectionFailure? = when (this) {
    is GatewayState.SignInRequired -> reason ?: ConnectionFailure.SignInRequired

    is GatewayState.Waiting -> when (val failure = failure) {
        is GatewayFailure.NotReachable -> failure.failure

        is GatewayFailure.Ticket -> failure.failure

        is GatewayFailure.Handshake -> when (val handshake = failure.failure) {
            is HandshakeFailure.Network -> handshake.failure
            HandshakeFailure.SignInRequired -> ConnectionFailure.SignInRequired
            is HandshakeFailure.Unavailable -> ConnectionFailure.DashboardUnavailable(handshake.status)
            else -> null
        }

        is GatewayFailure.Lost -> null
    }

    else -> null
}

/**
 * Repair connection, as iOS's: the setup wizard started from the dashboard whose connection
 * failed, with its saved address and sign-in, on top of the editable address. While it runs the
 * connection waits ([GatewayManager.holdForRepair]); closing it leaves the connection waiting for
 * another repair or a retry, as on iOS. Reconnect now saves what was tested and reconnects, and
 * puts the address back if that fails.
 *
 * In [WizardMode.Edit] (Connection setup for a working dashboard) nothing is held.
 */
@HiltViewModel
class RepairWizardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    connectionTest: ConnectionTest,
    private val repository: DashboardRepository,
    private val signIn: SignInService,
    private val gateway: GatewayManager,
) : WizardViewModel(
    connectionTest,
    savedStateHandle.toRoute<RepairRoute>().let { route ->
        SetupUiState(
            path = listOf(WizardStep.Details) + when (route.focus) {
                RepairFocus.Details -> emptyList()
                RepairFocus.Credentials -> listOf(WizardStep.Credentials)
                RepairFocus.Tls -> listOf(WizardStep.Tls)
                RepairFocus.Cloudflare -> listOf(WizardStep.Cloudflare)
                RepairFocus.Test -> listOf(WizardStep.Test)
            },
            draft = SetupDraft(useExisting = true),
        )
    },
) {
    private val route = savedStateHandle.toRoute<RepairRoute>()
    private val id = DashboardId(route.dashboard)

    override val mode = if (route.edit) WizardMode.Edit else WizardMode.Repair

    private var savedToken: CloudflareAccessToken? = null
    private var savedAddress: String? = null

    init {
        if (mode == WizardMode.Repair) gateway.holdForRepair()
        viewModelScope.launch {
            val dashboard = repository.dashboard(id)
            if (dashboard == null) {
                close()
                return@launch
            }
            val credentials = repository.credentials(id)
            savedToken = repository.cloudflareToken(id)
            savedAddress = dashboard.url.toString()
            mutableState.update {
                it.copy(
                    draft = it.draft.copy(
                        existingAddress = dashboard.url.toString(),
                        username = credentials?.username ?: dashboard.username.orEmpty(),
                        password = credentials?.password.orEmpty(),
                    ),
                )
            }
        }
    }

    /** The saved token goes only to the server it was saved for. */
    override suspend fun cloudflareTokenFor(address: String): CloudflareAccessToken? {
        val saved = savedAddress ?: return null
        val tested = (ConnectionUrlPolicy.check(address) as? AddressCheck.Valid)?.candidates?.firstOrNull()
        val original = (ConnectionUrlPolicy.check(saved) as? AddressCheck.Valid)?.candidates?.firstOrNull()
        val same = tested != null && original != null && tested.scheme == original.scheme &&
            tested.host.equals(original.host, ignoreCase = true) && tested.port == original.port
        return savedToken.takeIf { same }
    }

    /** Reconnect now: saves the tested address and session (used once), then reconnects. */
    fun reconnect() {
        val current = state.value
        val outcome = current.outcome as? TestOutcome.Ready ?: return
        if (current.reconnecting || current.reconnectFailure != null) return
        mutableState.update { it.copy(reconnecting = true) }
        viewModelScope.launch {
            val failure = when (
                val saved = signIn.applyRepair(
                    id,
                    outcome.url,
                    outcome.session,
                    current.draft.username,
                    current.draft.password,
                )
            ) {
                RepairSave.Gone -> {
                    close()
                    return@launch
                }

                RepairSave.AddressInUse -> ReconnectFailure.AddressInUse

                is RepairSave.Saved -> {
                    val result = gateway.reconnectAfterRepair(id, saved.updated.url)
                    if (result is GatewayState.Connected) {
                        mutableState.update { it.copy(reconnecting = false) }
                        eventChannel.send(SetupEvent.Reconnected)
                        return@launch
                    }
                    signIn.restoreAddress(saved.previous)
                    // Back to waiting for the user, as on iOS: no automatic retry loop.
                    if (mode == WizardMode.Repair) gateway.holdForRepair()
                    ReconnectFailure.NotConnected(result?.connectionFailure())
                }
            }
            mutableState.update { it.copy(reconnecting = false, reconnectFailure = failure) }
        }
    }

    /** Sign in to reconnect: the sign-in screen for the tested address, which saves the new session. */
    fun signInToReconnect() {
        val current = state.value
        val address = current.testedAddress ?: return
        cancelTest()
        // Signing in ends the repair; the usual reconnect follows the sign-in.
        gateway.endHold(reconnect = false)
        eventChannel.trySend(SetupEvent.SignIn(address, current.draft.username.trim().ifEmpty { null }))
    }

    /** Test connection again, after a reconnect used the tested session. */
    fun testAgain() {
        goTo(WizardStep.Test)
        runTest()
    }
}
