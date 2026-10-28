package lab.dawnforge.hermes.core.data.phonedata.location

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataFeature
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Why sending stopped, as Settings › Location says it. */
@Serializable
enum class LocationPause {
    /** Sign in to the dashboard again. */
    SignInRequired,

    /** The plugin on the host has no location sync. */
    PluginMissing,

    /** The host refused what the phone sent (`refusal` says why). */
    Refused,
}

/** What the engine remembers between runs: no locations, only what the host last acknowledged. */
@Serializable
data class LocationSyncState(
    val generation: String? = null,
    /** The saved places the host has, as [LocationRules.placesDigest]. */
    val placesDigest: String? = null,
    val lastSent: Long? = null,
    /** When the last fix reached the host, and why it was taken. */
    val lastFixSent: Long? = null,
    val lastFixSource: FixSource? = null,
    val lastAttempt: Long? = null,
    val pause: LocationPause? = null,
    val refusal: String? = null,
    val lastError: String? = null,
)

/** The host's `/location/status`. */
data class LocationHostStatus(
    val stored: Boolean,
    val readsToday: Int,
    val rules: Int,
    val lastSync: String?,
    val locateAvailable: Boolean,
)

/** One time Hermes read the location (never the location itself). */
data class LocationRead(val at: String, val action: String, val sessionTitle: String?, val platform: String?)

/** A place rule Hermes set up ("when I leave Home, Mon–Fri…"). */
data class LocationRule(
    val id: String,
    val what: String,
    /** A saved place's id, or `*` for any saved place. */
    val placeId: String?,
    val placeName: String?,
    /** `arrive`, `leave` or `either`. */
    val event: String,
    val days: String?,
    val hours: String?,
    val calls: Boolean,
    /** `daily` (once a day) or `every` (each time, at least 30 minutes apart). */
    val repeat: String?,
    val lastFired: String?,
) {
    val isAnySavedPlace: Boolean get() = placeId == "*"
}

/**
 * Talks to the plugin's location routes for the profile sharing goes to, as iOS's
 * `LocationSyncEngine`: sends sealed batches (`/location/ingest`, with a key set up as needed),
 * and reads what Settings › Location shows (status, the read log, place rules) or deletes. It also
 * keeps the phone's location choices, saved places and what's unsent.
 */
