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
import kotlinx.serialization.json.longOrNull

// Hermes 0.22.2 rows and payloads, read leniently: flags are booleans or 0/1, times float epoch
// seconds, and some objects arrive as JSON text on older servers.

internal val chatJson = Json { ignoreUnknownKeys = true }

internal val prettyJson = Json { prettyPrint = true }

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.contentOrNull

/** A string, or a number written as one (row ids are numbers on REST). */
internal fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it !is JsonNull }
    ?.contentOrNull
    ?.takeIf(String::isNotEmpty)

internal fun JsonObject.flag(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.booleanOrNull ?: value.longOrNull?.let { it != 0L }
}

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Float epoch seconds, as milliseconds. */
internal fun JsonObject.epochMs(key: String): Long? = double(key)?.let { (it * 1000).toLong() }

/** `display_metadata`: an object, or the same as JSON text from an older server. */
internal fun JsonObject.displayMetadata(): JsonObject? = when (val value = this["display_metadata"]) {
    is JsonObject -> value

    is JsonPrimitive -> value.contentOrNull?.let { runCatching { chatJson.parseToJsonElement(it) }.getOrNull() }
        as? JsonObject

    else -> null
}

/**
 * Message content as text: a string, or a list of parts whose text parts are joined (an image
 * part reads `[image]`, like Hermes' own text projection).
 */
internal fun JsonElement?.contentText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()

    is JsonArray -> joinToString("") { part ->
        when (part) {
            is JsonPrimitive -> part.contentOrNull.orEmpty()

            is JsonObject -> when (part.string("type")) {
                "text", "input_text", "output_text", null -> part.string("text").orEmpty()
                "image_url", "input_image", "image" -> "[image]"
                else -> "[${part.string("type")}]"
            }

            else -> ""
        }
    }

    else -> ""
}

/** JSON text parsed, or null when it is not JSON. */
internal fun String.parseJsonOrNull(): JsonElement? {
    val trimmed = trim()
    if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null
    return runCatching { chatJson.parseToJsonElement(trimmed) }.getOrNull()
}
