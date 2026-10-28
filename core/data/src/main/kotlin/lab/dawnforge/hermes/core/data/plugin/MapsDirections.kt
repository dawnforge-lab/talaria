package lab.dawnforge.hermes.core.data.plugin

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace

/** Where directions go, read on this phone from a [MapsTarget]'s `to`. */
sealed interface MapsDestination {
    /** The user's own position: never a destination. */
    data object Me : MapsDestination

    /** A place id from an earlier answer: only the engine that gave it can name it. */
    data class PlaceId(val id: String) : MapsDestination

    data class Point(val latitude: Double, val longitude: Double, val name: String? = null) : MapsDestination

    /** An address or a name ("Heathrow Terminal 5", "nearest Boots"): Maps finds it. */
    data class Text(val text: String) : MapsDestination
}

/** Directions links for Google Maps, which needs no key: the Maps app opens them, or the browser without it. */
object MapsDirections {
    const val MAX_TEXT = 200

    private val ME_WORDS = setOf("me", "my location", "current location", "here", "where i am")
    private val CONTROL = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u2028-\\u202e\\u2060-\\u206f\\ufeff]+")
    private const val PLACE_ID = "place_id:"
    private const val LINK = "https://www.google.com/maps/dir/?api=1"
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    /** Text from the host with control and invisible characters out, capped. */
    fun clean(raw: String): String = raw.replace(CONTROL, " ").trim().take(MAX_TEXT)

    /** What `to` names, as iOS reads it: a saved place's name wins over the words themselves. */
    fun destination(raw: String, places: List<SavedPlace>): MapsDestination? {
        val text = clean(raw)
        if (text.isEmpty()) return null
        val lowered = text.lowercase(Locale.ROOT)
        if (lowered in ME_WORDS) return MapsDestination.Me
        if (lowered.startsWith(PLACE_ID)) {
            val id = text.drop(PLACE_ID.length).trim()
            return if (id.isEmpty()) null else MapsDestination.PlaceId(id)
        }
        places.firstOrNull { it.name.trim().equals(text, ignoreCase = true) }?.let {
            return MapsDestination.Point(it.latitude, it.longitude, it.name)
        }
        return coordinates(text) ?: MapsDestination.Text(text)
    }

    /**
     * The Go confirmation a `talaria_maps` result sent, read from the tool's JSON answer (as text or
     * already parsed): only a navigate that was sent.
     */
    fun navigatePromptId(result: JsonElement?): String? {
        val answer = when (result) {
            is JsonObject -> result

            is JsonPrimitive -> result.contentOrNull?.let {
                runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject
            }

            else -> null
        } ?: return null
        fun text(key: String) = (answer[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text("action") != "navigate" || text("status") != "sent") return null
        return text("prompt_id")?.trim()?.takeIf(String::isNotEmpty)
    }

    /** A directions link from where the user is; null for what Google Maps can't be sent to. */
    fun link(destination: MapsDestination, mode: MapsMode): String? {
        val to = when (destination) {
            is MapsDestination.Point ->
                String.format(Locale.ROOT, "%.6f,%.6f", destination.latitude, destination.longitude)

            is MapsDestination.Text -> destination.text

            MapsDestination.Me, is MapsDestination.PlaceId -> return null
        }
        return "$LINK&destination=${encode(to)}&travelmode=${travelMode(mode)}"
    }

    /** The name to show for it. */
    fun label(destination: MapsDestination): String? = when (destination) {
        is MapsDestination.Point ->
            destination.name ?: String.format(Locale.ROOT, "%.4f, %.4f", destination.latitude, destination.longitude)

        is MapsDestination.Text -> destination.text

        MapsDestination.Me, is MapsDestination.PlaceId -> null
    }

    private fun travelMode(mode: MapsMode): String = when (mode) {
        MapsMode.Driving -> "driving"
        MapsMode.Walking -> "walking"
        MapsMode.Transit -> "transit"
        MapsMode.Cycling -> "bicycling"
    }

    private fun coordinates(text: String): MapsDestination.Point? {
        val parts = text.split(',').map(String::trim)
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        if (latitude !in -MAX_LATITUDE..MAX_LATITUDE || longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) return null
        return MapsDestination.Point(latitude, longitude)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
