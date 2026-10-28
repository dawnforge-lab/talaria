package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import lab.dawnforge.hermes.core.model.CardFact
import lab.dawnforge.hermes.core.model.CardMedia
import lab.dawnforge.hermes.core.model.CardMediaKind
import lab.dawnforge.hermes.core.model.CardPerson
import lab.dawnforge.hermes.core.model.CardPhoto
import lab.dawnforge.hermes.core.model.CardPlace
import lab.dawnforge.hermes.core.model.CardRoute
import lab.dawnforge.hermes.core.model.CardSource
import lab.dawnforge.hermes.core.model.CardStat
import lab.dawnforge.hermes.core.model.CardStep
import lab.dawnforge.hermes.core.model.ChartCard
import lab.dawnforge.hermes.core.model.ChartKind
import lab.dawnforge.hermes.core.model.ChartSeries
import lab.dawnforge.hermes.core.model.CompareOption
import lab.dawnforge.hermes.core.model.ComparisonCard
import lab.dawnforge.hermes.core.model.ContactCard
import lab.dawnforge.hermes.core.model.MediaCard
import lab.dawnforge.hermes.core.model.Milestone
import lab.dawnforge.hermes.core.model.PlaceRating
import lab.dawnforge.hermes.core.model.PlacesCard
import lab.dawnforge.hermes.core.model.PlacesKind
import lab.dawnforge.hermes.core.model.RichCard
import lab.dawnforge.hermes.core.model.RouteMode
import lab.dawnforge.hermes.core.model.SourcedText
import lab.dawnforge.hermes.core.model.StatsCard
import lab.dawnforge.hermes.core.model.StatusCard
import lab.dawnforge.hermes.core.model.StatusPhase
import lab.dawnforge.hermes.core.model.StepsCard
import lab.dawnforge.hermes.core.model.Tone
import lab.dawnforge.hermes.core.model.Trend
import lab.dawnforge.hermes.core.model.WeatherCard
import lab.dawnforge.hermes.core.model.WeatherCondition
import lab.dawnforge.hermes.core.model.WeatherDay
import lab.dawnforge.hermes.core.model.WeatherHour
import lab.dawnforge.hermes.core.model.WeatherNow

/**
 * The push plugin's rich cards (`talaria_show`), checked against the schema both sides read
 * (`cards/card-schema.json`, copied from the plugin), exactly as the plugin's `card_schema.py` does:
 * the same shared vectors test both. The plugin normalizes before it stores them; the phone does it
 * again, so a card it draws is one the schema allows.
 */
object RichCards {
    /** The tool that shows cards. */
    const val TOOL = "talaria_show"

    private val definition: JsonObject by lazy {
        val text = RichCards::class.java.getResourceAsStream("/cards/card-schema.json")!!.use {
            it.readBytes().decodeToString()
        }
        Json.parseToJsonElement(text).jsonObject
    }
    private val cardSchema: JsonObject get() = definition["card"] as JsonObject
    private val maxCards: Int get() = (definition["max_cards"] as JsonPrimitive).int