class LocationSyncEngine(
    private val uploader: PhoneDataUploader,
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    enum class SendOutcome {
        Sent,

        /** The host won't take this batch as it is: sending it again won't help. */
        Refused,

        /** Not sent now (no connection, signed out, a timeout): try later. */
        Failed,
    }

    val config: Flow<LocationConfig> = store.data.map {
        decode(it[CONFIG], LocationConfig.serializer(), LocationConfig())
    }
    val unsent: Flow<LocationUnsent> = store.data.map {
        decode(it[UNSENT], LocationUnsent.serializer(), LocationUnsent())
    }
    val state: Flow<LocationSyncState> = store.data.map {
        decode(it[STATE], LocationSyncState.serializer(), LocationSyncState())
    }

    /** Bumped by each new or forgotten key: the host then drops the latest fix it had from this phone. */
    @Volatile
    var keyAgreements: Int = 0
        private set

    suspend fun editConfig(change: (LocationConfig) -> LocationConfig): LocationConfig {
        var result = LocationConfig()
        store.edit { prefs ->
            result = change(decode(prefs[CONFIG], LocationConfig.serializer(), LocationConfig()))
            prefs[CONFIG] = json.encodeToString(LocationConfig.serializer(), result)
        }
        return result
    }

    suspend fun editUnsent(change: (LocationUnsent) -> LocationUnsent): LocationUnsent {
        var result = LocationUnsent()
        store.edit { prefs ->
            result = change(decode(prefs[UNSENT], LocationUnsent.serializer(), LocationUnsent()))
            prefs[UNSENT] = json.encodeToString(LocationUnsent.serializer(), result)
        }
        return result
    }

    private suspend fun editState(change: (LocationSyncState) -> LocationSyncState) {
        store.edit { prefs ->
            val next = change(decode(prefs[STATE], LocationSyncState.serializer(), LocationSyncState()))
            prefs[STATE] = json.encodeToString(LocationSyncState.serializer(), next)
        }
    }

    /**
     * Sends one batch to [config]'s destination, with the saved places when the host's copy is out
     * of date (or its key is new).
     */
    suspend fun send(batch: LocationBatch, config: LocationConfig): SendOutcome {
        val destination = config.destination ?: return SendOutcome.Failed
        val id = DashboardId(destination.dashboard)
        val known = state.first()
        editState { it.copy(lastAttempt = clock()) }
        var placesSent: List<SavedPlace>? = null
        val result = uploader.ingest(id, destination.profile, PhoneDataFeature.Location, batch.batchId) { newKey ->
            if (newKey) keyAgreements++
            val stale = newKey || known.placesDigest != LocationRules.placesDigest(config.places)
            placesSent = if (stale) config.places else null
            batch.copy(places = placesSent).wire()
        }
        return when (result) {
            is NetResult.Success -> {
                acknowledge(batch, placesSent, result.value)
                SendOutcome.Sent
            }

            is NetResult.Failure -> failed(result.failure, id, destination.profile)
        }
    }

    private suspend fun acknowledge(batch: LocationBatch, placesSent: List<SavedPlace>?, answer: JsonObject) {
        val at = clock()
        val generation = (answer["generation"] as? JsonPrimitive)?.contentOrNull
        editState { state ->
            var digest = placesSent?.let(LocationRules::placesDigest) ?: state.placesDigest
            // A new store on the host has none of the places.
            if (generation != null && state.generation != null && state.generation != generation &&
                placesSent == null
            ) {
                digest = null
            }
            state.copy(
                generation = generation ?: state.generation,
                placesDigest = digest,
                lastSent = at,
                lastFixSent = if (batch.fix != null) at else state.lastFixSent,
                lastFixSource = batch.fix?.source ?: state.lastFixSource,
                pause = null,
                refusal = null,
                lastError = null,
            )
        }
    }

    private suspend fun failed(failure: ConnectionFailure, id: DashboardId, profile: String): SendOutcome = when {
        failure is ConnectionFailure.SignInRequired || failure is ConnectionFailure.AuthenticationRejected ||
            failure is ConnectionFailure.InteractiveSignInRequired -> {
            editState { it.copy(pause = LocationPause.SignInRequired) }
            SendOutcome.Failed
        }

        failure is ConnectionFailure.NotFound -> {
            // A bare 404 could be a proxy or a restarting host: the status route tells whether the
            // plugin is gone.
            val missing = (uploader.status(id, profile, PhoneDataFeature.Location) as? NetResult.Failure)
                ?.failure is ConnectionFailure.NotFound
            editState {
                if (missing) it.copy(pause = LocationPause.PluginMissing) else it.copy(lastError = failure.toString())
            }
            SendOutcome.Failed
        }

        failure is ConnectionFailure.Invalid || failure is ConnectionFailure.TooLarge ||
            (failure is ConnectionFailure.UnexpectedResponse && failure.status == TOO_LARGE) -> {
            editState { it.copy(pause = LocationPause.Refused, refusal = failure.detail()) }
            SendOutcome.Refused
        }

        failure is ConnectionFailure.UnexpectedResponse && failure.status == CONFLICT -> {
            // layer_off and the like: what the batch says doesn't match what's shared.
            editState { it.copy(lastError = failure.detail) }
            SendOutcome.Refused
        }

        else -> {
            editState { it.copy(lastError = failure.toString()) }
            SendOutcome.Failed
        }
    }

    private fun ConnectionFailure.detail(): String? = when (this) {
        is ConnectionFailure.Invalid -> detail
        is ConnectionFailure.UnexpectedResponse -> detail
        else -> null
    }

    // Reading and deleting

    suspend fun status(destination: PhoneDataDestination): NetResult<LocationHostStatus> =
        when (val read = uploader.status(destination.id, destination.profile, PhoneDataFeature.Location)) {
            is NetResult.Failure -> read

            is NetResult.Success -> {
                val body = read.value
                NetResult.Success(
                    LocationHostStatus(
                        stored = body.bool("stored") ?: false,
                        readsToday = body.int("reads_24h") ?: 0,
                        rules = body.int("rules") ?: 0,
                        lastSync = (body["device"] as? JsonObject)?.text("last_sync"),
                        locateAvailable = (body["locate"] as? JsonObject)?.bool("available") ?: false,
                    ),
                )
            }
        }

    suspend fun reads(destination: PhoneDataDestination): NetResult<List<LocationRead>> = when (
        val read = uploader.read(
            destination.id,
            destination.profile,
            PhoneDataFeature.Location,
            listOf("reads"),
            mapOf("limit" to "50"),
        )
    ) {
        is NetResult.Failure -> read

        is NetResult.Success -> NetResult.Success(
            (read.value["reads"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                LocationRead(
                    row.text("at") ?: return@mapNotNull null,
                    row.text("action").orEmpty(),
                    row.text("session_title"),
                    row.text("platform"),
                )
            },
        )
    }

    suspend fun rules(destination: PhoneDataDestination): NetResult<List<LocationRule>> = when (
        val read = uploader.read(
            destination.id,
            destination.profile,
            PhoneDataFeature.Location,
            listOf("rules"),
        )
    ) {
        is NetResult.Failure -> read

        is NetResult.Success -> NetResult.Success(
            (read.value["rules"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                LocationRule(
                    id = row.text("id") ?: return@mapNotNull null,
                    what = row.text("what") ?: row.text("task").orEmpty(),
                    placeId = row.text("place_id"),
                    placeName = row.text("place_name"),
                    event = row.text("event").orEmpty(),
                    days = row.listText("days"),
                    hours = row.listText("hours"),
                    calls = row.bool("call") ?: false,
                    repeat = row.text("repeat"),
                    lastFired = row.text("last_fired"),
                )
            },
        )
    }

    suspend fun deleteRule(destination: PhoneDataDestination, ruleId: String): NetResult<JsonObject> =
        uploader.remove(destination.id, destination.profile, PhoneDataFeature.Location, listOf("rules", ruleId))

    /**
     * Deletes what this phone sent (or, with [everything], every device's and the place rules) from
     * the host, and the key: the host forgets it. A host with no location sync left has nothing to delete.
     */
    suspend fun wipe(destination: PhoneDataDestination, everything: Boolean): NetResult<Unit> {
        val result = if (everything) {
            uploader.wipeAll(destination.id, destination.profile, PhoneDataFeature.Location)
        } else {
            uploader.wipe(destination.id, destination.profile, PhoneDataFeature.Location)
        }
        if (result is NetResult.Failure && result.failure !is ConnectionFailure.NotFound) return result
        forgetHost(destination)
        return NetResult.Success(Unit)
    }

    /** Sharing moved to another destination, or the host's copy was deleted. */
    suspend fun forgetHost(destination: PhoneDataDestination?) {
        destination?.let { uploader.forget(it.id, it.profile, PhoneDataFeature.Location) }
        keyAgreements++
        editState { LocationSyncState() }
    }

    private val PhoneDataDestination.id get() = DashboardId(dashboard)

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull

    /** Days and hours as the host wrote them: a string, or a list. */
    private fun JsonObject.listText(key: String): String? = when (val value = get(key)) {
        is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotEmpty)

        is JsonArray -> value.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        }.takeIf { it.isNotEmpty() }?.joinToString(", ")

        else -> null
    }

    private fun <T> decode(text: String?, serializer: KSerializer<T>, fallback: T): T =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: fallback

    companion object {
        private val CONFIG = stringPreferencesKey("location.config")
        private val UNSENT = stringPreferencesKey("location.unsent")
        private val STATE = stringPreferencesKey("location.state")
        private const val CONFLICT = 409
        private const val TOO_LARGE = 413
    }
}
