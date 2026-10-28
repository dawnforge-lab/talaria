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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.McpCatalogEntry
import lab.dawnforge.hermes.core.data.settings.McpInstall
import lab.dawnforge.hermes.core.data.settings.McpProbe
import lab.dawnforge.hermes.core.data.settings.McpServer
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What a server's last test found. */
internal sealed interface ServerState {
    data object Off : ServerState

    data object Checking : ServerState

    /** Not tested yet. */
    data object Unknown : ServerState

    data class On(val tools: List<Pair<String, String>>) : ServerState

    data object NeedsSignIn : ServerState

    data class Failed(val error: String) : ServerState
}

internal fun McpProbe.state(): ServerState = when {
    ok -> ServerState.On(tools)
    needsSignIn -> ServerState.NeedsSignIn
    else -> ServerState.Failed(error.orEmpty())
}

internal data class ConnectorsUiState(
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val servers: List<McpServer> = emptyList(),
    val catalog: List<McpCatalogEntry> = emptyList(),
    val states: Map<String, ServerState> = emptyMap(),
    /** Catalog entries being installed. */
    val installing: Set<String> = emptySet(),
    val message: ConnectorMessage? = null,
) {
    fun state(server: McpServer): ServerState =
        if (!server.enabled) ServerState.Off else states[server.name] ?: ServerState.Unknown

    fun connected(query: String): List<McpServer> = servers.filter { it.enabled && it.matches(query) }
        .sortedWith(
            compareBy({
                if (state(it) is ServerState.Failed ||
                    state(it) == ServerState.NeedsSignIn
                ) {
                    0
                } else {
                    1
                }
            }, { it.name }),
        )

    fun turnedOff(query: String): List<McpServer> = servers.filter {
        !it.enabled && it.matches(query)
    }.sortedBy { it.name }

    /** Catalog servers not installed or configured yet. */
    fun available(query: String): List<McpCatalogEntry> {
        val configured = servers.map(McpServer::name).toSet()
        return catalog.filter { !it.installed && it.name !in configured && it.matches(query) }.sortedBy { it.name }
    }

    /** True when a configured server came from the catalog. */
    fun fromCatalog(server: McpServer): Boolean = catalog.any { it.name == server.name }
}

/** A change to a server that went wrong, said once: a failure to reach Hermes, or Hermes' own words. */
internal data class ConnectorMessage(
    val what: Int,
    val subject: String,
    val failure: ConnectionFailure? = null,
    val detail: String = "",
)

private fun McpServer.matches(query: String) = query.isBlank() ||
    listOfNotNull(name, url, command).any { it.contains(query.trim(), ignoreCase = true) }

private fun McpCatalogEntry.matches(query: String) = query.isBlank() ||
    listOf(name, description).any { it.contains(query.trim(), ignoreCase = true) }

/**
 * The active profile's MCP servers and Hermes' catalog of them. Each enabled server is tested when
 * the list opens (unless it was tested in the last [RETEST_MS]), [PROBES] at a time.
 */
@HiltViewModel
internal class ConnectorsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val connectors: ConnectorsRepository,
    private val gateway: GatewayStatusRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectorsUiState())
    val state: StateFlow<ConnectorsUiState> = _state.asStateFlow()

    private val testedAt = mutableMapOf<String, Long>()
    private val probes = Semaphore(PROBES)

    /** Reads the list; [retest] tests every enabled server again, not just stale ones. */
    fun refresh(retest: Boolean = false) {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            coroutineScope {
                val catalog = async { connectors.catalog(id, profile) }
                when (val servers = connectors.servers(id, profile)) {
                    is NetResult.Failure -> _state.update { it.copy(loading = false, failure = servers.failure) }

                    is NetResult.Success -> _state.update {
                        it.copy(loading = false, failure = null, servers = servers.value)
                    }
                }
                (catalog.await() as? NetResult.Success)?.let { read -> _state.update { it.copy(catalog = read.value) } }
            }
            val now = System.currentTimeMillis()
            _state.value.servers.filter { it.enabled }
                .filter { retest || now - (testedAt[it.name] ?: 0) > RETEST_MS }
                .forEach { test(it.name) }
        }
    }

    fun test(name: String) {
        _state.update { it.copy(states = it.states + (name to ServerState.Checking)) }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val probe = probes.withPermit { connectors.test(id, profile, name) }
            testedAt[name] = System.currentTimeMillis()
            _state.update { state ->
                state.copy(
                    states = state.states + (
                        name to when (probe) {
                            is NetResult.Success -> probe.value.state()
                            is NetResult.Failure -> ServerState.Failed("")
                        }
                        ),
                )
            }
        }
    }

    fun setEnabled(server: McpServer, enabled: Boolean) {
        if (server.plugin != null) return
        _state.update { state ->
            state.copy(
                servers = state.servers.map {
                    if (it.name ==
                        server.name
                    ) {
                        it.copy(enabled = enabled)
                    } else {
                        it
                    }
                },
            )
        }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            when (val result = connectors.setEnabled(id, profile, server.name, enabled)) {
                is NetResult.Success -> if (enabled) {
                    test(server.name)
                } else {
                    _state.update {
                        it.copy(
                            states =
                                it.states - server.name,
                        )
                    }
                }

                is NetResult.Failure -> _state.update { state ->
                    state.copy(
                        servers = state.servers.map { if (it.name == server.name) it.copy(enabled = !enabled) else it },
                        message = ConnectorMessage(R.string.feature_settings_mcp_failed, server.name, result.failure),
                    )
                }
            }
        }
    }

    /** Installs a catalog server with [keys], blanks left out, then tests it. */
    fun install(entry: McpCatalogEntry, keys: Map<String, String>) {
        if (entry.name in _state.value.installing) return
        _state.update { it.copy(installing = it.installing + entry.name) }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val filled = keys.mapValues { it.value.trim() }.filterValues(String::isNotEmpty)
            val message = when (val result = connectors.install(id, profile, entry.name, filled)) {
                is NetResult.Failure -> ConnectorMessage(
                    R.string.feature_settings_mcp_install_failed,
                    entry.name,
                    result.failure,
                )

                is NetResult.Success -> (result.value as? McpInstall.Running)?.let {
                    follow(id, it.action)
                }?.let { detail ->
                    ConnectorMessage(R.string.feature_settings_mcp_install_failed, entry.name, detail = detail)
                }
            }
            _state.update { it.copy(installing = it.installing - entry.name, message = message) }
            refresh()
        }
    }

    /** Follows a background install; null when it succeeded, else why not. */
    private suspend fun follow(id: DashboardId, action: String): String? {
        var misses = 0
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < INSTALL_LIMIT_MS) {
            delay(POLL_MS)
            when (val read = gateway.action(id, action)) {
                is NetResult.Failure -> if (++misses >= MISSES) return ""

                is NetResult.Success -> {
                    misses = 0
                    val status = read.value ?: return ""
                    if (!status.running) return if (status.succeeded) null else status.reason.orEmpty()
                }
            }
        }
        return ""
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }

    private companion object {
        const val PROBES = 3
        const val RETEST_MS = 300_000L
        const val POLL_MS = 1_200L
        const val MISSES = 4
        const val INSTALL_LIMIT_MS = 900_000L
    }
}
