package lab.dawnforge.hermes.core.data.plugin

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/**
 * When Hermes may phone this profile's user, kept on the host (`talaria-calls.json`). The optional
 * ones are null when the host's plugin doesn't have them; an older plugin refuses keys it doesn't
 * know, so only those it reported go back.
 */
data class CallSettings(
    val enabled: Boolean = false,
    /** Call when the user asked for it ("call me when it's done"). */
    val whenAsked: Boolean = true,
    /** Hermes may call on its own judgment. */
    val decides: Boolean? = null,
    /** Call when Hermes waited a minute for an approval or answer, or a turn failed. */
    val alerts: Boolean? = null,
    val minGapSeconds: Int = 120,
    val perHour: Int = 6,
    val perDay: Int = 20,
    /** The user's note on what's worth a call Hermes decides to make. */
    val rules: String? = null,
) {
    fun json(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("when_asked", whenAsked)
        put("min_gap_s", minGapSeconds)
        put("per_hour", perHour)
        put("per_day", perDay)
        decides?.let { put("decides", it) }
        alerts?.let { put("alerts", it) }
        rules?.let { put("rules", it) }
    }

    companion object {
        fun of(json: JsonObject): CallSettings? {
            fun flag(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull
            fun number(key: String) = (json[key] as? JsonPrimitive)?.intOrNull
            return CallSettings(
                enabled = flag("enabled") ?: return null,
                whenAsked = flag("when_asked") ?: true,
                decides = flag("decides"),
                alerts = flag("alerts"),
                minGapSeconds = number("min_gap_s") ?: DEFAULT_GAP,
                perHour = number("per_hour") ?: DEFAULT_PER_HOUR,
                perDay = number("per_day") ?: DEFAULT_PER_DAY,
                rules = (json["rules"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        }

        /** The note as the host keeps it: each line trimmed, no empty lines. */
        fun rules(text: String): String = text.lines().map(String::trim).filter(String::isNotEmpty).joinToString("\n")

        private const val DEFAULT_GAP = 120
        private const val DEFAULT_PER_HOUR = 6
        private const val DEFAULT_PER_DAY = 20
    }
}

/** What the host says of calls for a profile: its settings, their bounds, and whether it's paired. */
data class CallsStatus(
    val paired: Boolean,
    val settings: CallSettings,
    val gapBounds: IntRange = 30..3_600,
    val perHourBounds: IntRange = 1..30,
    val perDayBounds: IntRange = 1..60,
    val rulesMax: Int = 500,
)

/** How a call from Hermes went unanswered: turned down, or rang out (or came too late to ring). */
enum class CallOutcome(val key: String) {
    Declined("declined"),
    Missed("missed"),
}

/** An unanswered call the host hasn't heard about yet. */
@Serializable
data class PendingCallOutcome(
    val dashboard: String,
    val profile: String,
    val callId: String,
    val sessionIds: List<String>,
    val outcome: String,
    val kind: String? = null,
    val title: String? = null,
    val reason: String? = null,
    /** When it happened, on the phone's clock (ms). */
    val at: Long,
)

/**
 * Calls from Hermes, on the push plugin (`/api/plugins/talaria_push/calls…`, plugin 0.13+): their
 * settings, and what became of a call the user didn't answer, which the chat's next turn hears.
 * Those are kept on the phone until the host has them, for up to 23 hours, as on iOS.
 */
class HermesCallsRepository(
    /** Lazily: forgetting a dashboard's calls happens during sign-out, which requests need. */
    private val dashboardRequests: () -> DashboardRequests,
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) : DashboardDataOwner {
    private val json = Json { ignoreUnknownKeys = true }
    private val delivering = Mutex()
    private val requests: DashboardRequests get() = dashboardRequests()

    suspend fun status(id: DashboardId, profile: String): NetResult<CallsStatus> = when (
        val result = requests.run(id) { url, jar, cf ->
            get(url, jar, cf, API, listOf("calls"), mapOf("profile" to profile))
        }
    ) {
        is NetResult.Failure -> result

        is NetResult.Success -> parse(result.value as? JsonObject)?.let { NetResult.Success(it) }
            ?: NetResult.Failure(MALFORMED)
    }

    /** Saves [settings]; answers them as the host now has them. */
    suspend fun save(id: DashboardId, profile: String, settings: CallSettings): NetResult<CallSettings> {
        val body = buildJsonObject { put("settings", settings.json()) }
        val result = requests.run(id) { url, jar, cf ->
            put(url, jar, cf, API, listOf("calls"), body, mapOf("profile" to profile))
        }
        return when (result) {
            is NetResult.Failure -> result

            is NetResult.Success -> ((result.value as? JsonObject)?.get("settings") as? JsonObject)
                ?.let(CallSettings::of)
                ?.let { NetResult.Success(it) }
                ?: NetResult.Failure(MALFORMED)
        }
    }

    private fun parse(reply: JsonObject?): CallsStatus? {
        if ((reply?.get("ok") as? JsonPrimitive)?.booleanOrNull != true) return null
        val settings = (reply["settings"] as? JsonObject)?.let(CallSettings::of) ?: return null
        val bounds = reply["bounds"] as? JsonObject
        fun range(key: String, fallback: IntRange): IntRange {
            val entry = bounds?.get(key) as? JsonObject ?: return fallback
            val low = (entry["min"] as? JsonPrimitive)?.intOrNull ?: return fallback
            val high = (entry["max"] as? JsonPrimitive)?.intOrNull ?: return fallback
            return if (low <= high) low..high else fallback
        }
        return CallsStatus(
            paired = (reply["paired"] as? JsonPrimitive)?.booleanOrNull == true,
            settings = settings,
            gapBounds = range("min_gap_s", 30..3_600),
            perHourBounds = range("per_hour", 1..30),
            perDayBounds = range("per_day", 1..60),
            rulesMax = (reply["rules_max"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_RULES_MAX,
        )
    }

    // ---- Unanswered calls ------------------------------------------------------------------------

    /** Keeps an unanswered call to tell the host, then tells it what's waiting. */
    suspend fun record(outcome: PendingCallOutcome) {
        edit { list -> (list.filterNot { it.callId == outcome.callId } + outcome).takeLast(MAX_PENDING) }
        deliver()
    }

    /**
     * Tells the host the unanswered calls it hasn't heard of. One it refuses for good (a malformed
     * one, a profile no longer paired, a plugin without the route) is dropped; one that couldn't
     * go now is kept for the next try.
     */
    suspend fun deliver() = delivering.withLock {
        for (pending in read()) {
            val body = buildJsonObject {
                put("call_id", pending.callId)
                put("session_ids", JsonArray(pending.sessionIds.map(::JsonPrimitive)))
                put("outcome", pending.outcome)
                put("age_s", ((clock() - pending.at) / MILLIS).coerceIn(0, MAX_AGE_S))
                pending.kind?.let { put("kind", it) }
                pending.title?.let { put("title", it) }
                pending.reason?.let { put("reason", it) }
            }
            val result = requests.run(DashboardId(pending.dashboard)) { url, jar, cf ->
                post(url, jar, cf, API, body, mapOf("profile" to pending.profile), listOf("calls", "outcomes"))
            }
            val done = when (result) {
                is NetResult.Success -> true
                is NetResult.Failure -> result.failure.isFinal()
            }
            if (done) edit { list -> list.filterNot { it.callId == pending.callId } }
        }
    }

    private fun ConnectionFailure.isFinal(): Boolean = when (this) {
        is ConnectionFailure.Invalid, is ConnectionFailure.NotFound, is ConnectionFailure.TooLarge -> true
        is ConnectionFailure.UnexpectedResponse -> status in FINAL_STATUSES
        else -> false
    }

    private suspend fun read(): List<PendingCallOutcome> =
        parsePending(store.data.first()[KEY]).filter { clock() - it.at < MAX_KEPT_MS }

    private fun parsePending(text: String?): List<PendingCallOutcome> =
        text?.let { runCatching { json.decodeFromString<List<PendingCallOutcome>>(it) }.getOrNull() }.orEmpty()

    private suspend fun edit(change: (List<PendingCallOutcome>) -> List<PendingCallOutcome>) {
        store.edit { prefs ->
            prefs[KEY] = json.encodeToString(change(parsePending(prefs[KEY]).filter { clock() - it.at < MAX_KEPT_MS }))
        }
    }

    /** Signing out of a dashboard, or removing it, forgets its unanswered calls. */
    override suspend fun forget(id: DashboardId) = edit { list -> list.filterNot { it.dashboard == id.value } }

    companion object {
        /** A call id as the host gives it: what an outcome reports back. */
        val CALL_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
        private const val API = "/api/plugins/talaria_push"
        private val KEY = stringPreferencesKey("calls.pending_outcomes")
        private const val MAX_PENDING = 20
        private const val MAX_KEPT_MS = 23 * 60 * 60_000L
        private const val MAX_AGE_S = 86_399L
        private const val MILLIS = 1000L
        private const val DEFAULT_RULES_MAX = 500
        private val FINAL_STATUSES = setOf(400, 404, 405, 409, 410, 413, 422)
        private val MALFORMED = ConnectionFailure.UnexpectedResponse(null, "The push plugin gave an unexpected answer")
    }
}
