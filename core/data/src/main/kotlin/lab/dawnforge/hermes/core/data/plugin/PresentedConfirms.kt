package lab.dawnforge.hermes.core.data.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What the phone tells a host about itself in an exchange. */
data class AppDevice(val name: String, val appVersion: String)

/**
 * The confirmations Hermes waits on from the active dashboard and profile, kept in step with the
 * plugin's `present/exchange`: answers go up, what still waits comes down. It exchanges when the
 * app comes to the front, when a confirmation's push arrives, and when a chat sees Hermes ask. One
 * exchange runs at a time; asking during one runs one more round after it, so nothing is dropped.
 * Answers are kept on disk until the host hears them, so one given offline is reported later.
 * A Go confirmation for directions opens Maps once its answer is on its way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PresentedConfirms(
    private val dashboards: DashboardRepository,
    private val plugin: PushPluginRepository,
    private val settings: AppSettings,
    private val device: AppDevice,
    /** Whether a screen of the app is visible ([lab.dawnforge.hermes.core.data.gateway.AppForeground]). */
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val navigator: MapsNavigator? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Place(val dashboardId: DashboardId, val profile: String)

    /** A Go confirmation Hermes was just seen sending in the chat on screen, until when. */
    private data class Expected(val promptId: String, val sessionId: String, val until: Long)

    @Volatile
    private var expected: Expected? = null

    private val prompts = MutableStateFlow<List<ConfirmPrompt>>(emptyList())

    /** Put off until the app next comes to the front; Hermes still waits on them. */
    private val putOff = MutableStateFlow<Set<String>>(emptySet())
    private val rounds = Channel<Unit>(Channel.CONFLATED)
    private val _cards = MutableSharedFlow<List<ScreenCard>>(extraBufferCapacity = CARD_BUFFER)

    /**
     * Cards Hermes newly put on a voice conversation's screen, as each exchange brings them; each is
     * sent once. Those already there at the first exchange were for an earlier conversation.
     */
    val cards: SharedFlow<List<ScreenCard>> = _cards.asSharedFlow()
    private var seenCards: List<String>? = null

    @Volatile
    private var active: Place? = null

    /** Where the plugin can't exchange (too old, or the profile isn't paired), and until when. */
    private val unavailableUntil = HashMap<Place, Long>()

    /** The confirmation to show now: the oldest still waiting that wasn't put off. */
    val current: Flow<ConfirmPrompt?> = combine(prompts, putOff) { waiting, later ->
        waiting.firstOrNull { it.id !in later && !it.isExpired(clock()) }
    }.distinctUntilChanged()

    /** The confirmations still waiting, now: what a spoken yes or no may answer. */
    fun waiting(): List<ConfirmPrompt> {
        val now = clock()
        return prompts.value.filter { !it.isExpired(now) }
    }

    /** The active dashboard and profile, whose confirmations are shown. */
    val place: Flow<Pair<DashboardId, String>?> = dashboards.activeDashboardId.flatMapLatest { id ->
        if (id == null) flowOf(null) else dashboards.activeProfile(id).map { id to it }
    }.distinctUntilChanged()

    init {
        scope.launch {
            place.collect { next ->
                // Another host or profile: its confirmations aren't this one's.
                active = next?.let { Place(it.first, it.second) }
                seenCards = null
                prompts.value = emptyList()
                putOff.value = emptySet()
                if (next != null) sync()
            }
        }
        scope.launch { foreground.collect { if (it) onForeground() } }
        scope.launch { for (round in rounds) exchange() }
    }

    /** Exchanges soon; calls while one runs are answered by one more round. */
    fun sync() {
        rounds.trySend(Unit)
    }

    /** A confirmation's push for [dashboardId]'s [profile]: shown now when it's the active one. */
    fun woken(dashboardId: DashboardId?, profile: String?) {
        val place = active ?: return
        if (dashboardId == null || (place.dashboardId == dashboardId && place.profile == profile)) sync()
    }

    /** The app came to the front: what was put off shows again, and a host that couldn't exchange is asked again. */
    fun onForeground() {
        putOff.value = emptySet()
        synchronized(unavailableUntil) { unavailableUntil.clear() }
        sync()
    }

    /**
     * Takes the confirmation off screen and reports the answer; the first answer wins on the host. A
     * confirmed one with directions opens Maps after the answer is on its way, so Hermes hears it first.
     */
    suspend fun answer(prompt: ConfirmPrompt, outcome: ConfirmOutcome, via: AnswerVia = AnswerVia.Tap) {
        val place = active ?: return
        if (prompts.value.none { it.id == prompt.id }) return
        prompts.update { list -> list.filterNot { it.id == prompt.id } }
        settings.addConfirmAnswer(place.dashboardId, place.profile, ConfirmAnswer(prompt.id, outcome, via))
        sync()
        val maps = prompt.maps
        if (outcome == ConfirmOutcome.Confirmed && maps != null && navigator != null) {
            scope.launch { navigator.open(maps) }
        }
    }

    /**
     * Hermes asked to open Maps (`talaria_maps navigate`) in the chat [sessionId] on screen: that Go
     * confirmation opens Maps without a tap once it arrives, as iOS, while the app is in front.
     */
    fun expectMaps(promptId: String, sessionId: String) {
        expected = Expected(promptId, sessionId, clock() + EXPECTED_MS)
        // Stored before the tool returned: fetch it now.
        sync()
    }

    /** Hidden until the app next comes to the front; it stays waiting on the host. */
    fun putOff(prompt: ConfirmPrompt) {
        putOff.update { it + prompt.id }
    }

    /** Takes confirmations whose time ran out off screen (the host counts them as expired, never a yes). */
    fun dropExpired() {
        val now = clock()
        prompts.update { list -> list.filterNot { it.isExpired(now) } }
    }

    private suspend fun exchange() {
        val place = active ?: return
        val until = synchronized(unavailableUntil) { unavailableUntil[place] }
        if (until != null && clock() < until) return
        val answers = settings.confirmAnswers(place.dashboardId, place.profile)
        val request = PresentExchange(settings.installationId(), device.name, device.appVersion, answers)
        when (val result = plugin.exchangeAll(place.dashboardId, place.profile, request)) {
            is NetResult.Failure -> if (result.failure.meansUnavailable()) {
                synchronized(unavailableUntil) { unavailableUntil[place] = clock() + UNAVAILABLE_MS }
            }

            is NetResult.Success -> {
                val sent = answers.mapTo(HashSet()) { it.id }
                settings.dropConfirmAnswers(place.dashboardId, place.profile, sent)
                if (active != place) return
                // Answered here and still listed there until the answer lands: not again.
                val answered = sent + settings.confirmAnswers(place.dashboardId, place.profile).map { it.id }
                val now = clock()
                prompts.value = result.value.prompts.filter { it.id !in answered && !it.isExpired(now) }
                takeCards(result.value.cards)
                openExpected()
            }
        }
    }

    /** The Go confirmation Hermes was just seen sending in the chat on screen, answered and opened. */
    private suspend fun openExpected() {
        val wanted = expected ?: return
        if (clock() > wanted.until) {
            expected = null
            return
        }
        if (!foreground.value) return
        val prompt = prompts.value.firstOrNull { it.id == wanted.promptId && it.maps != null } ?: return
        expected = null
        // Only from the chat it came from.
        if (prompt.sessionId != wanted.sessionId) return
        answer(prompt, ConfirmOutcome.Confirmed, AnswerVia.Auto)
    }

    private fun takeCards(cards: List<ScreenCard>) {
        val seen = seenCards
        if (seen == null) {
            seenCards = cards.map { it.id }
            return
        }
        val fresh = cards.filter { it.id !in seen }
        if (fresh.isEmpty()) return
        seenCards = (seen + fresh.map { it.id }).takeLast(MAX_SEEN_CARDS)
        _cards.tryEmit(fresh)
    }

    /** A plugin without confirmations (404, 405) or a profile not paired with it (409). */
    private fun ConnectionFailure.meansUnavailable(): Boolean = when (this) {
        is ConnectionFailure.NotFound -> true
        is ConnectionFailure.UnexpectedResponse -> status == METHOD_NOT_ALLOWED || status == CONFLICT
        else -> false
    }

    private companion object {
        const val UNAVAILABLE_MS = 15 * 60_000L
        const val EXPECTED_MS = 120_000L
        const val MAX_SEEN_CARDS = 200
        const val CARD_BUFFER = 8
        const val METHOD_NOT_ALLOWED = 405
        const val CONFLICT = 409
    }
}
