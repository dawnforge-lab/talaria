package lab.dawnforge.hermes.core.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.HermesChangesMode
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** A setting value Hermes reads and asks for: on or off, or one of a setting's choices. */
sealed interface SettingValue {
    data class Flag(val on: Boolean) : SettingValue

    data class Choice(val value: String) : SettingValue

    fun json(): JsonPrimitive = when (this) {
        is Flag -> JsonPrimitive(on)
        is Choice -> JsonPrimitive(value)
    }

    companion object {
        fun of(element: JsonElement?): SettingValue? {
            val primitive = element as? JsonPrimitive ?: return null
            primitive.booleanOrNull?.takeIf { !primitive.isString }?.let { return Flag(it) }
            return primitive.contentOrNull?.takeIf { primitive.isString }?.let(::Choice)
        }
    }
}

/**
 * A setting the phone shares with Hermes: what it is in plain words, its value, and how much it
 * matters. A small one may apply with an Undo; any other asks the user every time.
 */
class SharedSetting(
    val key: String,
    val title: String,
    val section: String,
    val description: String,
    val small: Boolean,
    /** Null: on or off. Otherwise the values it takes, with their titles. */
    val choices: List<Pair<String, String>>?,
    val read: suspend () -> SettingValue,
    val write: suspend (SettingValue) -> Unit,
) {
    fun accepts(value: SettingValue): Boolean = when (value) {
        is SettingValue.Flag -> choices == null
        is SettingValue.Choice -> choices?.any { it.first == value.value } == true
    }

    /** The value as the user reads it. */
    fun describe(value: SettingValue): String = when (value) {
        is SettingValue.Flag -> if (value.on) "On" else "Off"
        is SettingValue.Choice -> choices?.firstOrNull { it.first == value.value }?.second ?: value.value
    }
}

/** A change Hermes asked for. */
data class SettingRequest(
    val id: String,
    val key: String,
    val value: SettingValue,
    /** The value Hermes saw when it asked. */
    val from: SettingValue?,
    val reason: String,
)

/** A change waiting for the user's Apply. */
data class ReviewItem(val request: SettingRequest, val title: String, val oldValue: String, val newValue: String)

/** Changes just applied without asking, kept for their Undo. */
data class AppliedNotice(val changes: List<Applied>) {
    data class Applied(val request: SettingRequest, val previous: SettingValue, val title: String)
}

