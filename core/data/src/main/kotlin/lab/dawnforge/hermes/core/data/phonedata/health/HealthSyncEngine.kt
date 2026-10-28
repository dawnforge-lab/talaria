package lab.dawnforge.hermes.core.data.phonedata.health

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataFeature
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What a pass may leave out, as iOS's `HealthPassOptions`. */
data class HealthPassOptions(
    /** Send every recent window, changed or not (the user asked). */
    val resendRecent: Boolean = false,
    /** The data saver: only what changed, and older history waits. */
    val lowData: Boolean = false,
)

sealed interface HealthOutcome {
    data object Synced : HealthOutcome

    /** Stopped at the deadline; the next pass goes on. */
    data object Unfinished : HealthOutcome

    data class Paused(val pause: HealthPause) : HealthOutcome

    /** Sharing is off. */
    data object Off : HealthOutcome

    data class Failed(val failure: ConnectionFailure?) : HealthOutcome
}

/**
 * One sync pass, as iOS's `HealthSyncEngine`: make sure the phone and the Hermes profile share a key,
 * recompute the recent days (a watch syncs late) and send the windows that changed since the host
 * last took them, then new and deleted workouts, sleep stages and raw readings, then one more step
 * of older history. Recent days go whole every few hours, or when the user asks.
 *
 * Everything can be read again from Health Connect, so there's no outbox: a stream's position, the
 * history cursor or a window's hash moves only once the host has taken the batch, and a pass that
 * stops (at its deadline, or a failure) is simply picked up by the next one.
 */
