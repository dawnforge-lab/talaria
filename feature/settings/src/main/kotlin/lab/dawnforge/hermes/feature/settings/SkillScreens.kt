package lab.dawnforge.hermes.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
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
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.GatewayStatusRepository
import lab.dawnforge.hermes.core.data.settings.Skill
import lab.dawnforge.hermes.core.data.settings.prettyCategory
import lab.dawnforge.hermes.core.data.settings.splitSkillFile
import lab.dawnforge.hermes.core.data.settings.usageLabel
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.ui.failureText
import lab.dawnforge.hermes.feature.settings.navigation.CatalogSkillRoute
import lab.dawnforge.hermes.feature.settings.navigation.SkillRoute

internal data class SkillUiState(
    val name: String,
    val skill: Skill? = null,
    val file: String? = null,
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val toggleFailure: ConnectionFailure? = null,
)

/** One installed skill: what it says about itself, its instructions, and its switch. */
@HiltViewModel
internal class SkillViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val capabilities: CapabilitiesRepository,
) : ViewModel() {
    private val name = savedStateHandle.toRoute<SkillRoute>().name
    private val _state = MutableStateFlow(SkillUiState(name))
    val state: StateFlow<SkillUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            coroutineScope {
                val skills = async { capabilities.skills(id, profile) }
                val file = capabilities.skillFile(id, profile, name)
                val skill = (skills.await() as? NetResult.Success)?.value?.firstOrNull { it.name == name }
                _state.update {
                    it.copy(
                        loading = false,
                        skill = skill,
                        file = (file as? NetResult.Success)?.value,
                        failure = (file as? NetResult.Failure)?.failure,
                    )
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val skill = _state.value.skill ?: return
        _state.update { it.copy(skill = skill.copy(enabled = enabled), toggleFailure = null) }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val result = capabilities.setSkill(id, profile, name, enabled)
            if (result is NetResult.Failure) _state.update { it.copy(skill = skill, toggleFailure = result.failure) }
        }
    }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillScreen(viewModel: SkillViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(state.name, onBack) {
        val skill = state.skill
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            else -> {
                val (fields, body) = splitSkillFile(state.file.orEmpty())
                val description = skill?.description?.takeIf(String::isNotBlank) ?: fields["description"]
                Header(
                    description = description,
                    tags = listOfNotNull(
                        skill?.category?.let(::prettyCategory),
                        skill?.usage?.takeIf { it > 0 }?.let(::usageLabel),
                    ),
                )
                if (skill != null) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.feature_settings_turned_on)) },
                        supportingContent = {
                            state.toggleFailure?.let {
                                Text(
                                    stringResource(R.string.feature_settings_skill_failed, state.name, failureText(it)),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } ?: Text(stringResource(R.string.feature_settings_new_sessions))
                        },
                        trailingContent = { Switch(checked = skill.enabled, onCheckedChange = viewModel::setEnabled) },
                    )
                }
                state.failure?.let {
                    Banner(stringResource(R.string.feature_settings_skill_read_failed, failureText(it)))
                }
                SkillFile(fields - "name" - "description", body)
            }
        }
    }
}

internal data class CatalogSkillUiState(
    val name: String,
    val file: String? = null,
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    val installing: Boolean = false,
    val installed: Boolean = false,
    /** Why the install failed: Hermes' own words, or a failure to reach it. */
    val installFailure: String? = null,
    val installUnreachable: ConnectionFailure? = null,
)

/** A skill in Hermes' official catalog, read before installing it. */
@HiltViewModel
internal class CatalogSkillViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val capabilities: CapabilitiesRepository,
    private val gateway: GatewayStatusRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<CatalogSkillRoute>()
    private val _state = MutableStateFlow(CatalogSkillUiState(route.name))
    val state: StateFlow<CatalogSkillUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            val file = capabilities.catalogFile(id, profile, route.identifier)
            _state.update {
                it.copy(
                    loading = false,
                    file = (file as? NetResult.Success)?.value,
                    failure = (file as? NetResult.Failure)?.failure,
                )
            }
        }
    }

    /** Hermes scans and installs it in the background; the phone follows that action until it ends. */
    fun install() {
        if (_state.value.installing) return
        _state.update { it.copy(installing = true, installFailure = null, installUnreachable = null) }
        viewModelScope.launch {
            val (id, profile) = target() ?: return@launch
            when (val started = capabilities.installSkill(id, profile, route.identifier)) {
                is NetResult.Failure -> _state.update {
                    it.copy(installing = false, installUnreachable = started.failure)
                }

                is NetResult.Success -> follow(id, started.value)
            }
        }
    }

    private suspend fun follow(id: DashboardId, action: String) {
        var misses = 0
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < INSTALL_LIMIT_MS) {
            delay(POLL_MS)
            when (val read = gateway.action(id, action)) {
                is NetResult.Failure -> if (++misses >= MISSES) break

                is NetResult.Success -> {
                    misses = 0
                    val status = read.value ?: break
                    if (status.running) continue
                    _state.update {
                        if (status.succeeded) {
                            it.copy(installing = false, installed = true)
                        } else {
                            it.copy(installing = false, installFailure = status.reason ?: "")
                        }
                    }
                    return
                }
            }
        }
        _state.update { it.copy(installing = false, installFailure = "") }
    }

    private suspend fun target(): Pair<DashboardId, String>? {
        val id = dashboards.activeDashboardId.first() ?: return null
        return id to dashboards.activeProfile(id).first()
    }

    private companion object {
        const val POLL_MS = 1_200L
        const val MISSES = 4
        const val INSTALL_LIMIT_MS = 900_000L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogSkillScreen(viewModel: CatalogSkillViewModel, category: String, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DetailScaffold(state.name, onBack) {
        val (fields, body) = splitSkillFile(state.file.orEmpty())
        Header(
            description = fields["description"],
            tags = listOf(prettyCategory(category), stringResource(R.string.feature_settings_skill_official)),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (state.installed) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.feature_settings_installed))
            } else {
                Button(onClick = viewModel::install, enabled = !state.installing && !state.loading) {
                    val label = if (state.installing) {
                        R.string.feature_settings_installing
                    } else {
                        R.string.feature_settings_install
                    }
                    Text(stringResource(label))
                }
                if (state.installing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        val failure = state.installUnreachable?.let { failureText(it) }
            ?: state.installFailure?.ifEmpty { stringResource(R.string.feature_settings_install_unfinished) }
        failure?.let { Banner(stringResource(R.string.feature_settings_install_failed, it)) }
        Text(
            stringResource(R.string.feature_settings_skills_available_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.failure != null -> Banner(
                stringResource(R.string.feature_settings_skill_read_failed, failureText(state.failure!!)),
            )

            else -> SkillFile(fields - "name" - "description", body)
        }
    }
}

/** A detail page: a back arrow and a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.feature_settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            content()
        }
    }
}

@Composable
internal fun Header(description: String?, tags: List<String>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tags.isNotEmpty()) {
            Text(
                tags.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            description ?: stringResource(R.string.feature_settings_no_description),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SkillFile(fields: Map<String, String>, body: String) {
    if (fields.isNotEmpty()) {
        CardHeader(stringResource(R.string.feature_settings_skill_details))
        fields.forEach { (key, value) ->
            ListItem(headlineContent = { Text(value) }, overlineContent = { Text(key) })
        }
    }
    if (body.isNotBlank()) {
        CardHeader(stringResource(R.string.feature_settings_skill_instructions))
        SelectionContainer {
            Text(
                body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