/**
 * Hermes changes the phone's settings, as on iOS (`PUT /talaria-settings`, plugin 0.19+): the
 * phone shares the settings it lets Hermes see and the user's mode; Hermes asks for changes; the
 * phone checks each against its own list again, applies a small one with an Undo when the user
 * allows that, asks the user for the rest, and reports each outcome on its next sync. Anything it
 * doesn't share is out of Hermes' reach. Outcomes are kept on disk until the host hears them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesChanges(
    private val dashboards: DashboardRepository,
    private val plugin: PushPluginRepository,
    private val app: AppSettings,
    private val voice: VoiceSettings,
    private val device: AppDevice,
    foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Place(val id: DashboardId, val profile: String)

    private val _review = MutableStateFlow<List<ReviewItem>>(emptyList())

    /** Changes waiting for the user's Apply. */
    val review: StateFlow<List<ReviewItem>> = _review.asStateFlow()
    private val _notice = MutableStateFlow<AppliedNotice?>(null)

    /** The changes just made without asking, with their Undo. */
    val notice: StateFlow<AppliedNotice?> = _notice.asStateFlow()

    private val rounds = Channel<Unit>(Channel.CONFLATED)
    private val _reviewing = MutableStateFlow(false)

    /** Whether the review sheet is open. */
    val reviewing: StateFlow<Boolean> = _reviewing.asStateFlow()

    fun showReview(open: Boolean) {
        _reviewing.value = open
    }

    @Volatile
    private var active: Place? = null
    private val unavailableUntil = HashMap<Place, Long>()

    init {
        scope.launch {
            dashboards.activeDashboardId
                .flatMapLatest { id ->
                    id?.let { dashboards.activeProfile(it).map { profile -> Place(it, profile) } } ?: flowOf(null)
                }
                .distinctUntilChanged()
                .collect { place ->
                    active = place
                    _review.value = emptyList()
                    _notice.value = null
                    if (place != null) sync()
                }
        }
        scope.launch { foreground.collect { if (it) onForeground() } }
        scope.launch { for (round in rounds) syncRounds() }
    }

    fun sync() {
        rounds.trySend(Unit)
    }

    private fun onForeground() {
        synchronized(unavailableUntil) { unavailableUntil.clear() }
        sync()
    }

    /** The settings shared with Hermes for [id]'s [profile]: the voice conversation's, as iOS shares. */
    private fun catalog(id: DashboardId, profile: String): List<SharedSetting> = listOf(
        SharedSetting(
            key = "voice.continuous_conversation",
            title = "Continuous Conversation",
            section = "Conversation",
            description = "After Hermes answers in a voice conversation, the app starts listening again on its own. " +
                "Off: the user taps Listen to speak again.",
            small = false,
            choices = null,
            read = { SettingValue.Flag(voice.profile(id, profile).first().continuousConversation) },
            write = { value ->
                (value as? SettingValue.Flag)?.let { flag ->
                    voice.updateProfile(id, profile) { it.copy(continuousConversation = flag.on) }
                }
            },
        ),
        SharedSetting(
            key = "voice.keep_listening_when_locked",
            title = "Keep listening when locked",
            section = "Conversation",
            description = "The microphone stays on during a voice conversation while the phone is locked.",
            small = false,
            choices = null,
            read = { SettingValue.Flag(voice.profile(id, profile).first().keepListeningWhenLocked) },
            write = { value ->
                (value as? SettingValue.Flag)?.let { flag ->
                    voice.updateProfile(id, profile) { it.copy(keepListeningWhenLocked = flag.on) }
                }
            },
        ),
    )

    // ---- Answers --------------------------------------------------------------------------------

    /** Applies or dismisses [items]; each is checked again first. */
    fun resolve(items: List<ReviewItem>, apply: Boolean) {
        val place = active ?: return
        scope.launch {
            val catalog = catalog(place.id, place.profile).associateBy { it.key }
            for (item in items) {
                val request = item.request
                val setting = catalog[request.key]
                val outcome = when {
                    setting == null || !setting.accepts(request.value) ->
                        Outcome("refused", "This app doesn't let Hermes change this setting to that value.")

                    !apply -> Outcome("dismissed", "The user said no.")

                    request.from != null && setting.read() != request.from && setting.read() != request.value ->
                        Outcome(
                            "conflict",
                            "The user changed this setting after Hermes read it; the user's value was kept.",
                        )

                    else -> {
                        setting.write(request.value)
                        Outcome("applied", "")
                    }
                }
                record(place, request.id, outcome)
            }
            val done = items.mapTo(HashSet()) { it.request.id }
            _review.update { list -> list.filterNot { it.request.id in done } }
            sync()
        }
    }

    /** Puts the changes back, each only if nothing changed it since. */
    fun undo(notice: AppliedNotice) {
        val place = active ?: return
        _notice.update { if (it === notice) null else it }
        scope.launch {
            val catalog = catalog(place.id, place.profile).associateBy { it.key }
            for (change in notice.changes) {
                val setting = catalog[change.request.key] ?: continue
                if (setting.read() != change.request.value) continue
                setting.write(change.previous)
                record(place, change.request.id, Outcome("undone", "The user undid this change."))
            }
            sync()
        }
    }

    fun noticeShown(notice: AppliedNotice) = _notice.update { if (it === notice) null else it }

    private data class Outcome(val status: String, val note: String)

    private suspend fun record(place: Place, requestId: String, outcome: Outcome) {
        app.addSettingResult(place.id, place.profile, requestId, outcome.status, outcome.note)
    }

    // ---- Syncing --------------------------------------------------------------------------------

    /** A round that answered something syncs again, up to three rounds, as on iOS. */
    private suspend fun syncRounds() {
        repeat(MAX_ROUNDS) { if (!syncOnce()) return }
    }

    /** One sync; true when it answered a request (so another round reports it). */
    private suspend fun syncOnce(): Boolean {
        val place = active ?: return false
        val until = synchronized(unavailableUntil) { unavailableUntil[place] }
        if (until != null && clock() < until) return false
        val mode = voice.device.first().hermesChanges
        val catalog = catalog(place.id, place.profile)
        val results = app.settingResults(place.id, place.profile)
        val body = buildJsonObject {
            put("installation_id", app.installationId())
            put("device", device.name)
            put("app_version", device.appVersion)
            put("mode", mode.key)
            put(
                "settings",
                JsonArray(
                    catalog.map { setting ->
                        buildJsonObject {
                            put("key", setting.key)
                            put("title", setting.title)
                            put("section", setting.section)
                            put("description", setting.description)
                            put("tier", if (setting.small) "small" else "ask")
                            put("kind", if (setting.choices == null) "bool" else "choice")
                            put("value", setting.read().json())
                            setting.choices?.let { choices ->
                                put(
                                    "choices",
                                    JsonArray(
                                        choices.map { (value, title) ->
                                            buildJsonObject {
                                                put("value", value)
                                                put("title", title)
                                            }
                                        },
                                    ),
                                )
                            }
                        }
                    },
                ),
            )
            put(
                "results",
                JsonArray(
                    results.map { (id, status, note) ->
                        buildJsonObject {
                            put("id", id)
                            put("status", status)
                            if (note.isNotEmpty()) put("note", note)
                        }
                    },
                ),
            )
        }
        val pending = when (val result = plugin.syncSettings(place.id, place.profile, body)) {
            is NetResult.Failure -> {
                if (result.failure.meansUnavailable()) {
                    synchronized(unavailableUntil) { unavailableUntil[place] = clock() + UNAVAILABLE_MS }
                }
                return false
            }

            is NetResult.Success -> result.value
        }
        app.dropSettingResults(place.id, place.profile, results.mapTo(HashSet()) { it.first })
        if (active != place) return false
        // Answered here and not yet heard there: not again.
        val answered = app.settingResults(place.id, place.profile).mapTo(HashSet()) { it.first }
        val byKey = catalog.associateBy { it.key }
        var answeredNow = false
        val toReview = mutableListOf<ReviewItem>()
        val applied = mutableListOf<AppliedNotice.Applied>()
        for (request in pending.filter { it.id !in answered }) {
            val setting = byKey[request.key]
            val current = setting?.read()
            val outcome = when {
                setting == null || current == null || !setting.accepts(request.value) ->
                    Outcome("refused", "This app doesn't let Hermes change this setting to that value.")

                mode == HermesChangesMode.Off ->
                    Outcome("refused", "The user turned off Hermes changing this app's settings.")

                current == request.value -> Outcome("applied", "It already had that value.")

                request.from != null && request.from != current ->
                    Outcome(
                        "conflict",
                        "The user changed this setting after Hermes read it; the user's value was kept.",
                    )

                mode == HermesChangesMode.Small && setting.small -> {
                    setting.write(request.value)
                    applied += AppliedNotice.Applied(request, current, setting.title)
                    Outcome("applied", "")
                }

                else -> {
                    toReview +=
                        ReviewItem(request, setting.title, setting.describe(current), setting.describe(request.value))
                    null
                }
            }
            if (outcome != null) {
                record(place, request.id, outcome)
                answeredNow = true
            }
        }
        _review.value = toReview
        if (applied.isNotEmpty()) _notice.value = AppliedNotice(applied)
        return answeredNow
    }

    /** A plugin without the route (404, 405), or a profile not paired with it (409). */
    private fun ConnectionFailure.meansUnavailable(): Boolean = when (this) {
        is ConnectionFailure.NotFound -> true
        is ConnectionFailure.UnexpectedResponse -> status == METHOD_NOT_ALLOWED || status == CONFLICT
        else -> false
    }

    private companion object {
        const val MAX_ROUNDS = 3
        const val UNAVAILABLE_MS = 15 * 60_000L
        const val METHOD_NOT_ALLOWED = 405
        const val CONFLICT = 409
    }
}

/** The plugin's `talaria-settings` answer: the changes Hermes asked of this phone. */
internal object SettingsJson {
    fun pending(json: JsonElement): List<SettingRequest>? {
        val reply = json as? JsonObject ?: return null
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
        return (reply["pending"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
                ?: return@mapNotNull null
            val key =
                (row["key"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val value = SettingValue.of(row["value"]) ?: return@mapNotNull null
            SettingRequest(
                id = id,
                key = key,
                value = value,
                from = SettingValue.of(row["from"]),
                reason = (row["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(MAX_REASON),
            )
        }
    }

    private const val MAX_ID = 64
    private const val MAX_REASON = 200
}
