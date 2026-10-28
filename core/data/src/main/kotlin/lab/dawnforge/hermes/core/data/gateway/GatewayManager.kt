package lab.dawnforge.hermes.core.data.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.gateway.GatewayLog
import lab.dawnforge.hermes.core.gateway.GatewayNotice
import lab.dawnforge.hermes.core.gateway.GatewayPolicy
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.OkHttpGatewayConnector
import lab.dawnforge.hermes.core.gateway.ServerRequest
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.DashboardClientFactory

/**
 * The live connection of the active dashboard, as the UI sees it. [held] is set while the
 * connection waits for the user to repair it: the state it failed in.
 */
data class GatewayStatus(
    val dashboardId: DashboardId?,
    val state: GatewayState,
    val profile: String?,
    val held: GatewayState? = null,
)

/** A connection stopped for the user to repair, and the state it failed in. */
private data class RepairHold(val dashboardId: DashboardId, val failedState: GatewayState)

/**
 * Owns the chat socket of the active dashboard.
 *
 * The socket is kept while it's in use: the app is in the foreground, or something holds it (a
 * voice conversation out of sight, see [ConnectionHolds]). Otherwise it is closed after
 * [BACKGROUND_GRACE_MS]: Android suspends background network anyway, and on return the gateway's
 * replay fills in what was missed. When the network comes back the client retries at once instead
 * of waiting out its backoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayManager(
    private val scope: CoroutineScope,
    private val repository: DashboardRepository,
    private val signIn: SignInService,
    private val clients: DashboardClientFactory,
    foreground: StateFlow<Boolean>,
    /** Whether something keeps the connection while the app is out of sight ([ConnectionHolds.held]). */
    keptOpen: StateFlow<Boolean>,
    private val networkAvailable: StateFlow<Boolean>,
    private val policy: GatewayPolicy = GatewayPolicy(),
) {
    /** Whether the connection is wanted now: the app is in front, or something holds it. */
    private val inUse: StateFlow<Boolean> = combine(foreground, keptOpen) { shown, kept -> shown || kept }
        .stateIn(scope, SharingStarted.Eagerly, foreground.value || keptOpen.value)

    private class Active(val dashboard: Dashboard, val profile: String, val client: GatewayClient) {
        /** Read by the synchronous header interceptor; refreshed whenever the socket (re)starts. */
        @Volatile
        var cloudflareToken: CloudflareAccessToken? = null
    }

    private val mutex = Mutex()
    private val active = MutableStateFlow<Active?>(null)
    private var backgroundStop: Job? = null

    /**
     * The connection waiting while the user repairs it, as iOS hands recovery to the user: no
     * automatic retry (which could spend password attempts) until the repair reconnects or the
     * user asks for a retry.
     */
    private val held = MutableStateFlow<RepairHold?>(null)

    /** The active dashboard's gateway client, for features that call the gateway. */
    val client: StateFlow<GatewayClient?> = active.map { it?.client }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val status: StateFlow<GatewayStatus> = active.flatMapLatest { current ->
        current?.client?.state?.combine(held) { state, hold ->
            val failed = hold?.takeIf { it.dashboardId == current.dashboard.id }?.failedState
            GatewayStatus(current.dashboard.id, state, current.profile, failed)
        } ?: flowOf(GatewayStatus(null, GatewayState.Stopped, null))
    }.stateIn(scope, SharingStarted.Eagerly, GatewayStatus(null, GatewayState.Stopped, null))

    /** Every event of the active connection, e.g. `sessions.changed` to refresh a list. */
    val events: Flow<GatewayEvent> = active.flatMapLatest { it?.client?.events ?: emptyFlow() }

    /** The active connection's notices: a chat back under a new id, events that could not be replayed. */
    val notices: Flow<GatewayNotice> = active.flatMapLatest { it?.client?.notices ?: emptyFlow() }

    /** The active connection's server requests waiting for an answer (approvals, questions…), by id. */
    val pendingRequests: Flow<Map<String, ServerRequest>> =
        active.flatMapLatest { it?.client?.pendingRequests ?: flowOf(emptyMap()) }

    fun start() {
        scope.launch {
            combine(repository.activeDashboardId, repository.dashboards) { id, list ->
                list.firstOrNull { it.id == id }
            }
                .distinctUntilChanged()
                .flatMapLatest { dashboard ->
                    if (dashboard ==
                        null
                    ) {
                        flowOf(null)
                    } else {
                        repository.activeProfile(dashboard.id).map { dashboard to it }
                    }
                }
                .distinctUntilChanged()
                .collectLatest { target -> switchTo(target?.first, target?.second) }
        }
        scope.launch {
            inUse.collectLatest { wanted ->
                active.value?.client?.foreground = wanted
                if (wanted) {
                    backgroundStop?.cancel()
                    resume()
                } else {
                    backgroundStop = launch {
                        delay(BACKGROUND_GRACE_MS)
                        log.info("connection unused for ${BACKGROUND_GRACE_MS}ms; closing the socket")
                        mutex.withLock { active.value?.client?.stop() }
                    }
                }
            }
        }
        scope.launch {
            networkAvailable.collect { available -> if (available) active.value?.client?.reconnectNow() }
        }
        scope.launch {
            var last: GatewayState? = null
            status.collect { status ->
                if (status.state != last) log.info("gateway ${status.state.describe()}")
                if (status.state is GatewayState.Connected && last !is GatewayState.Connected) watchProfile()
                last = status.state
            }
        }
    }

    /** Retries now: after the user signed in again, or asked to. A connection held for repair stays held. */
    fun reconnectNow() {
        scope.launch { resume() }
    }

    /**
     * Ends a hold for repair: the user asked to reconnect anyway ([reconnect]), or is signing in
     * again, after which the usual reconnect follows.
     */
    fun endHold(reconnect: Boolean) {
        scope.launch {
            held.value = null
            if (reconnect) resume()
        }
    }

    /** Stops the active dashboard's connection and its retries while the user repairs it. */
    fun holdForRepair() {
        scope.launch {
            mutex.withLock {
                val current = active.value ?: return@withLock
                // A repair that failed again keeps the state the connection first failed in.
                val failed = held.value?.takeIf { it.dashboardId == current.dashboard.id }?.failedState
                held.value = RepairHold(current.dashboard.id, failed ?: current.client.state.value)
                current.client.stop()
            }
        }
    }

    /**
     * Ends the hold on [id] once its connection runs on [url] (a repair may have moved it), connects
     * afresh and waits for how that went: connected, or the state that says why not. Null when the
     * dashboard is no longer the active one, or nothing settled within [timeoutMs].
     */
    suspend fun reconnectAfterRepair(
        id: DashboardId,
        url: DashboardUrl,
        timeoutMs: Long = REPAIR_TIMEOUT_MS,
    ): GatewayState? {
        held.value = null
        return withTimeoutOrNull(timeoutMs) {
            val current =
                active.first { it?.dashboard?.id == id && it.dashboard.url == url } ?: return@withTimeoutOrNull null
            val started = mutex.withLock {
                current.client.stop()
                startLocked(current)
                current.client.state.value
            }
            current.client.state.first { state ->
                state !== started &&
                    (
                        state is GatewayState.Connected || state is GatewayState.Waiting ||
                            state is GatewayState.SignInRequired
                        )
            }
        }
    }

    /** Closes and reopens the socket, e.g. after signing out, so the old session's socket goes away. */
    fun restart() {
        scope.launch {
            mutex.withLock {
                val current = active.value ?: return@withLock
                current.client.stop()
                if (inUse.value && held.value?.dashboardId != current.dashboard.id) startLocked(current)
            }
        }
    }

    private suspend fun resume() = mutex.withLock {
        val current = active.value ?: return@withLock
        if (!inUse.value || held.value?.dashboardId == current.dashboard.id) return@withLock
        startLocked(current)
        current.client.reconnectNow()
    }

    private suspend fun switchTo(dashboard: Dashboard?, profile: String?) = mutex.withLock {
        val previous = active.value
        if (previous?.dashboard == dashboard && previous?.profile == profile) return@withLock
        previous?.client?.stop()
        active.value = null
        if (dashboard == null || profile == null) return@withLock

        lateinit var created: Active
        val wsClient = clients.createWebSocketClient(dashboard.url, signIn.upgradeCookies(dashboard.id)) {
            created.cloudflareToken
        }
        val client = GatewayClient(
            scope = scope,
            connector = OkHttpGatewayConnector(wsClient, dashboard.url, policy, log),
            tickets = { signIn.mintGatewayTicket(dashboard.id) },
            readiness = { signIn.probe(dashboard.id) },
            policy = policy,
            // Session-scoped calls carry the profile; a profile switch makes a new client.
            profile = profile,
            log = log,
        )
        client.foreground = inUse.value
        created = Active(dashboard, profile, client)
        active.value = created
        // Another dashboard ends a hold; the held one stays waiting for its repair.
        if (held.value?.dashboardId != dashboard.id) held.value = null
        if (inUse.value && held.value == null) startLocked(created)
    }

    /**
     * Hermes sends `sessions.changed` only for its own profile and profiles a socket has used, so
     * a non-default profile is named once right after connecting.
     */
    private fun watchProfile() {
        val current = active.value ?: return
        if (current.profile == Profile.DEFAULT) return
        scope.launch {
            try {
                current.client.call("session.active_list", buildJsonObject { put("profile", current.profile) })
            } catch (e: Exception) {
                log.warn("could not register the profile for change events", e)
            }
        }
    }

    /**
     * One gateway call for [dashboardId]'s [profile], e.g. answering an approval from a notification
     * while the app is closed. Uses the live socket when it is that dashboard's and connected;
     * otherwise opens a short-lived one, closed again afterwards. Throws when Hermes can't be
     * reached in [timeoutMs] or refuses the call.
     */
    suspend fun callOnce(
        dashboardId: DashboardId,
        profile: String,
        method: String,
        params: JsonObject,
        timeoutMs: Long = ONE_CALL_TIMEOUT_MS,
    ): JsonElement {
        val current = active.value
        if (current != null && current.dashboard.id == dashboardId && current.profile == profile &&
            current.client.state.value is GatewayState.Connected
        ) {
            return current.client.call(method, params)
        }
        val dashboard = repository.dashboard(dashboardId) ?: throw IllegalStateException("The dashboard was removed")
        val cloudflare = repository.cloudflareToken(dashboardId)
        val callScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val client = GatewayClient(
            scope = callScope,
            connector = OkHttpGatewayConnector(
                clients.createWebSocketClient(dashboard.url, signIn.upgradeCookies(dashboardId)) {
                    cloudflare
                },
                dashboard.url,
                policy,
                log,
            ),
            tickets = { signIn.mintGatewayTicket(dashboardId) },
            readiness = { signIn.probe(dashboardId) },
            policy = policy,
            profile = profile,
            log = log,
        )
        client.foreground = true
        try {
            client.start()
            withTimeout(timeoutMs) {
                val state = client.state.first { it is GatewayState.Connected || it is GatewayState.SignInRequired }
                if (state is GatewayState.SignInRequired) throw IllegalStateException("Sign in to the dashboard again")
            }
            return client.call(method, params)
        } finally {
            client.stop()
            callScope.cancel()
        }
    }

    private suspend fun startLocked(current: Active) {
        current.cloudflareToken = repository.cloudflareToken(current.dashboard.id)
        current.client.start()
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L

        /** A reconnect after a repair: a ticket, the socket and `gateway.ready`, with room to spare. */
        const val REPAIR_TIMEOUT_MS = 30_000L

        /** Room to open a socket on a waking phone, sign in again if needed, and get an answer. */
        const val ONE_CALL_TIMEOUT_MS = 30_000L
        const val TAG = "HermesGateway"

        val log = object : GatewayLog {
            override fun info(message: String) {
                Log.i(TAG, message)
            }

            override fun warn(message: String, error: Throwable?) {
                Log.w(TAG, message, error)
            }
        }

        fun GatewayState.describe(): String = when (this) {
            is GatewayState.Connected -> "connected (epoch ${ready.replayEpoch})"
            is GatewayState.Connecting -> "connecting (attempt $attempt)"
            is GatewayState.Waiting -> "waiting to retry (attempt $attempt): $failure"
            is GatewayState.SignInRequired -> "needs sign-in: $reason"
            GatewayState.Stopped -> "stopped"
        }
    }
}
