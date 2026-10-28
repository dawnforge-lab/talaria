package lab.dawnforge.hermes.core.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** A link's query as names and values, or null when it can't be read (a bad escape, a repeated name). */
internal fun queryParams(rawQuery: String): Map<String, String>? {
    val params = mutableMapOf<String, String>()
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val name = percentDecode(pair.substringBefore('=')) ?: return null
        val value = percentDecode(pair.substringAfter('=', missingDelimiterValue = "")) ?: return null
        // A repeated parameter makes the link ambiguous, so it is unreadable.
        if (params.put(name, value) != null) return null
    }
    return params
}

/** Decodes %XX escapes as UTF-8. Unlike form decoding, "+" stays a plus sign. */
internal fun percentDecode(value: String): String? {
    if (!value.contains('%')) return value
    val bytes = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            if (i + 2 >= value.length) return null
            val byte = value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            bytes.write(byte)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * A link to one chat, as iOS writes them and other apps may open:
 * `talaria://session/<id>?profile=<name>&dashboard=<id>`. Each value is up to 128 of
 * `[A-Za-z0-9_.:-]`; the profile and dashboard are optional.
 */
data class SessionLink(val sessionId: String, val profile: String?, val dashboard: String?) {
    companion object {
        private val VALUE = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        /** Returns the link, or null when [raw] isn't a readable session link. */
        fun parse(raw: String): SessionLink? {
            val text = raw.trim()
            val prefix = "talaria://session/"
            if (!text.startsWith(prefix, ignoreCase = true) || '#' in text) return null
            val rest = text.substring(prefix.length)
            val id = percentDecode(rest.substringBefore('?').removeSuffix("/")) ?: return null
            if (!VALUE.matches(id)) return null
            val params = queryParams(rest.substringAfter('?', missingDelimiterValue = "")) ?: return null
            if (params.keys.any { it != "profile" && it != "dashboard" }) return null
            val profile = params["profile"]?.takeIf(String::isNotEmpty)
            val dashboard = params["dashboard"]?.takeIf(String::isNotEmpty)
            if (listOfNotNull(profile, dashboard).any { !VALUE.matches(it) }) return null
            return SessionLink(id, profile, dashboard)
        }
    }
}
