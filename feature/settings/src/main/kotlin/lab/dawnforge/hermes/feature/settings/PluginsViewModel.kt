package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AgentPlugin
import lab.dawnforge.hermes.core.data.settings.CatalogPlugin
import lab.dawnforge.hermes.core.data.settings.ConnectorsRepository
import lab.dawnforge.hermes.core.data.settings.PluginChange
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What a plugin change came to, said once. */
internal sealed interface PluginNote {
    data class UpToDate(val name: String) : PluginNote

    data class Updated(val name: String, val restart: Boolean) : PluginNote

    data class Installed(val name: String, val restart: Boolean, val missingKeys: List<String>) : PluginNote

    data class Failed(val name: String, val failure: ConnectionFailure) : PluginNote
}

internal data class PluginsUiState(
    val profile: String? = null,
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val plugins: List<AgentPlugin> = emptyList(),
    val catalog: List<CatalogPlugin> = emptyList(),
    /** Plugins with a change in flight, by name. */
    val busy: Set<String> = emptySet(),
    /** An update asking for more, awaiting the user's yes: the plugin and what it adds. */
    val consent: Pair<AgentPlugin, List<String>>? = null,
    val note: PluginNote? = null,
    /** A change since the gateway last started needs a restart to apply. */
    val needsRestart: Boolean = false,
) {
    /** Plugins belong to the host's default profile; another profile only looks. */
    val canManage: Boolean get() = profile == null || profile == Profile.DEFAULT

    fun catalogEntry(plugin: AgentPlugin): CatalogPlugin? =
        catalog.firstOrNull { it.name == plugin.name || it.name == plugin.folder }

    fun installed(query: String): List<AgentPlugin> = plugins.filter { it.matches(query) }

    /** Catalog plugins not installed, official ones first. */
    fun available(query: String): List<CatalogPlugin> {
        val names = plugins.flatMap { listOf(it.name, it.folder) }.toSet()
        return catalog.filter { !it.installed && it.name !in names && it.matches(query) }
            .sortedWith(compareBy({ if (it.tier == "official") 0 else 1 }, { it.title.lowercase() }))
    }
}

private fun AgentPlugin.matches(query: String) = query.isBlank() ||
    listOf(name, description).any { it.contains(query.trim(), ignoreCase = true) }

private fun CatalogPlugin.matches(query: String) = query.isBlank() ||
    listOf(name, title, description).any { it.contains(query.trim(), ignoreCase = true) }

/** The host's agent plugins and Hermes' reviewed catalog of them. */
@HiltViewModel
internal class PluginsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val connectors: ConnectorsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            _state.update { it.copy(profile = profile) }
            coroutineScope {
                val catalog = async { connectors.pluginCatalog(id) }
                when (val plugins = connectors.plugins(id, profile)) {
                    is NetResult.Failure -> _state.update { it.copy(loading = false, failure = plugins.failure) }

                    is NetResult.Success -> _state.update {
                        it.copy(loading = false, failure = null, plugins = plugins.value)
                    }
                }
                (catalog.await() as? NetResult.Success)?.let { read -> _state.update { it.copy(catalog = read.value) } }
            }
        }
    }

    fun setEnabled(plugin: AgentPlugin, enabled: Boolean) = change(plugin.name) { id ->
        connectors.setPlugin(id, plugin.name, enabled).also { result ->
            if (result is NetResult.Success) {
                _state.update { state ->
                    state.copy(
                        plugins = state.plugins.map {
                            if (it.name == plugin.name) it.copy(status = if (enabled) "enabled" else "disabled") else it
                        },
                    )
                }
            }
        }
    }

    /** Checks for and applies an update; [acceptMore] after the user agreed to what it adds. */
    fun update(plugin: AgentPlugin, acceptMore: Boolean = false) = change(plugin.name) { id ->
        connectors.updatePlugin(id, plugin.folder, acceptMore).also { result ->
            val change = (result as? NetResult.Success)?.value ?: return@also
            _state.update {
                when (change) {
                    is PluginChange.NeedsConsent -> it.copy(consent = plugin to change.lines)
                    PluginChange.Unchanged -> it.copy(note = PluginNote.UpToDate(plugin.name))
                    is PluginChange.Done -> it.copy(note = PluginNote.Updated(plugin.name, change.restart))
                }
            }
        }
    }

    fun dismissConsent() = _state.update { it.copy(consent = null) }

    fun acceptConsent() {
        val plugin = _state.value.consent?.first ?: return
        _state.update { it.copy(consent = null) }
        update(plugin, acceptMore = true)
    }

    fun uninstall(plugin: AgentPlugin) = change(plugin.name) { id -> connectors.uninstallPlugin(id, plugin.folder) }

    fun install(entry: CatalogPlugin) = change(entry.name) { id ->
        connectors.installPlugin(id, entry.name).also { result ->
            val change = (result as? NetResult.Success)?.value as? PluginChange.Done ?: return@also
            _state.update { it.copy(note = PluginNote.Installed(entry.title, change.restart, change.missingKeys)) }
        }
    }

    fun noteShown() = _state.update { it.copy(note = null) }

    /** The gateway restarted: plugin changes are live. */
    fun restarted() = _state.update { it.copy(needsRestart = false) }

    private fun change(name: String, block: suspend (DashboardId) -> NetResult<PluginChange>) {
        val state = _state.value
        if (!state.canManage || name in state.busy) return
        _state.update { it.copy(busy = it.busy + name) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val result = block(id)
            _state.update {
                when (result) {
                    is NetResult.Failure -> it.copy(
                        busy = it.busy - name,
                        note = PluginNote.Failed(name, result.failure),
                    )

                    is NetResult.Success -> it.copy(
                        busy = it.busy - name,
                        needsRestart = it.needsRestart || (result.value as? PluginChange.Done)?.restart == true,
                    )
                }
            }
            if (result is NetResult.Success) refresh()
        }
    }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }
}
