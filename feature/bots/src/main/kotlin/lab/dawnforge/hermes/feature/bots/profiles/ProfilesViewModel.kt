package lab.dawnforge.hermes.feature.bots.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.data.bots.BotColor
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotWrite
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.profiles.ProfileGlows
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.bots.AvatarPng

/** One profile as the manager shows it. */
data class ProfileRow(
    val profile: Profile,
    val color: Int,
    val picture: ByteArray?,
    /** Its picture lives on Hermes (Bot Mode), so it can be set here. */
    val canSetPicture: Boolean,
) {
    val name: String get() = profile.name
    val hasPicture: Boolean get() = picture != null

    override fun equals(other: Any?): Boolean = other is ProfileRow && other.profile == profile &&
        other.color == color && other.picture.contentEquals(picture) && other.canSetPicture == canSetPicture

    override fun hashCode(): Int = profile.hashCode()
}

/** Why a picture change didn't fully work. */
enum class PictureProblem { NotSaved, LookNotSaved, NotConnected }

data class ProfilesUiState(
    val loading: Boolean = true,
    val rows: List<ProfileRow> = emptyList(),
    val active: String = Profile.DEFAULT,
    val reordering: Boolean = false,
    /** The profile whose picture is being saved. */
    val saving: String? = null,
    val problem: Pair<String, PictureProblem>? = null,
    val failure: ConnectionFailure? = null,
    /** This Hermes has no Bot Mode, so profile pictures can't be kept on it. */
    val picturesUnavailable: Boolean = false,
    val renaming: Boolean = false,
    /** Each profile's glow (picked, or from its photo), by name. */
    val glows: Map<String, Int> = emptyMap(),
    /** The glows picked on this phone; the rest are "Automatic". */
    val picks: Map<String, Int> = emptyMap(),
)

sealed interface ProfilesEvent {
    data object Close : ProfilesEvent
}

