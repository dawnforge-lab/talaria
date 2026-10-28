package lab.dawnforge.hermes.core.data.phonedata.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult

/**
 * Location sharing as a whole, as iOS's `LocationSharingController`: the user's choices and saved
 * places, when a fix is taken (a message sent, a voice conversation, a saved place's arrive or
 * leave, a longer move, the app opened), what's unsent, and sending it to the dashboard and
 * profile sharing goes to.
 *
 * Nothing is taken or sent while a layer is off or sharing is paused. A message never waits long
 * for its fix: at most [TALKING_WAIT_MS], and the fix goes on its own after that.
 */
class LocationSharing(
    private val engine: LocationSyncEngine,
    private val fixes: LocationFixes,
    private val signals: LocationSignals,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val conditions: () -> DeviceConditions = { DeviceConditions() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Layer { Talking, Background }

    private val sending = Mutex()
    private var callWatch: Job? = null
    private var lastCallFix: RawFix? = null

    /** The last fix the host took with a message or a call, so the same place isn't sent with every message. */
    @Volatile
    private var lastTalkingUpload: TalkingUpload? = null

    val config: Flow<LocationConfig> = engine.config
    val state: Flow<LocationSyncState> = engine.state
    val access: LocationAccess get() = fixes.access

    /** At launch: the background signals are set up again, and coming back to the app is watched. */
    fun start() {
        scope.launch { reconfigure() }
        scope.launch { foreground.filter { it }.collect { appBecameActive() } }
    }

    /** The app came to the foreground: permissions may have changed, and anything unsent goes now. */
    suspend fun appBecameActive() {
        noteAccess()
        clearExpiredPause(send = false)
        val config = engine.config.first()
        // Not only while sharing is on: a change to off or paused that didn't reach the host matters most.
        config.destination ?: return
        reconfigure()
        val lastFix = engine.state.first().lastFixSent
        if (config.settings.shares(FixSource.App, clock()) && fixes.access.always &&
            (lastFix == null || clock() - lastFix > APP_FIX_INTERVAL_MS)
        ) {
            shareFix(FixSource.App)
        } else {
            sendWaiting()
        }
    }

    private suspend fun sendWaiting() {
        val config = engine.config.first()
        if (!engine.unsent.first().isEmpty || config.settingsPending) flush(settingsOnly = config.settingsPending)
    }

    /** Whether Android gives only approximate location, kept with the choices once sharing goes somewhere. */
    private suspend fun noteAccess() {
        val approximate = fixes.access.approximateOnly
        val config = engine.config.first()
        if (config.destination != null && config.approximateOnly != approximate) {
            engine.editConfig { it.copy(approximateOnly = approximate) }
            reconfigure()
        }
    }

    private suspend fun reconfigure() {
        val config = engine.config.first()
        signals.configure(config.settings, config.places, bound = config.destination != null)
    }

    /** The phone restarted or the app was updated: geofences and updates are set up again. */
    suspend fun signalsLost() {
        signals.forgetRegistered()
        reconfigure()
    }

    // Talking to Hermes

    /**
     * Before a message goes out: takes and sends a fix, waiting at most [TALKING_WAIT_MS] (the fix
     * goes on its own after that). Returns at once while this layer is off or paused.
     */
    suspend fun beforeSending(source: FixSource = FixSource.Message) {
        val config = engine.config.first()
        if (config.destination == null || !config.settings.shares(source, clock()) || !fixes.access.whileInUse) return
        val upload = scope.launch { shareFix(source) }
        withTimeoutOrNull(TALKING_WAIT_MS) { upload.join() }
    }

    /**
     * A voice conversation started: one fix now, and another whenever the phone has moved 200 m,
     * until [stillActive] says it's over.
     */
    fun voiceCallStarted(stillActive: () -> Boolean) {
        if (callWatch != null) return
        callWatch = scope.launch {
            val config = engine.config.first()
            if (config.destination == null || !config.settings.shares(FixSource.Call, clock()) ||
                !fixes.access.whileInUse
            ) {
                callWatch = null
                return@launch
            }
            lastCallFix = null
            scope.launch { shareFix(FixSource.Call) }
            signals.startCallUpdates { moved -> scope.launch { movedDuringCall(moved) } }
            while (true) {
                delay(CALL_CHECK_MS)
                if (!stillActive() || !engine.config.first().settings.shares(FixSource.Call, clock())) break
            }
            signals.stopCallUpdates()
            lastCallFix = null
            callWatch = null
        }
    }

    fun voiceCallEnded() {
        callWatch?.cancel()
        callWatch = null
        lastCallFix = null
        signals.stopCallUpdates()
    }

    private suspend fun movedDuringCall(fix: RawFix) {
        if (callWatch == null || !engine.config.first().settings.shares(FixSource.Call, clock())) return
        lastCallFix?.let {
            if (LocationGeo.distance(it.latitude, it.longitude, fix.latitude, fix.longitude) <
                LocationSignals.CALL_MOVE_M
            ) {
                return
            }
        }
        lastCallFix = fix
        share(fix, FixSource.Call)
    }

    // In the background

    suspend fun placeStateChanged(placeId: String, inside: Boolean, atMs: Long) {
        val place = engine.config.first().places.firstOrNull { it.id == placeId } ?: return
        var before: Boolean? = null
        engine.editUnsent { unsent ->
            before = unsent.inside[placeId]
            unsent.copy(inside = unsent.inside + (placeId to inside))
        }
        // The first answer for a place is where the phone is, not a move.
        if (before == null || before == inside ||
            !engine.config.first().settings.shares(FixSource.Place, clock())
        ) {
            return
        }
        shareFix(
            FixSource.Place,
            listOf(PlaceEvent(arrive = inside, placeId = place.id, atMs = atMs, isPrivate = place.isPrivate)),
        )
    }

    suspend fun movedSignificantly(fix: RawFix) {
        if (engine.config.first().settings.shares(FixSource.Significant, clock())) share(fix, FixSource.Significant)
    }

    // Hermes asking for the location now

    /** Requests answered lately, so a push delivered twice is answered once. */
    private val answeredLocates = LinkedHashMap<String, Long>()

    /**
     * Answers Hermes asking where the phone is now ([locateId]), as iOS's locate: only for the
     * dashboard and profile location goes to, while background sharing is on and not paused. One fix
     * at the background detail, sent as the answer. True when the host has it.
     */
    suspend fun answerLocate(dashboard: DashboardId, profile: String, locateId: String): Boolean {
        if (!answersLocate(dashboard, profile) || !claimLocate(locateId)) return false
        val config = engine.config.first()
        val fix = fixes.current(
            config.settings.precision(FixSource.Locate),
            maxAgeMs = LocationPolicy.FRESH_AGE_MS,
            timeoutMs = LOCATE_TIMEOUT_MS,
        )
        return share(fix, FixSource.Locate, locateId = locateId)
    }

    /** Whether Hermes asking from [dashboard]'s [profile] would be answered now. */
    suspend fun answersLocate(dashboard: DashboardId, profile: String): Boolean {
        val config = engine.config.first()
        val destination = config.destination ?: return false
        return destination.dashboard == dashboard.value && destination.profile == profile &&
            config.settings.shares(FixSource.Locate, clock())
    }

    @Synchronized
    private fun claimLocate(locateId: String): Boolean {
        val now = clock()
        answeredLocates.entries.removeAll { now - it.value > LOCATE_REPLAY_MS }
        if (locateId in answeredLocates) return false
        answeredLocates[locateId] = now
        return true
    }

    // Taking and sending

    /** Takes a fix for [source] (with any events) and sends it. True when the host has it. */
    suspend fun shareFix(source: FixSource, events: List<PlaceEvent> = emptyList(), locateId: String? = null): Boolean {
        val settings = engine.config.first().settings
        if (!settings.shares(source, clock())) return false
        // An arrive or leave is kept before the fix, so a process ended meanwhile can't lose it.
        if (events.isNotEmpty()) engine.editUnsent { it.keep(null, events) }
        val precision = settings.precision(source)
        val reused = if (source == FixSource.Message) recentFixForMessage(precision) else null
        val fix = reused
            ?: fixes.current(
                precision,
                maxAgeMs = if (source.isTalking) LocationPolicy.FRESH_AGE_MS else BACKGROUND_MAX_AGE_MS,
                timeoutMs = if (source.isTalking) TALKING_TIMEOUT_MS else BACKGROUND_TIMEOUT_MS,
            )
        // The place the host already has from a message moments ago: the message goes without it.
        if (source == FixSource.Message && events.isEmpty() && engine.unsent.first().fix == null && fix != null &&
            !messageFixIsNews(fix, precision)
        ) {
            return true
        }
        return share(fix, source, events, locateId)
    }

    private suspend fun recentFixForMessage(precision: LocationPrecision): RawFix? {
        val known = fixes.lastKnown() ?: return null
        return known.takeIf { LocationPolicy.reusesFix(clock() - it.timeMs, it.accuracy, it.speed, precision) }
    }

    private suspend fun messageFixIsNews(fix: RawFix, precision: LocationPrecision): Boolean =
        LocationPolicy.messageFixIsNews(fix, precision, talkingHost(), lastTalkingUpload, clock(), conditions())

    private suspend fun talkingHost() = TalkingUpload.Host(engine.keyAgreements, engine.state.first().generation)

    suspend fun share(
        fix: RawFix?,
        source: FixSource,
        events: List<PlaceEvent> = emptyList(),
        locateId: String? = null,
    ): Boolean {
        val config = engine.config.first()
        if (config.destination == null || !config.settings.shares(source, clock())) return false
        val record = fix?.let {
            val area = if (config.settings.precision(source) == LocationPrecision.Places) null else fixes.area(it)
            FixRecord(source = source, raw = it, area = area)
        }
        if (record == null && events.isEmpty()) return false
        engine.editUnsent { it.keep(record, events) }
        return flush(locateId = locateId)
    }

    /** Sends the unsent fix and events (or just the choices and places), one batch at a time. */
    suspend fun flush(settingsOnly: Boolean = false, locateId: String? = null): Boolean {
        // Remembered until a batch with these choices reaches the host.
        if (settingsOnly) engine.editConfig { if (it.destination != null) it.copy(settingsPending = true) else it }
        return sending.withLock { sendNow(settingsOnly, locateId) }
    }

    private suspend fun sendNow(settingsOnly: Boolean, locateId: String?): Boolean {
        val config = engine.config.first()
        config.destination ?: return false
        val at = clock()
        // What a layer turned off or a pause no longer allows is dropped, not held for later.
        val pending = engine.editUnsent { unsent ->
            var next = unsent
            if (next.fix?.let { !config.settings.shares(it.source, at) } == true) next = next.copy(fix = null)
            if (!config.settings.shares(FixSource.Place, at)) next = next.copy(events = emptyList())
            next
        }
        if (pending.isEmpty && !settingsOnly && !config.settingsPending) return true
        val batch = LocationBatch(
            sentAtMs = at,
            settings = config.settings,
            approximateOnly = fixes.access.approximateOnly || config.approximateOnly,
            knownPlaces = config.places,
            fix = pending.fix,
            events = pending.events,
            locateId = locateId?.takeIf { pending.fix?.source == FixSource.Locate },
        )
        val outcome = engine.send(batch, config)
        if (outcome == LocationSyncEngine.SendOutcome.Failed) return false
        if (outcome == LocationSyncEngine.SendOutcome.Sent) noteSent(pending.fix, config, at)
        // The host has these choices (or won't take them: sending the same again wouldn't help).
        engine.editConfig {
            if (it.settingsPending && it.settings == config.settings &&
                it.places == config.places
            ) {
                it.copy(settingsPending = false)
            } else {
                it
            }
        }
        engine.editUnsent { it.remove(pending.fix?.id, pending.events.map(PlaceEvent::id).toSet()) }
        return outcome == LocationSyncEngine.SendOutcome.Sent
    }

    private suspend fun noteSent(fix: FixRecord?, config: LocationConfig, atMs: Long) {
        fix ?: return
        // A newer fix from elsewhere is the host's latest now.
        lastTalkingUpload = if (fix.source.isTalking) {
            TalkingUpload(fix.raw, config.settings.precision(fix.source), talkingHost(), atMs)
        } else {
            null
        }
    }

    // Choices

    /**
     * Turns a layer on for [destination] (Android's permission already asked): sharing goes there
     * when nothing is on yet, and the host is told.
     */
    suspend fun turnOn(layer: Layer, destination: PhoneDataDestination) {
        val config = engine.config.first()
        if (config.destination == null || (!config.settings.isOn && config.destination != destination)) {
            if (config.destination != destination) engine.forgetHost(config.destination)
            engine.editConfig { it.copy(destination = destination, approximateOnly = fixes.access.approximateOnly) }
        }
        engine.editConfig {
            it.copy(
                settings = if (layer ==
                    Layer.Talking
                ) {
                    it.settings.copy(talking = true)
                } else {
                    it.settings.copy(background = true)
                },
            )
        }
        reconfigure()
        flush(settingsOnly = true)
    }

    suspend fun turnOff(layer: Layer) {
        engine.editConfig {
            it.copy(
                settings = if (layer ==
                    Layer.Talking
                ) {
                    it.settings.copy(talking = false)
                } else {
                    it.settings.copy(background = false)
                },
            )
        }
        if (layer == Layer.Talking) {
            voiceCallEnded()
            lastTalkingUpload = null
        }
        reconfigure()
        flush(settingsOnly = true)
    }

    suspend fun setPrecision(layer: Layer, precision: LocationPrecision) {
        engine.editConfig {
            it.copy(
                settings = if (layer == Layer.Talking) {
                    it.settings.copy(talkingPrecision = precision)
                } else {
                    it.settings.copy(backgroundPrecision = precision)
                },
            )
        }
        reconfigure()
        flush(settingsOnly = true)
    }

    /** "Keep maps lookups on this phone": the host then never looks up places or routes itself. */
    suspend fun setMapsHost(allowed: Boolean) {
        engine.editConfig { it.copy(settings = it.settings.copy(mapsHost = allowed)) }
        flush(settingsOnly = true)
    }

    suspend fun setRetention(days: Int) {
        if (days !in LocationSettings.RETENTION_CHOICES) return
        engine.editConfig { it.copy(settings = it.settings.copy(retentionDays = days)) }
        flush(settingsOnly = true)
    }

    /** Pauses until [untilMs] ([LocationSettings.FOREVER]: until turned back on), or resumes with null. */
    suspend fun pause(untilMs: Long?) {
        engine.editConfig { it.copy(settings = it.settings.copy(pausedUntil = untilMs)) }
        if (untilMs != null) {
            // What wasn't sent before the pause is dropped.
            engine.editUnsent { it.copy(fix = null, events = emptyList()) }
            lastTalkingUpload = null
            voiceCallEnded()
        }
        flush(settingsOnly = true)
    }

    /** Resumes a pause whose time has passed, and tells the host (pending until it's reachable). */
    suspend fun clearExpiredPause(send: Boolean = true) {
        val until = engine.config.first().settings.pausedUntil ?: return
        if (until > clock()) return
        val config = engine.editConfig {
            it.copy(settings = it.settings.copy(pausedUntil = null), settingsPending = it.destination != null)
        }
        if (send && config.destination != null) flush(settingsOnly = true)
    }

    // Saved places

    /** Where the phone is now, for placing a saved place there. */
    suspend fun here(): RawFix? =
        fixes.current(LocationPrecision.Precise, maxAgeMs = LocationPolicy.FRESH_AGE_MS, timeoutMs = HERE_TIMEOUT_MS)

    /** Addresses and places matching [query], for placing a saved place. */
    suspend fun search(query: String): List<FoundPlace> = fixes.search(query)

    suspend fun savePlace(place: SavedPlace) {
        engine.editConfig { config ->
            val index = config.places.indexOfFirst { it.id == place.id }
            when {
                index >= 0 -> config.copy(places = config.places.toMutableList().also { it[index] = place })
                config.places.size < SavedPlace.MAX_COUNT -> config.copy(places = config.places + place)
                else -> config
            }
        }
        // A moved place starts from where the phone is.
        engine.editUnsent { it.copy(inside = it.inside - place.id) }
        reconfigure()
        flush(settingsOnly = true)
    }

    suspend fun removePlace(id: String) {
        engine.editConfig { config -> config.copy(places = config.places.filterNot { it.id == id }) }
        engine.editUnsent { unsent ->
            unsent.copy(
                inside = unsent.inside - id,
                events = unsent.events.filterNot {
                    it.placeId ==
                        id
                },
            )
        }
        reconfigure()
        flush(settingsOnly = true)
    }

    // Host

    suspend fun status(): NetResult<LocationHostStatus>? = engine.config.first().destination?.let { engine.status(it) }

    suspend fun reads(): NetResult<List<LocationRead>>? = engine.config.first().destination?.let { engine.reads(it) }

    suspend fun rules(): NetResult<List<LocationRule>>? = engine.config.first().destination?.let { engine.rules(it) }

    suspend fun deleteRule(id: String) = engine.config.first().destination?.let { engine.deleteRule(it, id) }

    /**
     * Deletes what this phone (or, with [everything], every device and the place rules) sent to the
     * host. With both layers off, sharing lets go of the destination too.
     */
    suspend fun wipe(everything: Boolean): NetResult<Unit> {
        val destination = engine.config.first().destination ?: return NetResult.Success(Unit)
        val result = engine.wipe(destination, everything)
        if (result is NetResult.Failure) return result
        lastTalkingUpload = null
        engine.editUnsent { it.copy(fix = null, events = emptyList()) }
        engine.editConfig { if (!it.settings.isOn) it.copy(destination = null, settingsPending = false) else it }
        reconfigure()
        return result
    }

    /** Signing out of the dashboard location goes to, or removing it, stops sharing there. */
    suspend fun forget(id: DashboardId) {
        val config = engine.config.first()
        if (config.destination?.dashboard != id.value) return
        voiceCallEnded()
        engine.forgetHost(config.destination)
        engine.editConfig {
            it.copy(
                destination = null,
                settings = it.settings.copy(talking = false, background = false),
                settingsPending = false,
            )
        }
        engine.editUnsent { LocationUnsent() }
        lastTalkingUpload = null
        reconfigure()
    }

    companion object {
        const val TALKING_WAIT_MS = 2_500L

        /** A background fix when the app opens, if the last one is older than this. */
        const val APP_FIX_INTERVAL_MS = 15 * 60_000L
        private const val CALL_CHECK_MS = 15_000L
        private const val TALKING_TIMEOUT_MS = 8_000L
        private const val BACKGROUND_TIMEOUT_MS = 15_000L
        private const val BACKGROUND_MAX_AGE_MS = 120_000L
        private const val HERE_TIMEOUT_MS = 10_000L

        /**
         * The host waits 20 seconds for an answer. iOS gives the fix 6 of them; Android's GPS can take
         * longer to start, and the push and the upload take only a second or two.
         */
        private const val LOCATE_TIMEOUT_MS = 10_000L
        private const val LOCATE_REPLAY_MS = 30_000L
    }
}