class HealthSyncEngine(
    private val uploader: PhoneDataUploader,
    private val source: HealthSource,
    private val store: DataStore<Preferences>,
    /** What the host shows this phone as. */
    private val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val running = Mutex()

    val preferences: Flow<HealthPreferences> =
        store.data.map { decode(it[PREFERENCES], HealthPreferences.serializer(), HealthPreferences()) }
    val state: Flow<HealthSyncState> = store.data.map {
        decode(it[STATE], HealthSyncState.serializer(), HealthSyncState())
    }

    suspend fun setPreferences(change: (HealthPreferences) -> HealthPreferences): HealthPreferences {
        var result = HealthPreferences()
        store.edit { prefs ->
            result = change(decode(prefs[PREFERENCES], HealthPreferences.serializer(), HealthPreferences()))
            prefs[PREFERENCES] = json.encodeToString(HealthPreferences.serializer(), result)
        }
        return result
    }

    private suspend fun editState(change: (HealthSyncState) -> HealthSyncState): HealthSyncState {
        var result = HealthSyncState()
        store.edit { prefs ->
            result = change(decode(prefs[STATE], HealthSyncState.serializer(), HealthSyncState()))
            prefs[STATE] = json.encodeToString(HealthSyncState.serializer(), result)
        }
        return result
    }

    /** Runs a pass until [deadlineMs]; one already running is waited for first. */
    suspend fun sync(deadlineMs: Long, options: HealthPassOptions = HealthPassOptions()): HealthOutcome =
        running.withLock { pass(deadlineMs, options) }

    /**
     * Sharing turned off: forget the key and where each stream was, and delete this phone's data on
     * the host first when [deleteFromHost]. True when the host confirmed the deletion.
     */
    suspend fun turnOff(deleteFromHost: Boolean): Boolean = running.withLock {
        val destination = state.first().destination ?: preferences.first().destination
        var deleted = false
        if (destination != null) {
            if (deleteFromHost) {
                val result = uploader.wipe(
                    DashboardId(destination.dashboard),
                    destination.profile,
                    PhoneDataFeature.Health,
                )
                deleted = result is NetResult.Success ||
                    (result is NetResult.Failure && result.failure is ConnectionFailure.NotFound)
            }
            uploader.forget(DashboardId(destination.dashboard), destination.profile, PhoneDataFeature.Health)
        }
        editState { HealthSyncState() }
        setPreferences { it.copy(destination = null) }
        deleted
    }

    /** Signing out of the dashboard health data goes to, or removing it, stops sharing there. */
    suspend fun forget(id: DashboardId) = running.withLock {
        val destination = preferences.first().destination ?: return@withLock
        if (destination.dashboard != id.value) return@withLock
        uploader.forget(id, destination.profile, PhoneDataFeature.Health)
        editState { HealthSyncState() }
        setPreferences { it.copy(destination = null) }
    }

    private suspend fun pass(deadlineMs: Long, options: HealthPassOptions): HealthOutcome {
        val preferences = preferences.first()
        val destination = preferences.destination
        if (!preferences.isOn || destination == null) return HealthOutcome.Off
        if (state.first().destination != destination) {
            // Health data never follows the user to another destination.
            editState { HealthSyncState(destination = destination) }
        }
        editState { it.copy(lastAttempt = clock(), pause = null, refusal = null, lastError = null) }
        val id = DashboardId(destination.dashboard)
        val postedBefore = state.first().lastIngest
        return try {
            when (val prepared = uploader.prepare(id, destination.profile, PhoneDataFeature.Health)) {
                is NetResult.Failure -> return failed(prepared.failure)
                is NetResult.Success -> if (prepared.value.fresh) editState { it.restarted() }
            }
            source.prepare()
            prepareHistory(preferences.history)
            val session = Session(id, destination, preferences, options, deadlineMs)
            session.sendRecentDays()
            session.sendEvents()
            // The data saver: older history waits for another network.
            if (!options.lowData) session.sendHistory()
            if (state.first().lastIngest == postedBefore &&
                needsKeepalive(state.first().lastIngest)
            ) {
                session.sendKeepalive()
            }
            editState { it.copy(lastSync = clock(), pause = null, lastError = null) }
            HealthOutcome.Synced
        } catch (_: DeadlineReached) {
            HealthOutcome.Unfinished
        } catch (refused: Refused) {
            failed(refused.failure)
        } catch (unavailable: HealthUnavailable) {
            editState { it.copy(pause = HealthPause.NoAccess, lastError = unavailable.message) }
            HealthOutcome.Paused(HealthPause.NoAccess)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            editState { it.copy(lastError = error.message ?: error.toString()) }
            HealthOutcome.Failed(null)
        }
    }

    private suspend fun failed(failure: ConnectionFailure): HealthOutcome = when {
        failure is ConnectionFailure.SignInRequired || failure is ConnectionFailure.AuthenticationRejected ||
            failure is ConnectionFailure.InteractiveSignInRequired -> {
            editState { it.copy(pause = HealthPause.SignInRequired) }
            HealthOutcome.Paused(HealthPause.SignInRequired)
        }

        failure is ConnectionFailure.NotFound -> {
            editState { it.copy(pause = HealthPause.PluginMissing) }
            HealthOutcome.Paused(HealthPause.PluginMissing)
        }

        failure is ConnectionFailure.Invalid || failure is ConnectionFailure.TooLarge ||
            (
                failure is ConnectionFailure.UnexpectedResponse &&
                    (failure.status == CONFLICT || failure.status == TOO_LARGE)
                ) -> {
            val detail =
                (failure as? ConnectionFailure.Invalid)?.detail
                    ?: (failure as? ConnectionFailure.UnexpectedResponse)?.detail
            editState { it.copy(pause = HealthPause.Refused, refusal = detail) }
            HealthOutcome.Paused(HealthPause.Refused)
        }

        else -> {
            editState { it.copy(lastError = failure.toString()) }
            HealthOutcome.Failed(failure)
        }
    }

    /** How far back sharing reaches, fixed per choice: another choice starts the streams over. */
    private suspend fun prepareHistory(history: HealthHistory) {
        val dates = HealthDates(zone())
        val today = dates.startOfDay(Instant.ofEpochMilli(clock()))
        val start = dates.addDays(today, -(history.days ?: HealthLimits.ALL_HISTORY_DAYS).toLong()).toEpochMilli()
        editState { state ->
            when {
                state.historyStart != null && state.history == history -> state

                state.historyStart != null -> state.copy(
                    historyStart = start,
                    history = history,
                    tokens = emptyMap(),
                    seriesCounts = emptyMap(),
                )

                else -> state.copy(historyStart = start, history = history)
            }
        }
    }

    private fun needsKeepalive(lastIngest: Long?): Boolean {
        lastIngest ?: return true
        val elapsed = clock() - lastIngest
        return elapsed < 0 || elapsed >= KEEPALIVE_MS
    }

    private fun resendsRecent(options: HealthPassOptions, lastWhole: Long?): Boolean {
        if (options.resendRecent) return true
        if (options.lowData) return false
        lastWhole ?: return true
        val elapsed = clock() - lastWhole
        return elapsed < 0 || elapsed >= WHOLE_RESEND_MS
    }

    /** The host refused a batch: the pass stops, and [failed] says why. */
    private class Refused(val failure: ConnectionFailure) : Exception()

    /** The pass's time is up. */
    private class DeadlineReached : Exception()

    /** One pass's sending, with the pieces it shares. */
    private inner class Session(
        val id: DashboardId,
        val destination: PhoneDataDestination,
        val preferences: HealthPreferences,
        val options: HealthPassOptions,
        val deadlineMs: Long,
    ) {
        val zone: ZoneId = this@HealthSyncEngine.zone()
        val dates = HealthDates(zone)
        val categories = preferences.categories

        fun checkTime() {
            if (clock() >= deadlineMs) throw DeadlineReached()
        }

        fun newBatch() = HealthBatch(
            computedAt = Instant.ofEpochMilli(clock()),
            deviceName = deviceName,
            zone = zone,
            categories = HealthCategory.entries.filter { it in categories },
            detailed = HealthCategory.entries.filter { it in preferences.detailed && it in categories },
        )

        /**
         * Sends [batch] in parts. Returns the windows (print keys) in parts where the host kept a
         * newer computation of some window over this one (`stale_windows`): those aren't the host's.
         */
        suspend fun send(batch: HealthBatch): Set<String> {
            val notTaken = mutableSetOf<String>()
            for (part in batch.split()) {
                val answer = post(part)
                if (((answer["stale_windows"] as? JsonPrimitive)?.intOrNull ?: 0) > 0) {
                    notTaken += part.windowPrints().map { it.first }
                }
            }
            return notTaken
        }

        private suspend fun post(part: HealthBatch): JsonObject {
            checkTime()
            var newKey = false
            val result = uploader.ingest(id, destination.profile, PhoneDataFeature.Health, part.batchId) { fresh ->
                newKey = newKey || fresh
                part.wire()
            }
            return when (result) {
                is NetResult.Failure -> throw Refused(result.failure)

                is NetResult.Success -> {
                    // A key set up mid-pass: the host has only this batch, so everything goes again next time.
                    editState { state -> (if (newKey) state.restarted() else state).copy(lastIngest = clock()) }
                    result.value
                }
            }
        }

        /** An upload with nothing in it, so the host knows the phone still shares and has nothing new. */
        suspend fun sendKeepalive() {
            post(newBatch())
        }

        /** Buckets and nights for [from, to), as windows the host replaces. */
        suspend fun windows(from: Instant, to: Instant): List<HealthWindow> {
            val windows = mutableListOf<HealthWindow>()
            val today = dates.startOfDay(Instant.ofEpochMilli(clock()))
            val hourlyFloor = dates.addDays(today, -HealthLimits.HOURLY_HISTORY_DAYS.toLong())
            for (category in HealthCategory.entries.filter { it in categories }) {
                for (metric in category.metrics) {
                    checkTime()
                    windows += HealthWindow(
                        metric.wire,
                        HealthGrain.Day,
                        metric.unit,
                        from,
                        to,
                        source.buckets(metric, HealthGrain.Day, from, to, zone),
                    )
                    if (metric.hourly && to > hourlyFloor) {
                        val start = maxOf(from, hourlyFloor)
                        windows += HealthWindow(
                            metric.wire,
                            HealthGrain.Hour,
                            metric.unit,
                            start,
                            to,
                            source.buckets(metric, HealthGrain.Hour, start, to, zone),
                        )
                    }
                }
            }
            if (HealthCategory.Sleep in categories) {
                checkTime()
                val (nightsFrom, nightsTo) = HealthSleepNights.window(
                    from.atZone(zone).toLocalDate(),
                    to.atZone(zone).toLocalDate(),
                    zone,
                )
                val segments = source.sleepSegments(nightsFrom, nightsTo)
                windows +=
                    HealthWindow(
                        "sleep",
                        HealthGrain.Night,
                        "min",
                        nightsFrom,
                        nightsTo,
                        HealthSleepNights.summaries(segments, zone),
                    )
            }
            return windows
        }

        /** Today and the days a watch may still fill in: the windows that changed, or all every few hours. */
        suspend fun sendRecentDays() {
            val now = clock()
            val today = dates.startOfDay(Instant.ofEpochMilli(now))
            val from = dates.addDays(today, -(HealthLimits.RECOMPUTED_DAYS - 1).toLong())
            val to = dates.addDays(today, 1)
            val all = newBatch().copy(windows = windows(from, to))
            val prints = all.windowPrints()
            val whole = resendsRecent(options, state.first().recentWholeAt)
            val sent = state.first().recentWindows.orEmpty()
            val batch = if (whole) {
                all
            } else {
                all.copy(
                    windows = all.windows.zip(prints).filter { (_, print) ->
                        sent[print.first] !=
                            print.second
                    }.map { it.first },
                )
            }
            val notTaken = send(batch)
            editState { state ->
                state.copy(
                    recentWindows = prints.filter { it.first !in notTaken }.toMap(),
                    recentWholeAt = if (whole) now else state.recentWholeAt,
                    backfillCursor = state.backfillCursor ?: from.toEpochMilli(),
                )
            }
        }

        /**
         * New, changed and deleted workouts, sleep stages and (where chosen) raw readings, each from its
         * own position.
         */
        suspend fun sendEvents() {
            val since = state.first().historyStart?.let(Instant::ofEpochMilli) ?: return
            val streams = buildList {
                if (HealthCategory.Workouts in categories) add(HealthStream.Workouts)
                if (HealthCategory.Sleep in categories) add(HealthStream.Sleep)
                for (category in HealthCategory.entries.filter {
                    it in categories && it in preferences.detailed &&
                        it.hasDetailedReadings
                }) {
                    addAll(category.metrics.map(HealthStream::Samples))
                }
            }
            for (stream in streams) {
                var more = true
                while (more) {
                    checkTime()
                    val known = state.first()
                    val page = source.changes(stream, since, known.tokens[stream.key], HealthLimits.EVENTS_PER_PAGE)
                    val takenBack = page.series.flatMap { (session, now) ->
                        val before = known.seriesCounts[session] ?: 0
                        ((now ?: 0) until before).map { "$session:$it" }
                    }
                    val batch = newBatch().copy(events = page.added, deleted = page.deleted + takenBack)
                    if (!batch.isEmpty) send(batch)
                    editState { state ->
                        val stages = state.seriesCounts.toMutableMap()
                        page.series.forEach { (session, count) ->
                            if (count ==
                                null
                            ) {
                                stages.remove(session)
                            } else {
                                stages[session] = count
                            }
                        }
                        val tokens = if (page.position ==
                            null
                        ) {
                            state.tokens - stream.key
                        } else {
                            state.tokens + (stream.key to page.position)
                        }
                        state.copy(tokens = tokens, seriesCounts = stages)
                    }
                    more = page.more
                }
            }
        }

        /** One step further back per loop, newest first, until history is in. */
        suspend fun sendHistory() {
            val target = state.first().historyStart ?: return
            if (categories != state.first().backfilledCategories) {
                // A category turned on after some history went still needs its older days.
                val missing = categories - state.first().backfilledCategories.orEmpty()
                val today = dates.startOfDay(Instant.ofEpochMilli(clock()))
                val from = dates.addDays(today, -(HealthLimits.RECOMPUTED_DAYS - 1).toLong()).toEpochMilli()
                editState { state ->
                    state.copy(
                        backfilledCategories = categories,
                        backfillCursor = if (missing.isNotEmpty()) from else state.backfillCursor,
                    )
                }
            }
            while (true) {
                val cursor = state.first().backfillCursor ?: return
                if (cursor <= target) return
                checkTime()
                val start =
                    max(
                        target,
                        dates.addDays(
                            Instant.ofEpochMilli(cursor),
                            -HealthLimits.BACKFILL_CHUNK_DAYS.toLong(),
                        ).toEpochMilli(),
                    )
                send(newBatch().copy(windows = windows(Instant.ofEpochMilli(start), Instant.ofEpochMilli(cursor))))
                editState { it.copy(backfillCursor = start) }
            }
        }
    }

    private fun <T> decode(text: String?, serializer: KSerializer<T>, fallback: T): T =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: fallback

    companion object {
        private val PREFERENCES = stringPreferencesKey("health.preferences")
        private val STATE = stringPreferencesKey("health.state")
        private const val CONFLICT = 409
        private const val TOO_LARGE = 413

        /** Recent days go whole this often even when nothing changed, as on iOS. */
        const val WHOLE_RESEND_MS = 6 * 3_600_000L

        /** A pass with nothing to send still posts this often: only an upload moves the host's `last_seen`. */
        const val KEEPALIVE_MS = 15 * 60_000L
    }
}
