package lab.dawnforge.hermes.feature.bots

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotSlug
import lab.dawnforge.hermes.core.data.bots.BotSoul
import lab.dawnforge.hermes.core.data.bots.BotWrite
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.bots.navigation.BotEditorRoute

/** The picture being chosen: unchanged, a new one, or removed. */
sealed interface PictureChoice {
    data object Unchanged : PictureChoice

    class New(val png: ByteArray) : PictureChoice

    data object Removed : PictureChoice
}

/** Why the name can't be a profile id, if it can't. */
enum class NameProblem { Empty, Invalid, Taken }

data class BotEditorState(
    /** Null while making a new bot. */
    val bot: BotProfile? = null,
    val name: String = "",
    val description: String = "",
    val soul: String = "",
    /** A swatch, or null for the automatic colour. */
    val color: String? = null,
    val picture: PictureChoice = PictureChoice.Unchanged,
    /** Editing: its description and personality are being read. */
    val loading: Boolean = false,
    /** Editing: they couldn't be read, so the personality isn't written. */
    val detailsFailed: Boolean = false,
    val saving: Boolean = false,
    val failure: ConnectionFailure? = null,
    /** Saved, but with something that didn't stick. */
    val warning: BotWarning? = null,
    val takenNames: Set<String> = emptySet(),
    val loadedDescription: String = "",
    val loadedSoul: String = "",
) {
    val creating: Boolean get() = bot == null
    val slug: String get() = BotSlug.of(name)

    val nameProblem: NameProblem?
        get() = when {
            !creating -> null
            name.isBlank() -> NameProblem.Empty
            !BotSlug.isValid(slug) -> NameProblem.Invalid
            slug.lowercase() in takenNames -> NameProblem.Taken
            else -> null
        }

    val canSave: Boolean get() = !saving && !loading && nameProblem == null
}

/** What a save got done and what it didn't. */
enum class BotWarning { LookNotSaved, NameOrColorNotSaved, DetailsNotSaved, PictureNotRemoved }

