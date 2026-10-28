package lab.dawnforge.hermes.core.data.phonedata.location

import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination

/** How much a fix tells the host, as iOS's `LocationPrecision`. */
@Serializable
enum class LocationPrecision(val wire: String) {
    /** The saved place it's at, or none: no coordinates, no area. */
    @SerialName("places")
    Places("places"),

    /** Neighbourhood, city and country, and a coordinate to about 1 km. */
    @SerialName("area")
    Area("area"),

    /** The coordinate as measured, with its accuracy. */
    @SerialName("precise")
    Precise("precise"),
    ;

    /** The coarser of the two. */
    fun cappedAt(other: LocationPrecision): LocationPrecision = if (ordinal <= other.ordinal) this else other
}

@Serializable
enum class PlaceKind(val wire: String) {
    @SerialName("home")
    Home("home"),

    @SerialName("work")
    Work("work"),

    @SerialName("gym")
    Gym("gym"),

    @SerialName("school")
    School("school"),

    @SerialName("other")
    Other("other"),
}

/**
 * A place the user saved. Its centre and radius never leave the phone: the host gets its id, name
 * and kind, and distances with each fix.
 */
@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val kind: PlaceKind = PlaceKind.Other,
    /** Inside a private place the host hears only "at a private place". */
    val isPrivate: Boolean = false,
    val latitude: Double,
    val longitude: Double,
    val radius: Double = DEFAULT_RADIUS,
) {
    fun distanceTo(latitude: Double, longitude: Double): Double =
        LocationGeo.distance(this.latitude, this.longitude, latitude, longitude)

    companion object {
        const val MIN_RADIUS = 100.0
        const val MAX_RADIUS = 500.0
        const val DEFAULT_RADIUS = 150.0
        const val RADIUS_STEP = 25.0
        const val MAX_COUNT = 20
        const val MAX_NAME = 40

        /** A place with its name and radius kept to what the host and the geofences take. */
        fun of(
            id: String = newId(),
            name: String,
            kind: PlaceKind,
            isPrivate: Boolean,
            latitude: Double,
            longitude: Double,
            radius: Double = DEFAULT_RADIUS,
        ) = SavedPlace(
            id,
            name.trim().take(MAX_NAME),
            kind,
            isPrivate,
            latitude,
            longitude,
            radius.coerceIn(MIN_RADIUS, MAX_RADIUS),
        )

        /** `p_` and 12 hex digits: inside the host's `^[A-Za-z0-9_-]{1,40}$`. */
        fun newId(): String = "p_" + LocationIds.hex(6)
    }
}

/** Why a fix was taken (`fix.source`). */
@Serializable
enum class FixSource(val wire: String) {
    /** Talking to Hermes: a message sent, a voice conversation. */
    @SerialName("message")
    Message("message"),

    @SerialName("call")
    Call("call"),

    /** In the background: a saved place's arrive or leave, a longer move, the app opened. */
    @SerialName("place")
    Place("place"),

    @SerialName("significant")
    Significant("significant"),

    @SerialName("app")
    App("app"),

    /** Hermes asked for the location now. */
    @SerialName("locate")
    Locate("locate"),
    ;

    val isTalking: Boolean get() = this == Message || this == Call
}

/**
 * What the user chose to share, as iOS's `LocationSharingSettings`. It goes with every batch
 * (`sharing`), so the host always knows what's on, paused or off.
 */
@Serializable
data class LocationSettings(
    val talking: Boolean = false,
    val talkingPrecision: LocationPrecision = LocationPrecision.Precise,
    val background: Boolean = false,
    val backgroundPrecision: LocationPrecision = LocationPrecision.Area,
    val retentionDays: Int = DEFAULT_RETENTION,
    /** Nothing is sent while this is ahead (epoch ms); [FOREVER] is "until I turn it back on". */
    val pausedUntil: Long? = null,
    /** Whether the host may answer maps lookups itself. */
    val mapsHost: Boolean = true,
) {
    val isOn: Boolean get() = talking || background

    fun isPaused(nowMs: Long): Boolean = pausedUntil?.let { it > nowMs } == true

    /** Whether a fix from [source] may be taken and sent now. */
    fun shares(source: FixSource, nowMs: Long): Boolean =
        !isPaused(nowMs) && if (source.isTalking) talking else background

    fun precision(source: FixSource): LocationPrecision =
        if (source.isTalking) talkingPrecision else backgroundPrecision

    /** The batch's `sharing` object. */
    fun wire(nowMs: Long, time: LocationTime): JsonObject = buildJsonObject {
        put("talking", talking)
        put("talking_precision", talkingPrecision.wire)
        put("background", background)
        put("background_precision", backgroundPrecision.wire)
        put("retention_days", if (retentionDays in RETENTION_CHOICES) retentionDays else DEFAULT_RETENTION)
        val paused = pausedUntil
        if (paused != null &&
            paused > nowMs
        ) {
            put("paused_until", time.format(paused))
        } else {
            put("paused_until", JsonNull)
        }
        put("time_zone", time.zone.id)
        put("maps_host", mapsHost)
    }

    companion object {
        val RETENTION_CHOICES = listOf(0, 1, 7, 30, 365)
        const val DEFAULT_RETENTION = 7

        /** "Until I turn it back on": far enough ahead never to pass. */
        const val FOREVER = Long.MAX_VALUE
    }
}