    /** No spaces, quotes, brackets or parentheses: a URL may end up inside a Markdown link. */
    private val URI = Regex("^https?://[^\\s()<>\\[\\]{}\"'`\\\\]+$", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("^[0-9+\\-(). ]+$")
    private const val EMAIL_PART = "[^@\\s<>()\\[\\]\"'`,;:\\\\?#%&=]"
    private val EMAIL = Regex("^$EMAIL_PART+@$EMAIL_PART+\\.[^@\\s<>()\\[\\]\"'`,;:\\\\?#%&=.]+$")

    private val TYPE_FIELDS = mapOf(
        "map" to listOf("places", "route"),
        "place" to listOf("places"),
        "places" to listOf("places"),
        "stats" to listOf("stats"),
        "chart" to listOf("chart"),
        "weather" to listOf("weather"),
        "comparison" to listOf("options"),
        "steps" to listOf("steps", "needs"),
        "media" to listOf("media"),
        "contact" to listOf("people"),
        "status" to listOf("status"),
    )
    private val PLACE_TYPES = setOf("map", "place", "places")

    /** The cards a `talaria_show` call shows: from its result, else (when it went well) its arguments. */
    fun fromTool(arguments: JsonObject?, result: JsonElement?): List<RichCard> {
        val output = when (result) {
            is JsonObject -> result

            is JsonPrimitive -> result.contentOrNull?.let {
                runCatching { Json.parseToJsonElement(it) }.getOrNull()
            } as? JsonObject

            else -> null
        }
        val cards = output?.get("cards")
            ?: arguments?.get("cards")?.takeIf { (output?.get("ok") as? JsonPrimitive)?.booleanOrNull == true }
        return normalize(cards).mapNotNull(::parse)
    }

    /** The valid cards in [value], normalized; invalid ones are dropped. */
    fun normalize(value: JsonElement?): List<JsonObject> {
        val raws = value as? JsonArray ?: return emptyList()
        val cards = mutableListOf<JsonObject>()
        val live = mutableSetOf<String>()
        for (raw in raws) {
            if (cards.size == maxCards) break
            var card = normalizeCard(raw) ?: continue
            val name = (card["live"] as? JsonPrimitive)?.content
            if (name != null) {
                if (name in live) card = JsonObject(card - "live") else live += name
            }
            cards += card
        }
        return cards
    }

    private fun normalizeCard(raw: JsonElement): JsonObject? {
        val card = normalize(cardSchema, raw) as? JsonObject ?: return null
        return applyRules(card, raw as JsonObject)
    }

    /** One value against its schema; null when it is invalid. */
    private fun normalize(schema: JsonObject, value: JsonElement): JsonElement? =
        when ((schema["type"] as? JsonPrimitive)?.content) {
            "object" -> normalizeObject(schema, value)
            "array" -> normalizeArray(schema, value)
            "string" -> normalizeString(schema, value)
            "number", "integer" -> normalizeNumber(schema, value)
            "boolean" -> (value as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }
            else -> null
        }

    private fun normalizeObject(schema: JsonObject, value: JsonElement): JsonObject? {
        val raw = value as? JsonObject ?: return null
        val required = (schema["required"] as? JsonArray).orEmpty().mapTo(HashSet()) { (it as JsonPrimitive).content }
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, inner) in (schema["properties"] as? JsonObject).orEmpty()) {
            val given = raw[key]
            if (given == null || given == JsonNull) {
                if (key in required) return null
                continue
            }
            val clean = normalize(inner as JsonObject, given)
            if (clean == null) {
                if (key in required) return null
                continue
            }
            result[key] = clean
        }
        return JsonObject(result)
    }

    private fun normalizeArray(schema: JsonObject, value: JsonElement): JsonArray? {
        val raw = value as? JsonArray ?: return null
        val limit = schema.int("maxItems")
        val items = mutableListOf<JsonElement>()
        for (element in raw) {
            if (limit != null && items.size == limit) break
            normalize(schema["items"] as JsonObject, element)?.let(items::add)
        }
        if (items.size < (schema.int("minItems") ?: 0)) return null
        return JsonArray(items)
    }

    private fun normalizeString(schema: JsonObject, value: JsonElement): JsonPrimitive? {
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        var text = cleanText(raw)
        if (text.isEmpty()) return null
        val limit = schema.int("maxLength")
        if ((schema["format"] as? JsonPrimitive)?.content == "uri") {
            if (!URI.matches(text) || (limit != null && text.codePointCount(0, text.length) > limit)) return null
            return JsonPrimitive(text)
        }
        if (limit != null && text.codePointCount(0, text.length) > limit) {
            text = text.substring(0, text.offsetByCodePoints(0, limit)).trimEnd()
        }
        val options = schema["enum"] as? JsonArray ?: return JsonPrimitive(text)
        return options.map {
            (it as JsonPrimitive).content
        }.firstOrNull { it.equals(text, ignoreCase = true) }?.let(::JsonPrimitive)
    }

    private fun normalizeNumber(schema: JsonObject, value: JsonElement): JsonPrimitive? {
        val raw = value as? JsonPrimitive ?: return null
        if (raw.isString || raw.booleanOrNull != null) return null
        val number = raw.doubleOrNull ?: return null
        if (!number.isFinite()) return null
        var result = raw
        if ((schema["type"] as JsonPrimitive).content == "integer") {
            if (number % 1.0 != 0.0) return null
            result = JsonPrimitive(number.toLong())
        }
        schema.double("minimum")?.let { if (number < it) return null }
        schema.double("maximum")?.let { if (number > it) return null }
        return result
    }

    /** Control characters become spaces; trimmed. */
    internal fun cleanText(text: String): String =
        text.map { if (Character.getType(it) == Character.CONTROL.toInt()) ' ' else it }.joinToString("").trim()

    private fun applyRules(normalized: JsonObject, raw: JsonObject): JsonObject? {
        val kind = (normalized["type"] as? JsonPrimitive)?.content ?: return null
        val fields = TYPE_FIELDS[kind] ?: return null
        val keep = setOf("type", "title", "live") + fields + if (kind in PLACE_TYPES) emptySet() else setOf("source")
        val card = normalized.filterKeys { it in keep }.toMutableMap()
        if (fields.first() !in card) return null
        if (kind in PLACE_TYPES) return placeRules(card, raw)
        when (kind) {
            "chart" -> card["chart"] = chartRules(card["chart"] as JsonObject) ?: return null

            "weather" -> {
                val weather = (card["weather"] as JsonObject).toMutableMap()
                (weather["days"] as? JsonArray)?.let { days ->
                    weather["days"] =
                        JsonArray(days.filter { day -> (day as JsonObject).double("low")!! <= day.double("high")!! })
                }
                listOf("hours", "days").forEach { key ->
                    if ((weather[key] as? JsonArray)?.isEmpty() ==
                        true
                    ) {
                        weather.remove(key)
                    }
                }
                if ("source" !in card || listOf("now", "hours", "days").none { it in weather }) return null
                card["weather"] = JsonObject(weather)
            }

            "comparison" -> {
                var seen = false
                card["options"] = JsonArray(
                    (card["options"] as JsonArray).map { option ->
                        val fields2 = (option as JsonObject).toMutableMap()
                        if ((fields2["recommended"] as? JsonPrimitive)?.booleanOrNull == true) {
                            if (seen) fields2.remove("recommended")
                            seen = true
                        }
                        JsonObject(fields2)
                    },
                )
            }

            "media" -> {
                if ("source" !in card) return null
                val media = (card["media"] as JsonArray).map { it as JsonObject }.filter { isHttps(it.text("url")!!) }
                if (media.isEmpty()) return null
                card["media"] = JsonArray(
                    media.map { item ->
                        val fields2 = item.toMutableMap()
                        val image = item.text("kind") == "image"
                        if (image || !isHttps(item.text("artwork") ?: "https://")) fields2.remove("artwork")
                        if (image) fields2.remove("duration")
                        JsonObject(fields2)
                    },
                )
            }

            "contact" -> card["people"] = JsonArray(
                (card["people"] as JsonArray).map { person ->
                    val fields2 = (person as JsonObject).toMutableMap()
                    person.text("phone")?.let { if (!phoneOk(it)) fields2.remove("phone") }
                    person.text("email")?.let { if (!EMAIL.matches(it)) fields2.remove("email") }
                    (person["photo"] as? JsonObject)?.text("url")?.let { if (!isHttps(it)) fields2.remove("photo") }
                    JsonObject(fields2)
                },
            )
        }
        return JsonObject(card)
    }

    private fun placeRules(card: MutableMap<String, JsonElement>, raw: JsonObject): JsonObject {
        val places = card["places"] as JsonArray
        val kept = if ((card["type"] as JsonPrimitive).content == "place") places.take(1) else places
        card["places"] = JsonArray(
            kept.map { place ->
                val fields = (place as JsonObject).toMutableMap()
                (place["photo"] as? JsonObject)?.text("url")?.let { if (!isHttps(it)) fields.remove("photo") }
                JsonObject(fields)
            },
        )
        val route = card.remove("route") as? JsonObject
        if (route != null) {
            val given = raw["places"] as? JsonArray
            val survived = given != null && given.size == places.size
            val to = route.int("to")!!
            val start = route.int("from")
            if (survived && to < places.size &&
                (start == null || (start < places.size && start != to))
            ) {
                card["route"] = route
            }
        }
        return JsonObject(card)
    }

    private fun chartRules(chart: JsonObject): JsonObject? {
        val count = (chart["labels"] as JsonArray).size
        var series = (chart["series"] as JsonArray).filter { ((it as JsonObject)["values"] as JsonArray).size == count }
        if (chart.text("kind") == "pie") {
            series = series.take(1)
            val negative = series.firstOrNull()?.let { line ->
                ((line as JsonObject)["values"] as JsonArray).any { (it as JsonPrimitive).double < 0 }
            } == true
            if (negative) series = emptyList()
        }
        if (series.isEmpty()) return null
        return JsonObject(chart + ("series" to JsonArray(series)))
    }

    private fun isHttps(url: String): Boolean = url.take(HTTPS.length).lowercase() == HTTPS

    private fun phoneOk(phone: String): Boolean =
        PHONE.matches(phone) && phone.count(Char::isDigit) in MIN_DIGITS..MAX_DIGITS

    // ---- Into the app's cards ----------------------------------------------------------------

    /** A normalized card as the app draws it. */
    fun parse(card: JsonObject): RichCard? {
        val title = card.text("title")
        val live = card.text("live")
        val source = (card["source"] as? JsonObject)?.let {
            CardSource(it.text("name") ?: return@let null, it.text("url"))
        }
        return when (card.text("type")) {
            "map", "place", "places" -> PlacesCard(
                title = title,
                live = live,
                kind = when (card.text("type")) {
                    "map" -> PlacesKind.Map
                    "place" -> PlacesKind.Place
                    else -> PlacesKind.Places
                },
                places = card.objects("places").map(::place),
                route = (card["route"] as? JsonObject)?.let { route ->
                    CardRoute(route.int("to")!!, route.int("from"), enumOf(route.text("mode"), RouteMode.Driving))
                },
            )

            "stats" -> StatsCard(title, live, source, card.objects("stats").map(::stat))

            "chart" -> (card["chart"] as JsonObject).let { chart ->
                ChartCard(
                    title = title,
                    live = live,
                    source = source,
                    kind = enumOf(chart.text("kind"), ChartKind.Line),
                    labels = chart.strings("labels"),
                    series = chart.objects("series").map { ChartSeries(it.text("name"), it.numbers("values")) },
                    unit = chart.text("unit"),
                )
            }

            "weather" -> weather(card["weather"] as JsonObject, title, live, source ?: return null)

            "comparison" -> ComparisonCard(title, live, source, card.objects("options").map(::option))

            "steps" -> StepsCard(
                title = title,
                live = live,
                source = source,
                needs = card.strings("needs"),
                steps = card.objects("steps").map { CardStep(it.text("text")!!, it.int("timer")) },
            )

            "media" -> MediaCard(title, live, source ?: return null, card.objects("media").map(::media))

            "contact" -> ContactCard(title, live, source, card.objects("people").map(::person))

            "status" -> (card["status"] as JsonObject).let { status ->
                StatusCard(
                    title = title,
                    live = live,
                    source = source,
                    state = status.text("state")!!,
                    phase = enumOf(status.text("phase"), StatusPhase.Active),
                    progress = status.double("progress"),
                    detail = status.text("detail"),
                    eta = status.text("eta"),
                    milestones = status.objects("milestones").map {
                        Milestone(it.text("label")!!, (it["done"] as? JsonPrimitive)?.booleanOrNull == true)
                    },
                )
            }

            else -> null
        }
    }

    private fun place(json: JsonObject) = CardPlace(
        name = json.text("name")!!,
        address = json.text("address"),
        lat = json.double("lat"),
        lon = json.double("lon"),
        placeId = json.text("place_id"),
        note = json.text("note"),
        rating = (json["rating"] as? JsonObject)?.let {
            PlaceRating(it.double("value")!!, it.long("count"), it.text("source")!!, it.text("url"))
        },
        hours = (json["hours"] as? JsonObject)?.let(::sourced),
        price = (json["price"] as? JsonObject)?.let(::sourced),
        photo = (json["photo"] as? JsonObject)?.let(::photo),
    )

    private fun sourced(json: JsonObject) = SourcedText(json.text("text")!!, json.text("source")!!, json.text("url"))

    private fun photo(json: JsonObject) = CardPhoto(json.text("url")!!, json.text("source")!!, json.text("page_url"))

    private fun stat(json: JsonObject) = CardStat(
        label = json.text("label")!!,
        value = json.text("value")!!,
        detail = json.text("detail"),
        change = json.text("change"),
        trend = json.text("trend")?.let { enumOf(it, Trend.Flat) },
        tone = enumOf(json.text("tone"), Tone.Neutral),
        spark = json.numbers("spark"),
    )

    private fun weather(json: JsonObject, title: String?, live: String?, source: CardSource) = WeatherCard(
        title = title,
        live = live,
        source = source,
        unit = json.text("unit")!!,
        place = json.text("place"),
        now = (json["now"] as? JsonObject)?.let {
            WeatherNow(it.double("temp")!!, condition(it.text("condition"))!!, it.text("text"))
        },
        hours = json.objects("hours").map {
            WeatherHour(it.text("time")!!, it.double("temp")!!, condition(it.text("condition")), it.int("precip"))
        },
        days = json.objects("days").map {
            WeatherDay(
                it.text("day")!!,
                it.double("low")!!,
                it.double("high")!!,
                condition(it.text("condition")),
                it.int("precip"),
            )
        },
    )

    private fun condition(name: String?): WeatherCondition? = name?.let { key ->
        WeatherCondition.entries.firstOrNull { it.name.equals(key.replace("_", ""), ignoreCase = true) }
    }

    private fun option(json: JsonObject) = CompareOption(
        name = json.text("name")!!,
        summary = json.text("summary"),
        facts = json.objects("facts").map { CardFact(it.text("label")!!, it.text("value")!!) },
        pros = json.strings("pros"),
        cons = json.strings("cons"),
        recommended = (json["recommended"] as? JsonPrimitive)?.booleanOrNull == true,
    )

    private fun media(json: JsonObject) = CardMedia(
        kind = enumOf(json.text("kind"), CardMediaKind.Image),
        url = json.text("url")!!,
        title = json.text("title"),
        by = json.text("by"),
        caption = json.text("caption"),
        durationSeconds = json.int("duration"),
        artwork = json.text("artwork"),
        page = json.text("page"),
    )

    private fun person(json: JsonObject) = CardPerson(
        name = json.text("name")!!,
        detail = json.text("detail"),
        phone = json.text("phone"),
        email = json.text("email"),
        url = json.text("url"),
        address = json.text("address"),
        note = json.text("note"),
        photo = (json["photo"] as? JsonObject)?.let(::photo),
    )

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.takeIf {
        !it.isString
    }?.doubleOrNull

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

    private fun JsonObject.strings(key: String): List<String> =
        (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.numbers(key: String): List<Double> =
        (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }

    private val JsonPrimitive.int: Int get() = content.toInt()
    private val JsonPrimitive.double: Double get() = content.toDouble()

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private const val HTTPS = "https://"
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 20
}
