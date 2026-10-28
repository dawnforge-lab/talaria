package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.ConfigSchema
import lab.dawnforge.hermes.core.data.settings.ConfigValue
import lab.dawnforge.hermes.core.data.settings.FallbackModel
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository
import lab.dawnforge.hermes.core.data.settings.ModelCatalog
import lab.dawnforge.hermes.core.data.settings.ModelDefaults
import lab.dawnforge.hermes.core.data.settings.ModelSet
import lab.dawnforge.hermes.core.data.settings.ProviderOptions
import lab.dawnforge.hermes.core.data.settings.parseLines
import lab.dawnforge.hermes.core.data.settings.parseNumber
import lab.dawnforge.hermes.core.data.settings.toJson
import lab.dawnforge.hermes.core.data.settings.valueAt
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.settings.navigation.HermesPageRoute

/** How a save went, shown under what was saved. */
internal sealed interface SaveState {
    data object Saving : SaveState

    data object Saved : SaveState

    data class Failed(val failure: ConnectionFailure) : SaveState

    /** What was typed isn't a number. */
    data object NotANumber : SaveState
}

/** The models a delegate agent uses; each blank follows the chat that starts it. */
internal data class DelegateModel(val provider: String = "", val model: String = "", val reasoning: String = "")

internal data class HermesPageUiState(
    val page: HermesPage,
    val profile: String? = null,
    val loading: Boolean = true,
    /** Why the page couldn't be read. */
    val failure: ConnectionFailure? = null,
    val cards: List<Card> = emptyList(),
    val values: Map<String, ConfigValue> = emptyMap(),
    /** The choices of each choice field, current value not necessarily among them. */
    val choices: Map<String, List<String>> = emptyMap(),
    val saves: Map<String, SaveState> = emptyMap(),
    /** The Model and Memory pages' providers and models. */
    val catalog: ModelCatalog? = null,
    val catalogFailure: ConnectionFailure? = null,
    /** The default model as picked on the page, and as saved. */
    val defaults: ModelDefaults? = null,
    val savedDefaults: ModelDefaults? = null,
    val defaultsSave: SaveState? = null,
    /** Hermes' question before an expensive default model. */
    val expensive: String? = null,
    val fallbacks: List<FallbackModel> = emptyList(),
    val savedFallbacks: List<FallbackModel> = emptyList(),
    val fallbacksSave: SaveState? = null,
    val delegate: DelegateModel = DelegateModel(),
    val savedDelegate: DelegateModel = DelegateModel(),
    val delegateSave: SaveState? = null,
    /** Whether new delegate agents are paused; null when the live connection can't say. */
    val delegationPaused: Boolean? = null,
    val pauseSave: SaveState? = null,
) {
    val defaultsChanged: Boolean get() = defaults != null && defaults != savedDefaults
    val fallbacksChanged: Boolean get() = fallbacks != savedFallbacks
    val delegateChanged: Boolean get() = delegate != savedDelegate
    val busy: Boolean get() = saves.values.any { it == SaveState.Saving }
}

/**
 * One Hermes settings page for the active profile. Each change is saved on its own, as soon as
 * it's made (a switch or a choice) or confirmed (typed text), and only that key is sent.
 */