/**
 * The profile manager, as iOS's: each profile with its picture (kept on Hermes with
 * `profiles.set_asset`, and a look saying it is a photo), the order this phone lists them in, and
 * the name it shows for the default profile. Tapping a profile switches to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dashboards: DashboardRepository,
    private val profiles: ProfileRepository,
    private val bots: BotsRepository,
    private val glows: ProfileGlows,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfilesUiState())
    val state: StateFlow<ProfilesUiState> = _state.asStateFlow()

    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var dashboard: DashboardId? = null
    private var listed: List<Profile> = emptyList()

    init {
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull()
                .flatMapLatest { id ->
                    profiles.activeProfile(id).combine(profiles.appearance(id)) { active, _ -> id to active }
                }
                .collectLatest { (id, active) ->
                    dashboard = id
                    _state.update { it.copy(active = active) }
                    load(id)
                }
        }
        viewModelScope.launch { bots.avatars.collect { publish() } }
        viewModelScope.launch {
            dashboards.activeDashboardId.filterNotNull()
                .flatMapLatest { id -> glows.glows(id).combine(glows.picks(id)) { all, picked -> all to picked } }
                .collect { (all, picked) -> _state.update { it.copy(glows = all, picks = picked) } }
        }
    }

    private suspend fun load(id: DashboardId) {
        when (val result = profiles.profiles(id)) {
            is NetResult.Success -> listed = result.value
            is NetResult.Failure -> _state.update { it.copy(failure = result.failure) }
        }
        val roster = bots.refresh()
        if (roster is BotsState.Available) {
            roster.roster.bots.forEach { bots.loadAvatar(it, again = true) }
        }
        _state.update { it.copy(loading = false, picturesUnavailable = roster is BotsState.Unsupported) }
        publish()
    }

    private fun roster(): List<BotProfile> = (bots.state.value as? BotsState.Available)?.roster?.bots.orEmpty()

    private fun publish() {
        val bots = roster()
        val pictures = this.bots.avatars.value
        _state.update { state ->
            state.copy(
                rows = listed.map { profile ->
                    val bot = bots.firstOrNull { it.name == profile.name }
                    ProfileRow(
                        profile = profile,
                        color = bot?.let(BotColor::of) ?: BotColor.fallback(profile.name),
                        picture = pictures[profile.name],
                        canSetPicture = bot != null,
                    )
                },
            )
        }
    }

    fun select(name: String) {
        val id = dashboard ?: return
        if (_state.value.reordering) return
        viewModelScope.launch {
            profiles.setActiveProfile(id, name)
            _events.send(ProfilesEvent.Close)
        }
    }

    fun toggleReorder() = _state.update { it.copy(reordering = !it.reordering) }

    /** Moves [name] one place up ([by] -1) or down (+1); the order is kept on this phone. */
    fun move(name: String, by: Int) {
        val id = dashboard ?: return
        val names = listed.map(Profile::name).toMutableList()
        val from = names.indexOf(name)
        val to = from + by
        if (from < 0 || to !in names.indices) return
        names.add(to, names.removeAt(from))
        listed = names.mapNotNull { n -> listed.firstOrNull { it.name == n } }
        publish()
        viewModelScope.launch { profiles.setOrder(id, names) }
    }

    /** Picks [name]'s glow, or with null goes back to its photo's colour ("Automatic"). */
    fun pickGlow(name: String, rgb: Int?) {
        val id = dashboard ?: return
        viewModelScope.launch { glows.pick(id, name, rgb) }
    }

    fun startRename() = _state.update { it.copy(renaming = true) }

    fun cancelRename() = _state.update { it.copy(renaming = false) }

    /** The name this phone shows for the default profile; empty brings back "Hermes". */
    fun rename(name: String) {
        val id = dashboard ?: return
        _state.update { it.copy(renaming = false) }
        viewModelScope.launch { profiles.setDefaultName(id, name) }
    }

    /** A picture picked for [name]: kept on Hermes, then its look says it is a photo. */
    fun setPicture(name: String, uri: Uri) {
        val bot = roster().firstOrNull { it.name == name } ?: return notConnected(name)
        _state.update { it.copy(saving = name, problem = null) }
        viewModelScope.launch {
            val png = withContext(Dispatchers.IO) { runCatching { AvatarPng.from(context, uri) }.getOrNull() }
            val problem = when {
                png == null || bots.setAvatar(name, png) !is NetResult.Success -> PictureProblem.NotSaved
                bots.saveLook(bot, photoLook(photo = true)) != BotWrite.Saved -> PictureProblem.LookNotSaved
                else -> null
            }
            finish(name, problem)
        }
    }

    /** Removes [name]'s picture: the look goes back to a shape first, then the picture, as Desktop orders it. */
    fun removePicture(name: String) {
        val bot = roster().firstOrNull { it.name == name } ?: return notConnected(name)
        _state.update { it.copy(saving = name, problem = null) }
        viewModelScope.launch {
            val problem = when {
                bots.saveLook(bot, photoLook(photo = false)) != BotWrite.Saved -> PictureProblem.LookNotSaved
                bots.setAvatar(name, null) !is NetResult.Success -> PictureProblem.NotSaved
                else -> null
            }
            finish(name, problem)
        }
    }

    private fun photoLook(photo: Boolean) = buildMap {
        put("imageKind", JsonPrimitive(if (photo) "photo" else "shape"))
        if (photo) put("custom", JsonPrimitive(true))
    }

    private suspend fun finish(name: String, problem: PictureProblem?) {
        bots.refresh()
        roster().firstOrNull { it.name == name }?.let { bots.loadAvatar(it, again = true) }
        _state.update { it.copy(saving = null, problem = problem?.let { p -> name to p }) }
        publish()
    }

    private fun notConnected(name: String) = _state.update { it.copy(problem = name to PictureProblem.NotConnected) }

    /** The name the default profile shows now, for the rename field. */
    suspend fun defaultName(): String =
        dashboard?.let { dashboards.defaultProfileName(it).first() } ?: ProfileRepository.DEFAULT_NAME
}