/** Making a bot, or editing one: its name, colour, picture, description and personality. */
@HiltViewModel
class BotEditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val bots: BotsRepository,
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val route = savedState.toRoute<BotEditorRoute>()
    private val _state = MutableStateFlow(BotEditorState(loading = route.name != null))
    val state: StateFlow<BotEditorState> = _state.asStateFlow()
    val avatars: StateFlow<Map<String, ByteArray>> = bots.avatars

    init {
        viewModelScope.launch {
            val roster = (bots.state.value as? BotsState.Available)?.roster
            val bot = route.name?.let { name -> roster?.find(name) }
            if (bot != null) {
                _state.update { it.copy(bot = bot, name = bot.title.orEmpty(), color = bot.color) }
                when (val details = bots.describe(bot.name)) {
                    is NetResult.Success -> _state.update {
                        it.copy(
                            loading = false,
                            description = details.value.description,
                            soul = details.value.soul,
                            loadedDescription = details.value.description,
                            loadedSoul = details.value.soul,
                        )
                    }

                    is NetResult.Failure -> _state.update { it.copy(loading = false, detailsFailed = true) }
                }
            } else {
                _state.update { it.copy(loading = false) }
                // A new bot's id can't be any bot's or profile's.
                val dashboard = dashboards.activeDashboardId.first()
                val known = roster?.bots.orEmpty().map { it.name.lowercase() } +
                    (
                        dashboard?.let {
                            (profiles.profiles(it) as? NetResult.Success)?.value?.map { profile ->
                                profile.name.lowercase()
                            }
                        }.orEmpty()
                        )
                _state.update { it.copy(takenNames = known.toSet()) }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    fun setSoul(value: String) = _state.update { it.copy(soul = value) }

    fun setColor(value: String?) = _state.update { it.copy(color = value) }

    fun removePicture() = _state.update {
        it.copy(
            picture = if (it.picture is PictureChoice.New ||
                it.bot?.hasAvatar != true
            ) {
                PictureChoice.Unchanged
            } else {
                PictureChoice.Removed
            },
        )
    }

    fun keepPicture() = _state.update { it.copy(picture = PictureChoice.Unchanged) }

    /** A picture picked from the phone: cropped square and scaled to 256 px, as iOS sends it. */
    fun pickPicture(uri: Uri) {
        viewModelScope.launch {
            val png = withContext(Dispatchers.IO) { runCatching { AvatarPng.from(context, uri) }.getOrNull() }
            if (png != null) _state.update { it.copy(picture = PictureChoice.New(png)) }
        }
    }

    fun save(onDone: (created: String?) -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, failure = null, warning = null) }
        viewModelScope.launch {
            val bot = current.bot
            if (bot == null) create(current, onDone) else update(bot, current, onDone)
        }
    }

    private suspend fun create(current: BotEditorState, onDone: (String?) -> Unit) {
        val roster = (bots.state.value as? BotsState.Available)?.roster
        val slug = current.slug
        val title = BotSlug.title(current.name)
        val soul = BotSoul.compose(
            name = slug,
            title = title,
            description = current.description,
            customSoul = current.soul,
            protocolInjected = roster?.protocolInjected == true,
            roster = roster?.bots.orEmpty(),
        )
        when (val created = bots.create(slug, current.description.trim(), soul)) {
            is NetResult.Failure -> {
                _state.update { it.copy(saving = false, failure = created.failure) }
                return
            }

            is NetResult.Success -> Unit
        }
        val picture = current.picture as? PictureChoice.New
        val pictureSaved = picture == null || bots.setAvatar(slug, picture.png) is NetResult.Success
        val look = buildMap<String, JsonElement?> {
            put("created", JsonPrimitive(System.currentTimeMillis()))
            if (title.isNotEmpty()) put("title", JsonPrimitive(title))
            current.color?.let { put("color", JsonPrimitive(it)) }
            if (current.color != null || picture != null) put("custom", JsonPrimitive(true))
            if (picture != null && pictureSaved) put("imageKind", JsonPrimitive("photo"))
        }
        val fresh = BotProfile(
            slug, null, null, "", false, false, false, null, null, null, null, emptyList(), false,
            JsonObject(emptyMap()), null,
        )
        val lookSaved = (bots.configure(fresh, look) as? NetResult.Success)?.value?.get("ui_meta") == BotWrite.Saved
        bots.refresh()
        _state.update {
            it.copy(
                saving = false,
                warning = if (pictureSaved &&
                    lookSaved
                ) {
                    null
                } else {
                    BotWarning.LookNotSaved
                },
            )
        }
        onDone(slug)
    }

    private suspend fun update(bot: BotProfile, current: BotEditorState, onDone: (String?) -> Unit) {
        val picture = current.picture
        val uploaded = picture is PictureChoice.New && bots.setAvatar(bot.name, picture.png) is NetResult.Success
        val title = current.name.trim()
        val patch = buildMap<String, JsonElement?> {
            put("title", title.takeIf(String::isNotEmpty)?.let(::JsonPrimitive))
            put("color", current.color?.let(::JsonPrimitive))
            if (current.color != null || uploaded) put("custom", JsonPrimitive(true))
            when {
                uploaded -> put("imageKind", JsonPrimitive("photo"))
                picture == PictureChoice.Removed -> put("imageKind", JsonPrimitive("shape"))
            }
        }
        val description = current.description.takeIf { !current.detailsFailed && it != current.loadedDescription }
        val soul = current.soul.takeIf { !current.detailsFailed && it != current.loadedSoul }
        val first = bots.configure(bot, patch, description, soul)
        var look = (first as? NetResult.Success)?.value?.get("ui_meta")
        val details = (first as? NetResult.Success)?.value?.filterKeys { it != "ui_meta" }?.values
        if (look == BotWrite.Conflict) look = bots.saveLook(bot, patch)
        // A removed picture goes once the look says "shape", as Desktop orders it.
        val removed =
            picture != PictureChoice.Removed ||
                (look == BotWrite.Saved && bots.setAvatar(bot.name, null) is NetResult.Success)
        bots.refresh()
        val warning = when {
            first is NetResult.Failure -> null
            look != BotWrite.Saved -> BotWarning.NameOrColorNotSaved
            details.orEmpty().any { it == BotWrite.Failed } -> BotWarning.DetailsNotSaved
            !removed -> BotWarning.PictureNotRemoved
            else -> null
        }
        _state.update {
            it.copy(saving = false, failure = (first as? NetResult.Failure)?.failure, warning = warning)
        }
        if (first is NetResult.Success && warning == null) onDone(null)
    }
}
