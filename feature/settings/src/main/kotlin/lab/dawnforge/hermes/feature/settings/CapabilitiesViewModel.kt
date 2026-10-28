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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.CatalogSkill
import lab.dawnforge.hermes.core.data.settings.Skill
import lab.dawnforge.hermes.core.data.settings.Toolset
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

enum class CapabilitiesTab { Skills, Tools, Connectors, Plugins }

/** A switch whose save failed, shown once. */
internal data class ToggleFailure(val name: String, val failure: ConnectionFailure)

internal data class CapabilitiesUiState(
    val profile: String? = null,
    val tab: CapabilitiesTab = CapabilitiesTab.Skills,
    val query: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: ConnectionFailure? = null,
    val skills: List<Skill> = emptyList(),
    val catalog: List<CatalogSkill> = emptyList(),
    val toolsets: List<Toolset> = emptyList(),
    val usage: Map<String, Long> = emptyMap(),
    val skillsMostUsedFirst: Boolean = true,
    val toolsMostUsedFirst: Boolean = true,
    val toggleFailure: ToggleFailure? = null,
) {
    /** A toolset's calls: the sum of its tools'. */
    fun calls(toolset: Toolset): Long = toolset.tools.sumOf { usage[it] ?: 0 }

    val shownSkills: List<Skill>
        get() = skills.filter { it.matches(query) }.sortedWith(
            compareBy<Skill> { if (skillsMostUsedFirst) -it.usage else it.usage }.thenBy { it.name.lowercase() },
        )

    /** The catalog's skills that aren't installed, A to Z. */
    val shownCatalog: List<CatalogSkill>
        get() {
            val installed = skills.map(Skill::name).toSet()
            return catalog
                .filter { !it.installed && it.name !in installed && it.matches(query) }
                .sortedBy { it.name.lowercase() }
        }

    val shownToolsets: List<Toolset>
        get() = toolsets.filter { it.matches(query) }.sortedWith(
            compareBy<Toolset> { if (toolsMostUsedFirst) -calls(it) else calls(it) }.thenBy { it.label.lowercase() },
        )
}

private fun Skill.matches(query: String) = query.isBlank() ||
    listOf(name, description, category).any { it.contains(query.trim(), ignoreCase = true) }

private fun CatalogSkill.matches(query: String) = query.isBlank() ||
    (listOf(name, description, category) + tags).any { it.contains(query.trim(), ignoreCase = true) }

private fun Toolset.matches(query: String) = query.isBlank() ||
    (listOf(name, label, description) + tools).any { it.contains(query.trim(), ignoreCase = true) }

/**
 * The active profile's skills and toolsets, as the iOS app's Capabilities pages show them. A switch
 * shows its new state at once; saves go one at a time, in the order flipped, and a failed one puts
 * the switch back unless a newer flip of it is waiting.
 */
@HiltViewModel
internal class CapabilitiesViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val capabilities: CapabilitiesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CapabilitiesUiState())
    val state: StateFlow<CapabilitiesUiState> = _state.asStateFlow()

    private val writes = Mutex()

    /** Flips waiting or in flight, per switch, so only the newest one's failure reverts it. */
    private val pending = mutableMapOf<String, Int>()

    fun refresh() {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            _state.update { it.copy(profile = profile, refreshing = !it.loading) }
            coroutineScope {
                val skills = async { capabilities.skills(id, profile) }
                val toolsets = async { capabilities.toolsets(id, profile) }
                val catalog = async { capabilities.catalog(id, profile) }
                val usage = async { capabilities.toolUsage(id, profile) }
                val readSkills = skills.await()
                val readToolsets = toolsets.await()
                _state.update { state ->
                    state.copy(
                        loading = false,
                        refreshing = false,
                        failure = (readSkills as? NetResult.Failure)?.failure
                            ?: (readToolsets as? NetResult.Failure)?.failure,
                        skills = (readSkills as? NetResult.Success)?.value ?: state.skills,
                        toolsets = (readToolsets as? NetResult.Success)?.value ?: state.toolsets,
                        // The catalog and use counts are extras: a failed read keeps what was shown.
                        catalog = (catalog.await() as? NetResult.Success)?.value ?: state.catalog,
                        usage = (usage.await() as? NetResult.Success)?.value ?: state.usage,
                    )
                }
            }
        }
    }

    fun show(tab: CapabilitiesTab) = _state.update { it.copy(tab = tab) }

    fun search(query: String) = _state.update { it.copy(query = query) }

    fun sortSkills(mostUsedFirst: Boolean) = _state.update { it.copy(skillsMostUsedFirst = mostUsedFirst) }

    fun sortTools(mostUsedFirst: Boolean) = _state.update { it.copy(toolsMostUsedFirst = mostUsedFirst) }

    fun setSkill(name: String, enabled: Boolean) {
        val before = _state.value.skills.firstOrNull { it.name == name }?.enabled ?: return
        if (before == enabled) return
        _state.update { state ->
            state.copy(
                skills = state.skills.map {
                    if (it.name ==
                        name
                    ) {
                        it.copy(enabled = enabled)
                    } else {
                        it
                    }
                },
            )
        }
        write("skill:$name") { id, profile ->
            capabilities.setSkill(id, profile, name, enabled).also { result ->
                if (result is NetResult.Failure) {
                    _state.update { state ->
                        state.copy(
                            skills = if (isNewest("skill:$name")) {
                                state.skills.map { if (it.name == name) it.copy(enabled = before) else it }
                            } else {
                                state.skills
                            },
                            toggleFailure = ToggleFailure(name, result.failure),
                        )
                    }
                }
            }
        }
    }

    /** Turns every shown skill on or off; with [unusedOnly], only those never used, off. */
    fun setAllSkills(enabled: Boolean, unusedOnly: Boolean = false) {
        _state.value.shownSkills.filter { !unusedOnly || it.usage == 0L }.forEach { setSkill(it.name, enabled) }
    }

    fun setToolset(name: String, enabled: Boolean) {
        val before = _state.value.toolsets.firstOrNull { it.name == name }?.enabled ?: return
        if (before == enabled) return
        _state.update { state ->
            state.copy(
                toolsets = state.toolsets.map {
                    if (it.name ==
                        name
                    ) {
                        it.copy(enabled = enabled)
                    } else {
                        it
                    }
                },
            )
        }
        write("toolset:$name") { id, profile ->
            capabilities.setToolset(id, profile, name, enabled).also { result ->
                if (result is NetResult.Failure) {
                    _state.update { state ->
                        state.copy(
                            toolsets = if (isNewest("toolset:$name")) {
                                state.toolsets.map { if (it.name == name) it.copy(enabled = before) else it }
                            } else {
                                state.toolsets
                            },
                            toggleFailure = ToggleFailure(
                                state.toolsets.firstOrNull { it.name == name }?.label ?: name,
                                result.failure,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun setAllToolsets(enabled: Boolean) = _state.value.shownToolsets.forEach { setToolset(it.name, enabled) }

    fun toggleFailureShown() = _state.update { it.copy(toggleFailure = null) }

    private fun write(key: String, save: suspend (DashboardId, String) -> NetResult<Unit>) {
        pending[key] = (pending[key] ?: 0) + 1
        // Launched in flip order and queued on a fair lock before anything suspends, so saves keep that order.
        viewModelScope.launch {
            writes.withLock {
                target()?.let { (id, profile) -> save(id, profile) }
                pending[key] = (pending[key] ?: 1) - 1
            }
        }
    }

    /** True when no later flip of [key] is waiting (this one has just finished). */
    private fun isNewest(key: String) = (pending[key] ?: 0) <= 1

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }
}
