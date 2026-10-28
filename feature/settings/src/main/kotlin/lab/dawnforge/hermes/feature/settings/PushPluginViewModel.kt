package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.plugin.HostedPlugin
import lab.dawnforge.hermes.core.data.plugin.PluginState
import lab.dawnforge.hermes.core.data.plugin.PluginStatus
import lab.dawnforge.hermes.core.data.plugin.PluginVersions
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.PluginChange
import lab.dawnforge.hermes.core.data.settings.RestartMonitor
import lab.dawnforge.hermes.core.data.settings.RestartStage
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Where an update of the push plugin stands. */
internal sealed interface PluginUpdate {
    data class Downloading(val installing: Boolean) : PluginUpdate

    data object RestartingGateway : PluginUpdate

    /** The dashboard waits for [count] replying chats before it restarts. */
    data class WaitingForChats(val count: Int) : PluginUpdate

    data object RestartingDashboard : PluginUpdate

    data class Updated(val version: String?) : PluginUpdate

    /** The plugin is on the host, but the dashboard can't restart itself (as on Windows). */
    data class NeedsDashboardRestart(val detail: String?) : PluginUpdate

    data class Failed(val failure: ConnectionFailure?, val detail: String? = null) : PluginUpdate

    /** This Hermes can't update plugins from the app. */
    data object Unsupported : PluginUpdate

    /** The plugin is updated, but the gateway didn't come back. */
    data object GatewayNotBack : PluginUpdate

    /** The dashboard didn't come back after restarting. */
    data object DashboardNotBack : PluginUpdate

    val running: Boolean
        get() = when (this) {
            is Downloading, RestartingGateway, is WaitingForChats, RestartingDashboard -> true
            else -> false
        }
}

internal data class PushPluginUiState(
    val state: PluginState? = null,
    val failure: ConnectionFailure? = null,
    val loading: Boolean = true,
    /** The newest version published, when it could be read. */
    val latest: String? = null,
    val update: PluginUpdate? = null,
    val askingUpdate: Boolean = false,
) {
    val status: PluginStatus? get() = (state as? PluginState.Reported)?.status

    /** What the app offers to update to: the newest published, and never below the one it needs. */
    val offered: String
        get() = PluginVersions.max(latest, PushPluginRepository.RECOMMENDED) ?: PushPluginRepository.RECOMMENDED

    val restartPending: Boolean get() = status?.restartNeeded == true

    /** The plugin lacks something this app uses. */
    val missingFeatures: Boolean get() = status?.let { s -> USED.any { !s.reports(it) } } ?: true

    val updateAvailable: Boolean
        get() = when (val current = state) {
            is PluginState.Reported -> if (restartPending) {
                PluginVersions.older(current.status.version, offered)
            } else {
                missingFeatures || PluginVersions.older(current.status.runningVersion, offered)
            }

            is PluginState.Absent -> current.hosted != HostedPlugin.NotServed

            null -> false
        }

    /** Installing rather than updating: the plugin isn't on the host. */
    val offersInstall: Boolean get() = (state as? PluginState.Absent)?.hosted == HostedPlugin.Missing

    val needsAttention: Boolean
        get() = updateAvailable || restartPending || (state as? PluginState.Absent)?.hosted == HostedPlugin.NotServed

    companion object {
        /** The plugin's capabilities this app uses; one missing means an update is due. */
        val USED = listOf(
            "session-takeover",
            "desktop-views",
            "reply-sources",
            "e2e-notifications",
            "pairing",
            "artifacts",
        )
    }
}

/**
 * The host's push plugin: how it stands, what it serves, and a one-tap update as iOS does it:
 * Hermes updates (or installs) it, restarts its gateway, then the dashboard restarts itself.
 */
