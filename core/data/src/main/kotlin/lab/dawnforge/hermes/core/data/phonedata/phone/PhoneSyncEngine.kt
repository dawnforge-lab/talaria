package lab.dawnforge.hermes.core.data.phonedata.phone

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataFeature
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What the host has from this phone, so a pass sends only what changed (iOS's `PhoneSyncState`). */
@Serializable
data class PhoneSyncState(
    val destination: PhoneDataDestination? = null,
    val kid: String? = null,
    val fingerprint: String? = null,
    val lastFullMs: Long? = null,
    val eventHashes: Map<String, String> = emptyMap(),
    val calendarsHash: String? = null,
    val statusHash: String? = null,
    val statusSentMs: Long? = null,
    val focusHash: String? = null,
    val alarmsHash: String? = null,
    val contactsHash: String? = null,
    val frameHash: String? = null,
    val lastIngestMs: Long? = null,
    val lastSyncMs: Long? = null,
    /** Changes the host had waiting at the last pass. */
    val pending: Int = 0,
    val lastError: String? = null,
    /** How changes went, until an exchange reports them. */
    val unreported: List<PhoneChangeResult> = emptyList(),
    /** Every change answered lately, so one that comes back is reported again, never applied twice. */
    val answered: Map<String, PhoneChangeResult> = emptyMap(),
) {
    /** A new key or a wiped host: nothing counts as sent. */
    fun forgetSent() = copy(
        fingerprint = null,
        lastFullMs = null,
        eventHashes = emptyMap(),
        calendarsHash = null,
        statusHash = null,
        statusSentMs = null,
        focusHash = null,
        alarmsHash = null,
        contactsHash = null,
        frameHash = null,
    )
}

/** What the engine reads from the phone: the calendar, and its status and Do Not Disturb. */
interface PhoneSources {
    val canRead: Boolean
    val canWrite: Boolean

    fun calendarsJson(hidden: Set<String>): List<JsonObject>

    fun events(
        fromMs: Long,
        toMs: Long,
        hidden: Set<String>,
        details: Boolean,
        emails: Boolean,
        limit: Int,
    ): List<JsonObject>

    fun status(nowMs: Long): JsonObject

    fun focus(nowMs: Long): JsonObject
}

/** The phone's own calendar provider and system state. */
class DevicePhoneSources(private val calendars: CalendarSource, private val state: PhoneStatusSource) : PhoneSources {
    override val canRead: Boolean get() = calendars.canRead
    override val canWrite: Boolean get() = calendars.canWrite

    override fun calendarsJson(hidden: Set<String>) = calendars.calendarsJson(hidden)

    override fun events(fromMs: Long, toMs: Long, hidden: Set<String>, details: Boolean, emails: Boolean, limit: Int) =
        calendars.events(fromMs, toMs, hidden, details, emails, limit)

    override fun status(nowMs: Long) = state.snapshot(nowMs)

    override fun focus(nowMs: Long) = state.focus(nowMs)
}

/** Applies Hermes' changes to the phone's calendar and alarms. */
interface PhoneChangeApplier {
    /** Applies [change]; the item it made or changed, when there's one to name. */
    suspend fun apply(change: PhoneChange): String?

    /** Undoes an item added without asking (it goes; no new result is reported, as on iOS). */
    suspend fun undo(kind: PhoneChangeKind, itemId: String)
}

/** A change that went in without asking, for the banner with Undo. */
data class AutoAdded(val change: PhoneChange, val itemId: String?)

/** How a pass went. */
sealed interface PhoneSyncOutcome {
    data object Off : PhoneSyncOutcome

    data class Synced(val pending: Int) : PhoneSyncOutcome

    data class Failed(val failure: ConnectionFailure) : PhoneSyncOutcome
}

/**
 * Sends the calendar and phone to Hermes, as iOS's `PhoneSyncEngine`: one check with the host,
 * then what changed since the host last took it (events by occurrence, compared by hash), with
 * everything whole every day or after the user changed what's shared. A pass with nothing new
 * still posts every 15 minutes, so the host knows the phone is there.
 */
class PhoneSyncEngine(
    private val uploader: PhoneDataUploader,
    private val sources: PhoneSources,
    private val store: DataStore<Preferences>,
    /** The alarms and timers this app keeps, as the host takes them; null while unreadable. */
    private val alarms: suspend () -> List<JsonObject>? = { emptyList() },
    /** The people the user picked, as the host takes them. */
    private val contacts: suspend () -> List<JsonObject> = { emptyList() },
    /** Applies Hermes' changes; none in tests that only send. */
    private val applier: PhoneChangeApplier? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val running = Mutex()

    private val _prompts = MutableStateFlow<List<PhoneChange>>(emptyList())

    /** Hermes' changes waiting for the user's yes or no, oldest first (the card shows the first). */
    val prompts: StateFlow<List<PhoneChange>> = _prompts.asStateFlow()

    private val _autoAdded = MutableStateFlow<AutoAdded?>(null)

    /** The last change that went in without asking, until dismissed or undone. */
    val autoAdded: StateFlow<AutoAdded?> = _autoAdded.asStateFlow()

    val sharing: Flow<PhoneSharing> = store.data.map { prefs -> decode(prefs[SHARING], PhoneSharing()) }

    val state: Flow<PhoneSyncState> = store.data.map { prefs -> decode(prefs[STATE], PhoneSyncState()) }

    suspend fun setSharing(change: (PhoneSharing) -> PhoneSharing) {
        store.edit { prefs ->
            prefs[SHARING] =
                json.encodeToString(PhoneSharing.serializer(), change(decode(prefs[SHARING], PhoneSharing())))
        }
    }

    /** One pass; a pass already running is waited for, not repeated. */
    suspend fun sync(): PhoneSyncOutcome = running.withLock { pass() }

    private suspend fun pass(): PhoneSyncOutcome {
        val sharing = sharing.first()
        val destination = sharing.destination
        if (!sharing.isOn || destination == null) return PhoneSyncOutcome.Off
        var state = state.first()
        if (state.destination != destination) state = PhoneSyncState(destination = destination)
        val id = DashboardId(destination.dashboard)
        val profile = destination.profile

        val prepared = when (val ready = uploader.prepare(id, profile, PhoneDataFeature.Phone)) {
            is NetResult.Success -> ready.value
            is NetResult.Failure -> return fail(state, ready.failure)
        }
        if (prepared.fresh || prepared.kid != state.kid) state = state.forgetSent().copy(kid = prepared.kid)

        val now = clock()
        val full = state.fingerprint != sharing.fingerprint ||
            state.lastFullMs.let { it == null || now - it > FULL_SYNC_MS }
        val readsEvents = sharing.calendar == CalendarAccess.Full && sources.canRead
        val batch = linkedMapOf<String, JsonElement>(
            "sent_at" to JsonPrimitive(PhoneWire.format(now)),
            "time_zone" to JsonPrimitive(ZoneId.systemDefault().id),
            "sharing" to sharing.wire(sources.canRead, sources.canWrite),
        )
        var next = state

        val calendarList = if (readsEvents) sources.calendarsJson(sharing.hiddenCalendars) else emptyList()
        val calendarsHash = hash(JsonArray(calendarList))
        if (full || calendarsHash != state.calendarsHash) {
            batch["calendars"] = JsonArray(calendarList)
            next = next.copy(calendarsHash = calendarsHash)
        }

        if (readsEvents) {
            val (from, to) = window(sharing.futureDays)
            val events = sources.events(
                from,
                to,
                sharing.hiddenCalendars,
                sharing.details,
                sharing.details && sharing.attendeeEmails,
                MAX_EVENTS,
            )
            val section = diff(events, state.eventHashes, full, truncated = events.size >= MAX_EVENTS)
            if (full || section.upsert.isNotEmpty() || section.delete.isNotEmpty()) {
                batch["events"] = buildJsonObject {
                    put("full", full)
                    put("from", PhoneWire.format(from))
                    put("to", PhoneWire.format(to))
                    put("upsert", JsonArray(section.upsert))
                    put("delete", JsonArray(section.delete.map(::JsonPrimitive)))
                }
            }
            next = next.copy(eventHashes = section.hashes)
        } else {
            // The host drops every stored event when sharing says add-only or off.
            next = next.copy(eventHashes = emptyMap())
        }

        var statusSent = false
        if (sharing.status) {
            val snapshot = sources.status(now)
            val statusHash = hash(comparableStatus(snapshot))
            val stale = state.statusSentMs.let { it == null || now - it > STATUS_REFRESH_MS }
            if (full || statusHash != state.statusHash || stale) {
                batch["status"] = snapshot
                next = next.copy(statusHash = statusHash)
                statusSent = true
            }
        } else {
            next = next.copy(statusHash = null)
        }

        if (sharing.focus) {
            val focus = sources.focus(now)
            val focusHash = hash(JsonObject(focus - "at"))
            if (full || focusHash != state.focusHash) {
                batch["focus"] = focus
                next = next.copy(focusHash = focusHash)
            }
        } else {
            next = next.copy(focusHash = null)
        }

        if (sharing.alarms) {
            // Unreadable alarms keep what the host has until the next pass.
            alarms()?.let { list ->
                val alarmsHash = hash(JsonArray(list))
                if (full || alarmsHash != state.alarmsHash) {
                    batch["alarms"] = JsonArray(list)
                    next = next.copy(alarmsHash = alarmsHash)
                }
            }
        } else {
            next = next.copy(alarmsHash = null)
        }

        if (sharing.contacts) {
            val people = contacts()
            val contactsHash = hash(JsonArray(people))
            if (full || contactsHash != state.contactsHash) {
                batch["contacts"] = JsonArray(people)
                next = next.copy(contactsHash = contactsHash)
            }
        } else {
            next = next.copy(contactsHash = null)
        }

        val frame = hash(JsonObject(batch.filterKeys { it == "time_zone" || it == "sharing" }))
        val news = batch.keys.any { it !in FRAME_KEYS }
        val keepaliveDue = state.lastIngestMs.let { it == null || now - it < 0 || now - it >= KEEPALIVE_MS }
        var pending = prepared.pending ?: state.pending
        if (news || prepared.pending == null || frame != state.frameHash || keepaliveDue) {
            val batchId = UUID.randomUUID().toString().replace("-", "")
            batch["batch_id"] = JsonPrimitive(batchId)
            when (val sent = uploader.ingest(id, profile, PhoneDataFeature.Phone, batchId, JsonObject(batch))) {
                is NetResult.Failure -> return fail(state, sent.failure)
                is NetResult.Success -> pending = (sent.value["pending"] as? JsonPrimitive)?.intOrNull ?: 0
            }
            next = next.copy(frameHash = frame, lastIngestMs = now)
        }
        next = next.copy(
            fingerprint = sharing.fingerprint,
            lastFullMs = if (full) now else next.lastFullMs,
            statusSentMs = if (statusSent) now else next.statusSentMs,
            lastSyncMs = now,
            pending = pending,
            lastError = null,
        )
        save(next)
        if (applier != null && (pending > 0 || next.unreported.isNotEmpty() || _prompts.value.isNotEmpty())) {
            when (val traded = exchange(id, profile, sharing, next)) {
                is NetResult.Failure -> return fail(next, traded.failure)
                is NetResult.Success -> next = traded.value
            }
            // What went in without asking is reported straight away, not on the next pass.
            if (next.unreported.isNotEmpty()) {
                when (val again = exchange(id, profile, sharing, next)) {
                    is NetResult.Failure -> return fail(next, again.failure)
                    is NetResult.Success -> next = again.value
                }
            }
        }
        return PhoneSyncOutcome.Synced(pending)
    }

    /**
     * Trades with the host (`/phone/exchange`): the results not yet reported go, and its waiting
     * changes come back. A change already answered is reported again; a new one goes in at once
     * when the host and the user allow it without asking, and otherwise waits for the user.
     */
    private suspend fun exchange(
        id: DashboardId,
        profile: String,
        sharing: PhoneSharing,
        state: PhoneSyncState,
    ): NetResult<PhoneSyncState> {
        val callId = UUID.randomUUID().toString().replace("-", "")
        val body = buildJsonObject {
            put("call_id", callId)
            put("results", JsonArray(state.unreported.take(MAX_RESULTS).map(PhoneChangeResult::wire)))
        }
        val answer = when (val traded = uploader.exchange(id, profile, "changes", callId, body)) {
            is NetResult.Success -> traded.value
            is NetResult.Failure -> return traded
        }
        if ((answer["call_id"] as? JsonPrimitive)?.content != callId) {
            return NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "The exchange answered another call"))
        }
        var next = state.copy(unreported = state.unreported.drop(MAX_RESULTS))
        val waiting = mutableListOf<PhoneChange>()
        val changes = (answer["changes"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.let(PhoneChange::parse)
        }
        for (change in changes) {
            val known = next.answered[change.id]
            if (known != null) {
                next = next.copy(unreported = next.unreported + known)
                continue
            }
            if (change.auto && change.kind.addsWithoutAsking && sharing.autoAdd) {
                val result = applyChange(change)
                next = next.record(result)
                if (result.outcome == PhoneChangeResult.APPLIED) {
                    next = next.seedAdded(change, result.itemId)
                    _autoAdded.value = AutoAdded(change, result.itemId)
                }
            } else {
                waiting += change
            }
        }
        _prompts.value = waiting
        save(next)
        return NetResult.Success(next)
    }

    /** The user's answer to a card: applied, or dismissed; reported right away. */
    suspend fun answer(change: PhoneChange, approve: Boolean) {
        _prompts.value = _prompts.value.filterNot { it.id == change.id }
        running.withLock {
            var state = state.first()
            if (change.id in state.answered) return@withLock
            val result = if (approve) applyChange(change) else PhoneChangeResult(change.id, PhoneChangeResult.DISMISSED)
            state = state.record(result)
            if (result.outcome == PhoneChangeResult.APPLIED) state = state.seedAdded(change, result.itemId)
            save(state)
        }
        sync()
    }

    /** Undo for something added without asking: it goes from the phone, and the next upload tells the host. */
    suspend fun undo(added: AutoAdded) {
        _autoAdded.value = null
        val itemId = added.itemId ?: return
        runCatching { applier?.undo(added.change.kind, itemId) }
        sync()
    }

    fun dismissAutoAdded() {
        _autoAdded.value = null
    }

    private suspend fun applyChange(change: PhoneChange): PhoneChangeResult {
        val applier = applier ?: return PhoneChangeResult(change.id, PhoneChangeResult.FAILED, note = "Not set up")
        return try {
            PhoneChangeResult(change.id, PhoneChangeResult.APPLIED, itemId = applier.apply(change))
        } catch (e: PhoneChangeError) {
            PhoneChangeResult(change.id, PhoneChangeResult.FAILED, note = e.message)
        } catch (e: SecurityException) {
            PhoneChangeResult(change.id, PhoneChangeResult.FAILED, note = CalendarWriter.DENIED)
        }
    }

    private fun PhoneSyncState.record(result: PhoneChangeResult) = copy(
        unreported = unreported + result,
        answered = (answered + (result.id to result)).entries.toList().takeLast(MAX_ANSWERED).associate { it.toPair() },
    )

    /** A new event counts as sent but changed, so the next upload sends it (or its deletion, after Undo). */
    private fun PhoneSyncState.seedAdded(change: PhoneChange, itemId: String?) =
        if (change.kind == PhoneChangeKind.AddEvent &&
            itemId != null
        ) {
            copy(eventHashes = eventHashes + (itemId to ""))
        } else {
            this
        }

    /**
     * Sharing turned off: optionally deletes this phone's data on the host first, then forgets
     * the state. True when the host confirmed the deletion.
     */
    suspend fun turnOff(deleteFromHost: Boolean): Boolean = running.withLock {
        val destination = sharing.first().destination
        var deleted = false
        if (deleteFromHost && destination != null) {
            deleted = uploader.wipe(DashboardId(destination.dashboard), destination.profile, PhoneDataFeature.Phone) is
                NetResult.Success
        }
        setSharing { PhoneSharing() }
        save(PhoneSyncState())
        deleted
    }

    /** Signing out of the dashboard the data goes to, or removing it, stops sending it there. */
    suspend fun forget(id: DashboardId) = running.withLock {
        if (sharing.first().destination?.dashboard != id.value) return@withLock
        setSharing { PhoneSharing() }
        save(PhoneSyncState())
    }

    private suspend fun fail(state: PhoneSyncState, failure: ConnectionFailure): PhoneSyncOutcome {
        save(state.copy(lastError = failure.toString()))
        return PhoneSyncOutcome.Failed(failure)
    }

    private suspend fun save(state: PhoneSyncState) {
        store.edit { it[STATE] = json.encodeToString(PhoneSyncState.serializer(), state) }
    }

    private fun <T> decode(text: String?, fallback: T): T where T : Any = text?.let {
        @Suppress("UNCHECKED_CAST")
        runCatching {
            when (fallback) {
                is PhoneSharing -> json.decodeFromString(PhoneSharing.serializer(), it)
                is PhoneSyncState -> json.decodeFromString(PhoneSyncState.serializer(), it)
                else -> null
            } as T?
        }.getOrNull()
    } ?: fallback

    /** The days shared: a week back, and [futureDays] ahead, whole days in the phone's zone. */
    private fun window(futureDays: Int): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(PhoneSharing.PAST_DAYS.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(futureDays + 1L).atStartOfDay(zone).toInstant().toEpochMilli()
        return from to to
    }

    companion object {
        /** A section's changes: the items to send, the ids gone, and what the host has after. */
        data class Section(val upsert: List<JsonObject>, val delete: List<String>, val hashes: Map<String, String>)

        /**
         * What changed in [items] since [sent]: new and changed items, and (unless the list was cut
         * at its limit, or it all goes whole) the ids gone.
         */
        fun diff(
            items: List<JsonObject>,
            sent: Map<String, String>,
            full: Boolean,
            truncated: Boolean = false,
        ): Section {
            val hashes = LinkedHashMap<String, String>()
            val upsert = ArrayList<JsonObject>()
            for (item in items) {
                val id = (item["id"] as? JsonPrimitive)?.content ?: continue
                val hash = hash(item)
                hashes[id] = hash
                if (full || sent[id] != hash) upsert += item
            }
            if (full || truncated) return Section(upsert, emptyList(), hashes)
            return Section(upsert, sent.keys.filter { it !in hashes }.sorted(), hashes)
        }

        /** A hash of [value] that doesn't depend on key order. */
        fun hash(value: JsonElement): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun canonical(value: JsonElement): String = when (value) {
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, inner) ->
                JsonPrimitive(key).toString() + ":" + canonical(inner)
            }

            is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }

            else -> value.toString()
        }

        /**
         * The status as far as sending it again goes: its time left out, and the battery in 5%
         * steps above 20%, so a phone in use doesn't upload with every percent it loses.
         */
        fun comparableStatus(status: JsonObject): JsonObject {
            val battery = (status["battery"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val rounded = if (battery != null && battery > LOW_BATTERY) {
                kotlin.math.floor(battery * BATTERY_STEPS) / BATTERY_STEPS
            } else {
                battery
            }
            return JsonObject(
                (status - "at").let { if (rounded != null) it + ("battery" to JsonPrimitive(rounded)) else it },
            )
        }

        private val SHARING = stringPreferencesKey("phonedata.phone.sharing")
        private val STATE = stringPreferencesKey("phonedata.phone.state")
        private val FRAME_KEYS = setOf("sent_at", "time_zone", "sharing", "batch_id")
        const val MAX_EVENTS = 4_000
        private const val MAX_RESULTS = 50
        private const val MAX_ANSWERED = 200
        private const val FULL_SYNC_MS = 24 * 3_600_000L
        private const val STATUS_REFRESH_MS = 15 * 60_000L
        private const val KEEPALIVE_MS = 15 * 60_000L
        private const val LOW_BATTERY = 0.2
        private const val BATTERY_STEPS = 20.0
    }
}
