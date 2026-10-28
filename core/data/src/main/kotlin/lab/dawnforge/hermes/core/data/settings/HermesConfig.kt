package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** A value in a profile's config (`GET /api/config`), as a settings page shows it. */
sealed interface ConfigValue {
    data class Flag(val on: Boolean) : ConfigValue

    data class Text(val text: String) : ConfigValue

    data class Number(val number: Double) : ConfigValue

    data class Items(val items: List<String>) : ConfigValue
}

/** The value at a dotted [path] (`display.show_reasoning`), or null when it's missing or not one of the four kinds. */
fun JsonObject.valueAt(path: String): ConfigValue? = when (val element = elementAt(path)) {
    is JsonPrimitive -> when {
        element is JsonNull -> null

        element.isString -> ConfigValue.Text(element.content)

        // A JSON number 1 is not a flag: only true and false are.
        element.booleanOrNull != null -> ConfigValue.Flag(element.booleanOrNull!!)

        else -> element.doubleOrNull?.let(ConfigValue::Number)
    }

    is JsonArray -> ConfigValue.Items(
        element.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: it.toString() },
    )

    else -> null
}

/** The element at a dotted [path], or null. */
fun JsonObject.elementAt(path: String): JsonElement? =
    path.split('.').fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

/** [value] as Hermes stores it: whole numbers as integers, so `300` stays `300`. */
fun ConfigValue.toJson(): JsonElement = when (this) {
    is ConfigValue.Flag -> JsonPrimitive(on)
    is ConfigValue.Text -> JsonPrimitive(text)
    is ConfigValue.Number -> if (isWhole(number)) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
    is ConfigValue.Items -> JsonArray(items.map(::JsonPrimitive))
}

/**
 * The partial config `PUT /api/config` takes to set [path] alone: `{"a": {"b": value}}`. Hermes
 * deep-merges it into the profile's file, replacing lists whole and leaving everything else.
 */
fun configPatch(path: String, value: JsonElement): JsonObject =
    path.split('.').reversed().fold(value) { inner, key -> JsonObject(mapOf(key to inner)) } as JsonObject

/** One partial config setting every key of [values] (dotted paths), for a single save. */
fun configPatch(values: Map<String, JsonElement>): JsonObject =
    values.entries.fold(JsonObject(emptyMap())) { patch, (path, value) -> merge(patch, configPatch(path, value)) }

private fun merge(base: JsonObject, extra: JsonObject): JsonObject = JsonObject(
    base + extra.mapValues { (key, value) ->
        val existing = base[key]
        if (existing is JsonObject && value is JsonObject) merge(existing, value) else value
    },
)

/** A number as typed: trimmed, with a decimal comma read as a point. Null when it isn't one. */
fun parseNumber(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** A number for an edit field: `300`, not `300.0`. */
fun formatNumber(number: Double): String = if (isWhole(number)) number.toLong().toString() else number.toString()

private fun isWhole(number: Double) = number == Math.floor(number) && kotlin.math.abs(number) < 1e15

/** One line per entry, trimmed, blank lines dropped. */
fun parseLines(text: String): List<String> = text.lines().map(String::trim).filter(String::isNotEmpty)

/**
 * What `/api/config/schema` says about this Hermes' config: which keys it has, and the choices of
 * the keys that have a fixed list. Empty when the schema couldn't be read.
 */
data class ConfigSchema(val fields: Map<String, Field> = emptyMap()) {
    data class Field(val type: String?, val options: List<String>)

    val loaded: Boolean get() = fields.isNotEmpty()

    /** True when this Hermes has [key], or when that isn't known (no schema). */
    fun has(key: String): Boolean = !loaded || key in fields

    fun options(key: String): List<String> = fields[key]?.options.orEmpty()

    companion object {
        fun parse(json: JsonElement): ConfigSchema {
            val fields = ((json as? JsonObject)?.get("fields") as? JsonObject).orEmpty()
            return ConfigSchema(
                fields.mapNotNull { (key, entry) ->
                    val field = entry as? JsonObject ?: return@mapNotNull null
                    val options = (field["options"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    }
                    key to Field((field["type"] as? JsonPrimitive)?.content, options)
                }.toMap(),
            )
        }
    }
}

/** A model Hermes falls back to when the main one fails (`fallback_providers`). */
data class FallbackModel(
    val provider: String,
    val model: String,
    /** Settings of the entry the phone doesn't edit (`base_url`, `key_env`, ...), kept as they are. */
    val extras: JsonObject = JsonObject(emptyMap()),
) {
    val complete: Boolean get() = provider.isNotBlank() && model.isNotBlank()

    fun toJson(): JsonObject = buildJsonObject {
        extras.forEach { (key, value) -> if (key != "provider" && key != "model") put(key, value) }
        put("provider", provider)
        put("model", model)
    }

    companion object {
        /** The config's list: objects, or the older `"provider/model"` strings. */
        fun list(config: JsonObject): List<FallbackModel> =
            (config["fallback_providers"] as? JsonArray).orEmpty().mapNotNull { entry ->
                when {
                    entry is JsonObject -> FallbackModel(
                        provider = entry.string("provider").orEmpty(),
                        model = entry.string("model").orEmpty(),
                        extras = JsonObject(entry - "provider" - "model"),
                    )

                    entry is JsonPrimitive && entry.isString -> entry.content.split('/', limit = 2)
                        .takeIf { it.size == 2 }
                        ?.let { (provider, model) -> FallbackModel(provider.trim(), model.trim()) }

                    else -> null
                }
            }
    }
}

/**
 * The profile's default reasoning, as Hermes reads it: `agent.reasoning_effort` (or the older
 * top-level keys). `false`, "off" and "disabled" are no thinking; blank is Hermes' default, medium.
 */
fun defaultReasoning(config: JsonObject): String {
    val raw = listOf("agent.reasoning_effort", "reasoning", "reasoning_effort")
        .firstNotNullOfOrNull { path -> config.elementAt(path)?.takeUnless { it is JsonNull } }
    val level = when (raw) {
        is JsonObject -> if ((raw["enabled"] as? JsonPrimitive)?.booleanOrNull ==
            false
        ) {
            "none"
        } else {
            raw.string("effort")
        }

        is JsonPrimitive -> if (raw.booleanOrNull == false) "none" else raw.content.takeIf { raw.isString }

        else -> null
    }?.trim()?.lowercase()
    return when {
        level == null || level.isEmpty() -> DEFAULT_REASONING

        level == "off" || level == "disabled" || level == "false" -> "none"

        // A level the phone doesn't list (a relay's own tier) is shown as it is.
        else -> level
    }
}

/** What Hermes uses when no reasoning is set. */
const val DEFAULT_REASONING = "medium"

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
