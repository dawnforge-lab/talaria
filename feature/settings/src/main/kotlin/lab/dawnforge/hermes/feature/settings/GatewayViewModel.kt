package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.gateway.GatewayManager
import lab.dawnforge.hermes.core.data.gateway.GatewayStatus
import lab.dawnforge.hermes.core.data.settings.Connector
import lab.dawnforge.hermes.core.data.settings.GatewayHealth
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.RestartMonitor
import lab.dawnforge.hermes.core.data.settings.RestartStage
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

internal data class GatewayUiState(
    val address: String? = null,
    val profile: String? = null,
    val loading: Boolean = true,
    val health: GatewayHealth? = null,
    val healthFailure: ConnectionFailure? = null,
    val connectors: List<Connector> = emptyList(),
    val connectorsFailure: ConnectionFailure? = null,
    /** The restart being followed, or how the last one ended until dismissed. */
    val restart: RestartStage? = null,
    /** The restart command's newest log line. */
    val restartLine: String? = null,
    /** Why asking for the restart failed. */
    val restartFailure: ConnectionFailure? = null,
) {
    val restarting: Boolean get() = restart != null && !restart.finished
}

/**
 * Hermes' gateway as the desktop's status page shows it, and Restart Gateway with its progress,
 * followed by the iOS app's rules ([RestartMonitor]).
 */
@HiltViewModel
internal class GatewayViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val gateway: GatewayStatusRepository,
    private val connection: GatewayManager,
) : ViewModel() {
    private val _state = MutableStateFlow(GatewayUiState())
    val state: StateFlow<GatewayUiState> = _state.asStateFlow()

    /** The phone's own live connection to the dashboard. */
    val link: StateFlow<GatewayStatus> = connection.status

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = dashboards.activeProfile(id).first()
            val address = dashboards.dashboard(id)?.url?.let { it.origin + it.basePath }
            _state.update { it.copy(address = address, profile = profile) }
            coroutineScope {
                val health = async { gateway.health(id) }
                val connectors = async { gateway.connectors(id, profile) }
                val read = health.await()
                val platforms = connectors.await()
                _state.update {
                    it.copy(
                        loading = false,
                        health = (read as? NetResult.Success)?.value ?: it.health,
                        healthFailure = (read as? NetResult.Failure)?.failure,
                        connectors = (platforms as? NetResult.Success)?.value ?: it.connectors,
                        connectorsFailure = (platforms as? NetResult.Failure)?.failure,
                    )
                }
            }
        }
    }

    fun reconnect() = connection.reconnectNow()

    fun restart() {
        if (_state.value.restarting) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(restart = RestartStage.Asking, restartLine = null, restartFailure = null) }
            val before = (gateway.health(id) as? NetResult.Success)?.value
            when (val asked = gateway.restart(id)) {
                is NetResult.Failure -> {
                    _state.update { it.copy(restart = null, restartFailure = asked.failure) }
                    return@launch
                }

                is NetResult.Success -> if (asked.value == null) {
                    _state.update { it.copy(restart = RestartStage.Unsupported) }
                    return@launch
                }
            }
            val startedAt = System.currentTimeMillis()
            val monitor = RestartMonitor(before, startedAt)
            var followAction = true
            while (true) {
                delay(RestartMonitor.POLL_MS)
                val action = if (followAction) {
                    when (val read = gateway.restartAction(id)) {
                        is NetResult.Success -> read.value.also { if (it == null) followAction = false }
                        is NetResult.Failure -> null
                    }
                } else {
                    null
                }
                val health = (gateway.health(id) as? NetResult.Success)?.value
                val stage = monitor.next(System.currentTimeMillis(), action, health)
                _state.update {
                    it.copy(restart = stage, restartLine = monitor.lastLine, health = health ?: it.health)
                }
                if (stage.finished) break
            }
            refresh()
            // The phone's own connection may have dropped while the gateway was down.
            if (connection.status.value.state !is GatewayState.Connected) connection.reconnectNow()
        }
    }

    fun dismissRestart() {
        if (_state.value.restarting) return
        _state.update { it.copy(restart = null, restartLine = null, restartFailure = null) }
    }
}
