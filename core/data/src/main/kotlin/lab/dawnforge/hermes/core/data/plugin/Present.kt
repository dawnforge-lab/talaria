package lab.dawnforge.hermes.core.data.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** A confirmation Hermes waits on (`talaria_present confirm`): the user's OK before it acts for them. */
data class ConfirmPrompt(
    val id: String,
    val title: String,
    /** What will happen, as Markdown. */
    val preview: String,
    /** The primary action's label ("Send"); null shows the app's own. */
    val actionLabel: String?,
    /** Short phrases that confirm it out loud, for a voice call. */
    val spokenYes: List<String>,
    /** The chat it came from, if any. */
    val sessionId: String?,
    /** When the host stops waiting, in seconds since the epoch; null when it didn't say. */
    val expiresAt: Double?,
    /** Directions to open in Maps when the user says go (`talaria_maps navigate`); null for any other. */
    val maps: MapsTarget? = null,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAt != null && expiresAt * MILLIS <= nowMillis

    private companion object {
        const val MILLIS = 1000
    }
}

/** How to get there, as `talaria_maps` names it. */
enum class MapsMode(val key: String) {
    Driving("driving"),
    Walking("walking"),
    Transit("transit"),
    Cycling("cycling"),
}

/**
 * Where a Go confirmation from `talaria_maps navigate` takes the user, from wherever they are: a
 * saved place's name, an address or a name, "place_id:<id>" or "lat,lon".
 */
data class MapsTarget(val to: String, val mode: MapsMode = MapsMode.Driving)

/** How the user answered a confirmation. Only [Confirmed] lets Hermes go ahead. */
enum class ConfirmOutcome(val key: String) {
    Confirmed("confirmed"),
    Declined("declined"),

    /** The user wants changes and sends the corrected request themselves. */
    Edit("edit"),
}

/**
 * Something Hermes put on the screen of a voice conversation (`talaria_show` on a voice turn): a
 * table, steps, a chart, as Markdown, and cards drawn natively. Shown once; never answered.
 */
data class ScreenCard(
    val id: String,
    val title: String,
    val markdown: String,
    /** The cards' own JSON, as the plugin normalized them; drawn like a chat's. */
    val cards: kotlinx.serialization.json.JsonArray?,
)

/** What one exchange brought: the confirmations still waiting and any screen cards. */
data class PresentReply(val prompts: List<ConfirmPrompt>, val cards: List<ScreenCard>)

/** An answer the host hasn't heard yet. */
data class ConfirmAnswer(val id: String, val outcome: ConfirmOutcome, val via: AnswerVia = AnswerVia.Tap)

/** How the user answered: a tap, or out loud in a voice conversation. */
enum class AnswerVia(val key: String) {
    Tap("tap"),
    Voice("voice"),

    /** The app opened Maps for a Go confirmation itself: Hermes asked in the chat on screen. */
    Auto("auto"),
}

/** One `present/exchange` call: this phone, and the answers it reports. */
data class PresentExchange(
    /** Names this phone to the host; it doesn't authenticate it. */
    val installationId: String,
    val device: String,
    val appVersion: String,
    val answers: List<ConfirmAnswer>,
)

/** The plugin's `present/exchange` route, as the app reads and writes it. */
object PresentJson {
    /**
     * What this app draws, told to the host in each exchange. "chat-cards": `talaria_show`'s map and
     * place cards in a chat, so the host offers the tool there; "info-cards": stats, charts,
     * forecasts, comparisons and steps; "media-cards": photos, videos, audio and contacts;
     * "live-cards": statuses and cards kept up to date under a live name; "show-cards": cards on
     * a voice conversation's screen; "maps-navigate": Go confirmations that open Maps with directions.
     */
    val FEATURES = listOf("maps-navigate", "show-cards", "chat-cards", "info-cards", "media-cards", "live-cards")

    fun body(exchange: PresentExchange): JsonObject = buildJsonObject {
        put("installation_id", exchange.installationId)
        put("device", exchange.device)
        put("app_version", exchange.appVersion)
        put("features", JsonArray(FEATURES.map(::JsonPrimitive)))
        put(
            "results",
            JsonArray(
                exchange.answers.map { answer ->
                    buildJsonObject {
                        put("id", answer.id)
                        put("outcome", answer.outcome.key)
                        put("via", answer.via.key)
                    }
                },
            ),
        )
    }

    /** The prompts still waiting, oldest first; null when the answer isn't the route's. */
    fun prompts(json: JsonElement): List<ConfirmPrompt>? = reply(json)?.prompts

    /** The prompts still waiting and the screen cards; null when the answer isn't the route's. */
    fun reply(json: JsonElement): PresentReply? {
        val reply = json as? JsonObject ?: return null
        if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val prompts = (reply["prompts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::prompt) }
        val cards = (reply["cards"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::card) }
        return PresentReply(prompts, cards)
    }

    private fun card(entry: JsonObject): ScreenCard? {
        val id = entry.text("id") ?: return null
        val cards = (entry["cards"] as? JsonArray)?.takeIf { it.isNotEmpty() }
        // Markdown that is only the cards' own text would show them twice beside the drawn cards.
        val fallback = cards != null && (entry["fallback"] as? JsonPrimitive)?.booleanOrNull == true
        val markdown = entry.text("markdown")?.trim().orEmpty()
        if (markdown.isEmpty() && cards == null) return null
        return ScreenCard(id, entry.text("title")?.trim().orEmpty(), if (fallback) "" else markdown, cards)
    }

    private fun prompt(entry: JsonObject): ConfirmPrompt? {
        val id = entry.text("id") ?: return null
        val title = entry.text("title")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return ConfirmPrompt(
            id = id,
            title = title.take(MAX_TITLE),
            preview = entry.text("preview").orEmpty().take(MAX_PREVIEW),
            actionLabel = entry.text("action_label")?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_LABEL),
            spokenYes = (entry["spoken_yes"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim() }
                .filter(String::isNotEmpty)
                .take(MAX_SPOKEN)
                .map { it.take(MAX_SPOKEN_CHARS) },
            sessionId = entry.text("session_id"),
            expiresAt = (entry["expires_at"] as? JsonPrimitive)?.doubleOrNull,
            maps = (entry["maps"] as? JsonObject)?.let(::mapsTarget),
        )
    }

    /** A navigate prompt's target; a mode this app doesn't know drops it, as iOS. */
    private fun mapsTarget(entry: JsonObject): MapsTarget? {
        val to = entry.text("to")?.let(MapsDirections::clean)?.takeIf(String::isNotEmpty) ?: return null
        val name = entry.text("mode") ?: return MapsTarget(to)
        val mode = MapsMode.entries.firstOrNull { it.key == name.lowercase() } ?: return null
        return MapsTarget(to, mode)
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

    private const val MAX_TITLE = 120
    private const val MAX_PREVIEW = 4000
    private const val MAX_LABEL = 32
    private const val MAX_SPOKEN = 5
    private const val MAX_SPOKEN_CHARS = 40
}