@HiltViewModel
internal class HermesPageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val settings: HermesSettingsRepository,
) : ViewModel() {
    private val page = HermesPage.valueOf(savedStateHandle.toRoute<HermesPageRoute>().page)
    private val _state = MutableStateFlow(HermesPageUiState(page))
    val state: StateFlow<HermesPageUiState> = _state.asStateFlow()

    private var target: Pair<DashboardId, String>? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = dashboards.activeProfile(id).first()
            target = id to profile
            _state.update { it.copy(profile = profile, loading = it.values.isEmpty(), failure = null) }
            coroutineScope {
                val schema = async { (settings.schema(id, profile) as? NetResult.Success)?.value ?: ConfigSchema() }
                val options = async {
                    if (page ==
                        HermesPage.Memory
                    ) {
                        (settings.providerOptions(id, profile) as? NetResult.Success)?.value
                    } else {
                        null
                    }
                }
                val paused = async {
                    if (page == HermesPage.Memory) (settings.delegationPaused() as? NetResult.Success)?.value else null
                }
                when (val config = settings.config(id, profile)) {
                    is NetResult.Failure -> _state.update { it.copy(loading = false, failure = config.failure) }

                    is NetResult.Success -> {
                        show(config.value, schema.await(), options.await())
                        if (page == HermesPage.Model || page == HermesPage.Memory) loadModels(id, profile, config.value)
                    }
                }
                _state.update { it.copy(delegationPaused = paused.await()) }
            }
        }
    }

    private fun show(config: JsonObject, schema: ConfigSchema, options: ProviderOptions?) {
        val cards = page.cards.shown(schema)
        val fields = cards.flatMap(Card::fields)
        val delegate = DelegateModel(
            provider = (config.valueAt("delegation.provider") as? ConfigValue.Text)?.text.orEmpty(),
            model = (config.valueAt("delegation.model") as? ConfigValue.Text)?.text.orEmpty(),
            reasoning = (config.valueAt("delegation.reasoning_effort") as? ConfigValue.Text)?.text.orEmpty(),
        )
        val fallbacks = FallbackModel.list(config)
        _state.update {
            it.copy(
                loading = false,
                failure = null,
                cards = cards,
                values = fields.mapNotNull { field ->
                    config.valueAt(field.key)?.let { value -> field.key to value }
                }.toMap(),
                choices = fields.filter { field ->
                    field.control is Control.Choice || field.control == Control.TimeZone
                }
                    .associate { field -> field.key to choices(field, config, schema, options) },
                fallbacks = if (it.fallbacksChanged) it.fallbacks else fallbacks,
                savedFallbacks = fallbacks,
                delegate = if (it.delegateChanged) it.delegate else delegate,
                savedDelegate = delegate,
            )
        }
    }

    private fun choices(
        field: Field,
        config: JsonObject,
        schema: ConfigSchema,
        options: ProviderOptions?,
    ): List<String> = when (field.key) {
        "display.personality" -> (PERSONALITIES + personalities(config)).distinct()

        "timezone" -> listOf("") + schema.options("timezone").ifEmpty { ZoneId.getAvailableZoneIds().sorted() }

        HermesSettingsRepository.MEMORY_PROVIDER -> listOf("") + options?.memory.orEmpty()

        HermesSettingsRepository.CONTEXT_ENGINE -> (
            listOf(
                "default",
                "custom",
            ) + options?.contextEngines.orEmpty()
            ).distinct()

        else -> (field.control as Control.Choice).values.ifEmpty { schema.options(field.key) }
    }

    private fun personalities(config: JsonObject): List<String> =
        ((config["agent"] as? JsonObject)?.get("personalities") as? JsonObject)?.keys.orEmpty().sorted()

    private suspend fun loadModels(id: DashboardId, profile: String, config: JsonObject) {
        when (val result = settings.models(id, profile, config)) {
            is NetResult.Failure -> _state.update { it.copy(catalogFailure = result.failure) }

            is NetResult.Success -> {
                val catalog = result.value
                val saved = catalog.defaults.let { d ->
                    d.copy(provider = catalog.providerFor(d.provider, d.model)?.slug ?: d.provider)
                }
                _state.update {
                    it.copy(
                        catalog = catalog,
                        catalogFailure = null,
                        defaults = if (it.defaultsChanged) it.defaults else saved,
                        savedDefaults = saved,
                    )
                }
            }
        }
    }

    // ---- Fields ----------------------------------------------------------------------------------

    /** Saves [value] for [field], showing it at once and putting the old value back if it fails. */
    fun set(field: Field, value: ConfigValue) {
        val (id, profile) = target ?: return
        val before = _state.value.values[field.key]
        if (before == value || _state.value.saves[field.key] == SaveState.Saving) return
        _state.update {
            it.copy(
                values = it.values + (field.key to value),
                saves =
                    it.saves + (field.key to SaveState.Saving),
            )
        }
        viewModelScope.launch {
            when (val result = settings.save(id, profile, field.key, value.toJson())) {
                is NetResult.Success -> flashSaved(field.key)

                is NetResult.Failure -> _state.update {
                    it.copy(
                        values = if (before != null) it.values + (field.key to before) else it.values - field.key,
                        saves = it.saves + (field.key to SaveState.Failed(result.failure)),
                    )
                }
            }
        }
    }

    /** Saves what was typed for a text, number or list field. */
    fun setText(field: Field, text: String) {
        val value = when (field.control) {
            is Control.Number -> parseNumber(text)?.let(ConfigValue::Number) ?: run {
                _state.update { it.copy(saves = it.saves + (field.key to SaveState.NotANumber)) }
                return
            }

            Control.Lines -> ConfigValue.Items(parseLines(text))

            else -> ConfigValue.Text(text.trim())
        }
        set(field, value)
    }

    private fun flashSaved(key: String) {
        _state.update { it.copy(saves = it.saves + (key to SaveState.Saved)) }
        viewModelScope.launch {
            delay(SAVED_SHOWN_MS)
            _state.update { if (it.saves[key] == SaveState.Saved) it.copy(saves = it.saves - key) else it }
        }
    }

    // ---- Default model ---------------------------------------------------------------------------

    fun pickDefault(provider: String? = null, model: String? = null, reasoning: String? = null) {
        _state.update { state ->
            val current = state.defaults ?: return@update state
            var next = current.copy(reasoning = reasoning ?: current.reasoning)
            if (provider != null && provider != current.provider) {
                // Keep the model if the new provider lists it too.
                val models = state.catalog?.choices?.providers?.firstOrNull { it.slug == provider }?.models.orEmpty()
                next =
                    next.copy(
                        provider = provider,
                        model =
                            models.firstOrNull { it.id == current.model }?.id ?: models.firstOrNull()?.id.orEmpty(),
                    )
            }
            if (model != null) next = next.copy(model = model)
            state.copy(defaults = next, defaultsSave = null)
        }
    }

    fun saveDefaults(confirmExpensive: Boolean = false) {
        val (id, profile) = target ?: return
        val state = _state.value
        val picked = state.defaults ?: return
        val saved = state.savedDefaults
        if (picked.provider.isBlank() || picked.model.isBlank()) return
        _state.update { it.copy(defaultsSave = SaveState.Saving, expensive = null) }
        viewModelScope.launch {
            if (saved == null || picked.provider != saved.provider || picked.model != saved.model) {
                when (
                    val set = settings.setDefaultModel(
                        id,
                        profile,
                        picked.provider,
                        picked.model,
                        confirmExpensive,
                    )
                ) {
                    is NetResult.Failure -> {
                        _state.update { it.copy(defaultsSave = SaveState.Failed(set.failure)) }
                        return@launch
                    }

                    is NetResult.Success -> if (set.value is ModelSet.Confirm) {
                        _state.update {
                            it.copy(defaultsSave = null, expensive = (set.value as ModelSet.Confirm).message)
                        }
                        return@launch
                    }
                }
            }
            if (saved == null || picked.reasoning != saved.reasoning) {
                val result = settings.save(id, profile, "agent.reasoning_effort", JsonPrimitive(picked.reasoning))
                if (result is NetResult.Failure) {
                    // The model may have been set: what's saved now is the new model, the old reasoning.
                    _state.update {
                        it.copy(
                            savedDefaults = picked.copy(reasoning = saved?.reasoning.orEmpty()),
                            defaultsSave = SaveState.Failed(result.failure),
                        )
                    }
                    return@launch
                }
            }
            _state.update { it.copy(savedDefaults = picked, defaultsSave = SaveState.Saved) }
        }
    }

    fun dismissExpensive() = _state.update { it.copy(expensive = null) }

    // ---- Fallbacks -------------------------------------------------------------------------------

    fun addFallback() = _state.update { state ->
        val provider = state.catalog?.choices?.providers?.let { rows ->
            rows.firstOrNull { !it.current }
                ?: rows.firstOrNull()
        }
        state.copy(
            fallbacks =
                state.fallbacks + FallbackModel(
                    provider?.slug.orEmpty(),
                    provider?.models?.firstOrNull()?.id.orEmpty(),
                ),
            fallbacksSave = null,
        )
    }

    fun setFallback(index: Int, provider: String? = null, model: String? = null) = _state.update { state ->
        val list = state.fallbacks.toMutableList()
        val entry = list.getOrNull(index) ?: return@update state
        list[index] = when {
            // Another provider's settings (its address, key) don't carry over.
            provider != null && provider != entry.provider -> FallbackModel(
                provider,
                state.catalog?.choices?.providers?.firstOrNull {
                    it.slug == provider
                }?.models?.firstOrNull()?.id.orEmpty(),
            )

            model != null -> entry.copy(model = model)

            else -> entry
        }
        state.copy(fallbacks = list, fallbacksSave = null)
    }

    fun moveFallback(index: Int, by: Int) = _state.update { state ->
        val to = index + by
        if (index !in state.fallbacks.indices || to !in state.fallbacks.indices) return@update state
        val list = state.fallbacks.toMutableList()
        list.add(to, list.removeAt(index))
        state.copy(fallbacks = list, fallbacksSave = null)
    }

    fun removeFallback(index: Int) = _state.update { state ->
        state.copy(fallbacks = state.fallbacks.filterIndexed { i, _ -> i != index }, fallbacksSave = null)
    }

    /** Replaces the whole list: Hermes replaces a list when it merges a save. */
    fun saveFallbacks() {
        val (id, profile) = target ?: return
        val list = _state.value.fallbacks
        if (list.any { !it.complete }) return
        _state.update { it.copy(fallbacksSave = SaveState.Saving) }
        viewModelScope.launch {
            when (
                val result = settings.save(
                    id,
                    profile,
                    "fallback_providers",
                    JsonArray(list.map(FallbackModel::toJson)),
                )
            ) {
                is NetResult.Success -> _state.update {
                    it.copy(savedFallbacks = list, fallbacksSave = SaveState.Saved)
                }

                is NetResult.Failure -> _state.update { it.copy(fallbacksSave = SaveState.Failed(result.failure)) }
            }
        }
    }

    // ---- Delegation ------------------------------------------------------------------------------

    fun pickDelegate(provider: String? = null, model: String? = null, reasoning: String? = null) =
        _state.update { state ->
            val current = state.delegate
            val next = when {
                // A model belongs to its provider: following the chat's provider follows its model too.
                provider != null && provider != current.provider -> current.copy(provider = provider, model = "")

                else -> current.copy(model = model ?: current.model, reasoning = reasoning ?: current.reasoning)
            }
            state.copy(delegate = next, delegateSave = null)
        }

    fun saveDelegate() {
        val (id, profile) = target ?: return
        val picked = _state.value.delegate
        _state.update { it.copy(delegateSave = SaveState.Saving) }
        viewModelScope.launch {
            val values = mapOf(
                "delegation.provider" to JsonPrimitive(picked.provider),
                "delegation.model" to JsonPrimitive(picked.model),
                "delegation.reasoning_effort" to JsonPrimitive(picked.reasoning),
            )
            when (val result = settings.saveAll(id, profile, values)) {
                is NetResult.Success -> _state.update {
                    it.copy(savedDelegate = picked, delegateSave = SaveState.Saved)
                }

                is NetResult.Failure -> _state.update { it.copy(delegateSave = SaveState.Failed(result.failure)) }
            }
        }
    }

    fun pauseDelegation(paused: Boolean) {
        if (_state.value.pauseSave == SaveState.Saving) return
        _state.update { it.copy(pauseSave = SaveState.Saving) }
        viewModelScope.launch {
            when (val result = settings.pauseDelegation(paused)) {
                is NetResult.Success -> _state.update { it.copy(delegationPaused = result.value, pauseSave = null) }
                is NetResult.Failure -> _state.update { it.copy(pauseSave = SaveState.Failed(result.failure)) }
            }
        }
    }

    private companion object {
        const val SAVED_SHOWN_MS = 2_000L
    }
}
