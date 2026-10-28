package lab.dawnforge.hermes.core.data.phonedata.location

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Local time with its offset, as the contract writes times (`2026-10-14T08:02:11+01:00`). */
class LocationTime(val zone: ZoneId = ZoneId.systemDefault()) {
    fun format(epochMs: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).atZone(zone).toOffsetDateTime(),
    )
}

/**
 * The precision rules, applied on the phone before anything is sealed, as iOS's
 * `LocationPrecisionRules`. The host applies them again, so a bug here can't widen what it keeps:
 * - places: the saved place the fix is in, if any. No coordinates, accuracy, area or distances.
 * - area: the coordinate to 2 decimals (about 1 km), accuracy at least 1000 m, the area, distances
 *   to saved places to 100 m.
 * - precise: the coordinate and accuracy as measured, the area, distances to 10 m, speed and course.
 * - Inside a private place, at any precision: that place's id and nothing else.
 */
object LocationRules {
    /** Area precision never claims better than this. */
    const val AREA_MIN_ACCURACY = 1_000.0

    /** Past this accuracy a saved place can't be told apart from its neighbours: none is named. */
    const val PLACE_MATCH_MAX_ACCURACY = 500.0

    /** What the host lists a private place as: the agent's text, so English as iOS sends it. */
    const val PRIVATE_PLACE_NAME = "Private place"

    private const val PLACES_STEP = 100.0
    private const val PRECISE_STEP = 10.0
    private const val AREA_DECIMALS = 2
    private const val PRECISE_DECIMALS = 6

    /** The saved place the fix is in: the one whose centre is nearest for its radius. */
    fun containingPlace(raw: RawFix, places: List<SavedPlace>): SavedPlace? =
        if (raw.accuracy > PLACE_MATCH_MAX_ACCURACY) null else nearestInside(raw, places)

    /**
     * The private place the fix falls in, whatever its accuracy: where a private place is must never
     * follow from a fix sent near it.
     */
    fun containingPrivatePlace(raw: RawFix, places: List<SavedPlace>): SavedPlace? =
        nearestInside(raw, places.filter { it.isPrivate })

    private fun nearestInside(raw: RawFix, places: List<SavedPlace>): SavedPlace? = places
        .map { it to it.distanceTo(raw.latitude, raw.longitude) / it.radius }
        .filter { (_, ratio) -> ratio <= 1 }
        .minByOrNull { (_, ratio) -> ratio }
        ?.first

    /** The `fix` object at [requested] precision. [approximateOnly]: no place named, nothing precise. */
    fun wire(
        fix: FixRecord,
        requested: LocationPrecision,
        places: List<SavedPlace>,
        approximateOnly: Boolean,
        time: LocationTime,
    ): JsonObject {
        val precision = if (approximateOnly) requested.cappedAt(LocationPrecision.Area) else requested
        val raw = fix.raw
        return buildJsonObject {
            put("id", fix.id)
            put("at", time.format(raw.timeMs))
            put("source", fix.source.wire)
            put("precision", precision.wire)
            // Inside a private place only its id goes, even for a fix too rough for an ordinary place.
            containingPrivatePlace(raw, places)?.let {
                put("place_id", it.id)
                return@buildJsonObject
            }
            if (!approximateOnly) containingPlace(raw, places)?.let { put("place_id", it.id) }
            if (precision == LocationPrecision.Places) return@buildJsonObject

            val step = if (precision == LocationPrecision.Precise) PRECISE_STEP else PLACES_STEP
            val distances = places.filterNot { it.isPrivate }
                .associate { it.id to LocationGeo.roundedDistance(it.distanceTo(raw.latitude, raw.longitude), step) }
            if (distances.isNotEmpty()) {
                put(
                    "distances_m",
                    buildJsonObject {
                        distances.forEach { (id, m) -> put(id, m) }
                    },
                )
            }
            fix.area?.takeUnless { it.isEmpty }?.let { put("area", it.wire()) }

            if (precision == LocationPrecision.Area) {
                put("lat", LocationGeo.rounded(raw.latitude, AREA_DECIMALS))
                put("lon", LocationGeo.rounded(raw.longitude, AREA_DECIMALS))
                put("accuracy_m", LocationGeo.halfAway(max(AREA_MIN_ACCURACY, raw.accuracy)).roundToInt())
            } else {
                put("lat", LocationGeo.rounded(raw.latitude, PRECISE_DECIMALS))
                put("lon", LocationGeo.rounded(raw.longitude, PRECISE_DECIMALS))
                put("accuracy_m", LocationGeo.halfAway(raw.accuracy).roundToInt())
                raw.speed?.let { put("speed_mps", LocationGeo.rounded(it, 1)) }
                raw.course?.let { put("course", LocationGeo.rounded(it, 0)) }
            }
        }
    }

    /** The `places` list: names and kinds only; a private place without its name or kind. */
    fun placesWire(places: List<SavedPlace>): JsonArray = JsonArray(
        places.take(SavedPlace.MAX_COUNT).map { place ->
            buildJsonObject {
                put("id", place.id)
                put("name", if (place.isPrivate) PRIVATE_PLACE_NAME else place.name.take(SavedPlace.MAX_NAME))
                put("kind", if (place.isPrivate) PlaceKind.Other.wire else place.kind.wire)
                put("private", place.isPrivate)
            }
        },
    )

    /** Changes when anything the host keeps about the places changes. */
    fun placesDigest(places: List<SavedPlace>): String = places.take(SavedPlace.MAX_COUNT).joinToString("\n") { place ->
        val name = if (place.isPrivate) PRIVATE_PLACE_NAME else place.name.take(SavedPlace.MAX_NAME)
        val kind = if (place.isPrivate) PlaceKind.Other.wire else place.kind.wire
        listOf(place.id, name, kind, if (place.isPrivate) "1" else "0").joinToString("|")
    }
}

/** One upload's plaintext, as iOS's `LocationBatch`. */
data class LocationBatch(
    val batchId: String = LocationIds.batchId(),
    val sentAtMs: Long,
    val settings: LocationSettings,
    val time: LocationTime = LocationTime(),
    val approximateOnly: Boolean = false,
    /** The saved places, when the host's copy needs replacing. */
    val places: List<SavedPlace>? = null,
    /** All saved places, for naming the place a fix is in and distances. */
    val knownPlaces: List<SavedPlace> = emptyList(),
    val fix: FixRecord? = null,
    val events: List<PlaceEvent> = emptyList(),
    val locateId: String? = null,
) {
    fun wire(): JsonObject = buildJsonObject {
        put("batch_id", batchId)
        put("sent_at", time.format(sentAtMs))
        put("sharing", settings.wire(sentAtMs, time))
        places?.let { put("places", LocationRules.placesWire(it)) }
        fix?.let {
            put("fix", LocationRules.wire(it, settings.precision(it.source), knownPlaces, approximateOnly, time))
        }
        if (events.isNotEmpty()) put("events", JsonArray(events.map { it.wire(time) }))
        locateId?.let { put("locate_id", it) }
    }
}