@HiltViewModel
internal class PushPluginViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    private val plugin: PushPluginRepository,
    private val connectors: ConnectorsRepository,
    private val gatewayStatus: GatewayStatusRepository,
    private val connection: GatewayManager,
) : ViewModel() {
    private val _state = MutableStateFlow(PushPluginUiState())
    val state: StateFlow<PushPluginUiState> = _state.asStateFlow()
    private var updateJob: Job? = null

    init {
        refresh()
        viewModelScope.launch { _state.update { it.copy(latest = plugin.latestVersion()) } }
    }

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = profiles.activeProfile(id).first()
            _state.update { it.copy(loading = true) }
            when (val result = plugin.state(id, profile)) {
                is NetResult.Success -> _state.update { it.copy(state = result.value, failure = null, loading = false) }
                is NetResult.Failure -> _state.update { it.copy(failure = result.failure, loading = false) }
            }
        }
    }

    fun askUpdate() = _state.update { it.copy(askingUpdate = true) }

    fun cancelAsk() = _state.update { it.copy(askingUpdate = false) }

    fun update() {
        _state.update { it.copy(askingUpdate = false) }
        if (_state.value.update?.running == true) return
        updateJob = viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = profiles.activeProfile(id).first()
            val outcome = runUpdate(id, profile)
            _state.update { it.copy(update = outcome) }
            if (connection.status.value.state !is GatewayState.Connected) connection.reconnectNow()
            refresh()
        }
    }

    /** Stops following the update; whatever Hermes started goes on. */
    fun cancelUpdate() {
        updateJob?.cancel()
        _state.update { it.copy(update = null) }
        refresh()
    }

    fun dismissUpdate() {
        if (_state.value.update?.running == true) return
        _state.update { it.copy(update = null) }
    }

    private suspend fun runUpdate(id: DashboardId, profile: String): PluginUpdate {
        val current = _state.value
        // 1. The plugin's files. A restart already due needs only the restarts.
        if (!current.restartPending || current.updateAvailable) {
            val installing = current.offersInstall
            _state.update { it.copy(update = PluginUpdate.Downloading(installing)) }
            val downloaded = if (installing) installed(plugin.install(id)) else updateFiles(id)
            if (downloaded != null) return downloaded
        }
        // 2. The gateway, which loads the plugin's tools.
        _state.update { it.copy(update = PluginUpdate.RestartingGateway) }
        restartGateway(id)?.let { return it }
        // 3. The dashboard, which serves the plugin's routes.
        return restartDashboard(id, profile)
    }

    /** Updates the plugin's files; null when that went well. */
    private suspend fun updateFiles(id: DashboardId): PluginUpdate? {
        val result = connectors.updatePlugin(id, PushPluginRepository.NAME)
        return when (result) {
            is NetResult.Success -> when (val change = result.value) {
                is PluginChange.NeedsConsent -> PluginUpdate.Failed(null, change.lines.joinToString("\n"))
                else -> null
            }

            is NetResult.Failure -> {
                val detail = (result.failure as? ConnectionFailure.Invalid)?.detail.orEmpty()
                when {
                    detail.contains("not found", ignoreCase = true) -> installed(plugin.install(id))

                    detail.contains("no owned git checkout", ignoreCase = true) ->
                        installed(plugin.install(id, force = true))

                    result.failure is ConnectionFailure.NotFound -> PluginUpdate.Unsupported

                    else -> PluginUpdate.Failed(result.failure)
                }
            }
        }
    }

    private fun installed(result: NetResult<Unit>): PluginUpdate? = when (result) {
        is NetResult.Failure -> PluginUpdate.Failed(result.failure)
        is NetResult.Success -> null
    }

    /** Restarts the gateway and waits for it to be back; null when it is. */
    private suspend fun restartGateway(id: DashboardId): PluginUpdate? {
        val before = (gatewayStatus.health(id) as? NetResult.Success)?.value
        when (val asked = gatewayStatus.restart(id)) {
            is NetResult.Failure -> return PluginUpdate.Failed(asked.failure)
            is NetResult.Success -> if (asked.value == null) return PluginUpdate.Unsupported
        }
        val monitor = RestartMonitor(before, System.currentTimeMillis())
        var followAction = true
        while (true) {
            delay(RestartMonitor.POLL_MS)
            val action = if (followAction) {
                val read = (gatewayStatus.restartAction(id) as? NetResult.Success)?.value
                if (read == null) followAction = false
                read
            } else {
                null
            }
            val health = (gatewayStatus.health(id) as? NetResult.Success)?.value
            val stage = monitor.next(System.currentTimeMillis(), action, health)
            if (stage.finished) {
                return if (stage is RestartStage.Done) null else PluginUpdate.GatewayNotBack
            }
        }
    }

    private suspend fun restartDashboard(id: DashboardId, profile: String): PluginUpdate {
        val status = readStatus(id, profile) ?: return PluginUpdate.NeedsDashboardRestart(null)
        if (!status.restartNeeded) return PluginUpdate.Updated(status.runningVersion)
        if (!status.reports(SELF_RESTART)) return PluginUpdate.NeedsDashboardRestart(null)
        val waitUntil = System.currentTimeMillis() + CHATS_WAIT_MS
        while (true) {
            when (val asked = plugin.restartDashboard(id)) {
                is NetResult.Success -> break

                is NetResult.Failure -> {
                    val failure = asked.failure
                    val code = (failure as? ConnectionFailure.UnexpectedResponse)?.status
                    val detail = (failure as? ConnectionFailure.UnexpectedResponse)?.detail
                    when {
                        code == CONFLICT && System.currentTimeMillis() < waitUntil -> {
                            val count = detail?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() } ?: 1
                            _state.update { it.copy(update = PluginUpdate.WaitingForChats(count)) }
                            delay(CHATS_POLL_MS)
                        }

                        code == NOT_IMPLEMENTED || code == METHOD_NOT_ALLOWED ->
                            return PluginUpdate.NeedsDashboardRestart(detail)

                        failure is ConnectionFailure.NotFound -> return PluginUpdate.NeedsDashboardRestart(detail)

                        else -> return PluginUpdate.Failed(failure)
                    }
                }
            }
        }
        _state.update { it.copy(update = PluginUpdate.RestartingDashboard) }
        val bootBefore = status.bootId
        val until = System.currentTimeMillis() + DASHBOARD_WAIT_MS
        while (System.currentTimeMillis() < until) {
            delay(BOOT_POLL_MS)
            val now = readStatus(id, profile) ?: continue
            if (now.bootId != bootBefore) {
                if (now.restartNeeded) return PluginUpdate.NeedsDashboardRestart(null)
                return PluginUpdate.Updated(now.runningVersion)
            }
        }
        return PluginUpdate.DashboardNotBack
    }

    private suspend fun readStatus(id: DashboardId, profile: String): PluginStatus? {
        repeat(STATUS_TRIES) {
            val state = (plugin.state(id, profile) as? NetResult.Success)?.value
            if (state is PluginState.Reported) return state.status
        }
        return null
    }

    private companion object {
        const val SELF_RESTART = "self-restart"
        const val CONFLICT = 409
        const val NOT_IMPLEMENTED = 501
        const val METHOD_NOT_ALLOWED = 405
        const val CHATS_WAIT_MS = 600_000L
        const val CHATS_POLL_MS = 5_000L
        const val DASHBOARD_WAIT_MS = 120_000L
        const val BOOT_POLL_MS = 2_000L
        const val STATUS_TRIES = 3
    }
}