/** Where location goes, and everything kept on the phone about it. */
@Serializable
data class LocationConfig(
    /** The dashboard and profile, fixed when the user first turns a layer on. */
    val destination: PhoneDataDestination? = null,
    val settings: LocationSettings = LocationSettings(),
    val places: List<SavedPlace> = emptyList(),
    /** Android gave only approximate location. */
    val approximateOnly: Boolean = false,
    /**
     * A change of the choices or places the host hasn't had yet (made offline, or the send failed):
     * sent when the host is next reachable, even with sharing off, as "off" and "paused" matter most.
     */
    val settingsPending: Boolean = false,
)

/** A saved place's arrive or leave (`events`). */
@Serializable
data class PlaceEvent(
    val id: String = "e_" + LocationIds.hex(12),
    val arrive: Boolean,
    val placeId: String,
    val atMs: Long,
    val isPrivate: Boolean,
) {
    fun wire(time: LocationTime): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", if (arrive) "arrive" else "leave")
        put("place_id", placeId)
        put("at", time.format(atMs))
        put("private", isPrivate)
    }
}

/** Neighbourhood, city and country from the phone's geocoder. */
@Serializable
data class LocationArea(
    val name: String? = null,
    val locality: String? = null,
    val region: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
) {
    val isEmpty: Boolean get() = listOf(name, locality, region, country, countryCode).all { it.isNullOrEmpty() }

    fun wire(): JsonObject = buildJsonObject {
        // A longer field refuses the whole batch (the host's MAX_AREA_CHARS, in code points).
        fun field(value: String) = value.codePoints().limit(MAX_FIELD.toLong()).toArray().let { String(it, 0, it.size) }
        name?.takeIf(String::isNotEmpty)?.let { put("name", field(it)) }
        locality?.takeIf(String::isNotEmpty)?.let { put("locality", field(it)) }
        region?.takeIf(String::isNotEmpty)?.let { put("region", field(it)) }
        country?.takeIf(String::isNotEmpty)?.let { put("country", field(it)) }
        countryCode?.takeIf { COUNTRY_CODE.matches(it) }?.let { put("country_code", it) }
    }

    companion object {
        const val MAX_FIELD = 80
        private val COUNTRY_CODE = Regex("^[A-Z]{2}$")
    }
}

/** A measured position, before any precision rule. Kept on the phone only until it's sent. */
@Serializable
data class RawFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val timeMs: Long,
    val speed: Double? = null,
    val course: Double? = null,
)

/** A fix as it goes on the wire, built from a measurement. */
@Serializable
data class FixRecord(
    val id: String = "f_" + LocationIds.hex(12),
    val source: FixSource,
    val raw: RawFix,
    val area: LocationArea? = null,
)

/** What's waiting to reach the host, and where the phone last was for each saved place. */
@Serializable
data class LocationUnsent(
    val fix: FixRecord? = null,
    val events: List<PlaceEvent> = emptyList(),
    /** Whether the phone was last inside each saved place, so a geofence's first answer isn't an arrival. */
    val inside: Map<String, Boolean> = emptyMap(),
) {
    val isEmpty: Boolean get() = fix == null && events.isEmpty()

    /** Keeps the newer fix and the newest events. */
    fun keep(fix: FixRecord?, events: List<PlaceEvent>): LocationUnsent {
        val newer = fix?.takeIf { it.raw.timeMs >= (this.fix?.raw?.timeMs ?: Long.MIN_VALUE) } ?: this.fix
        val merged = (this.events + events.filter { event -> this.events.none { it.id == event.id } })
            .sortedBy { it.atMs }
            .takeLast(MAX_EVENTS)
        return copy(fix = newer, events = merged)
    }

    /** Drops what was sent. */
    fun remove(fixId: String?, eventIds: Set<String>): LocationUnsent = copy(
        fix = fix?.takeUnless { it.id == fixId },
        events = events.filterNot { it.id in eventIds },
    )

    companion object {
        const val MAX_EVENTS = 50
    }
}

object LocationIds {
    private val random = SecureRandom()

    fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    fun batchId(): String = "b_" + hex(12)
}

object LocationGeo {
    /** The mean Earth radius, in metres. */
    private const val EARTH_RADIUS = 6_371_008.8

    /** The great-circle distance in metres between two coordinates. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return 2 * EARTH_RADIUS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Rounded to [decimals] places, halves away from zero as iOS rounds. */
    fun rounded(value: Double, decimals: Int): Double {
        val scale = 10.0.pow(decimals)
        return halfAway(value * scale) / scale
    }

    fun roundedDistance(meters: Double, step: Double): Int = (halfAway(meters / step) * step).roundToInt()

    fun halfAway(value: Double): Double = sign(value) * floor(abs(value) + HALF)

    private const val HALF = 0.5
}
