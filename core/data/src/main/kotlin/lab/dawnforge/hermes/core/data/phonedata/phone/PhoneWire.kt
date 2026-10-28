package lab.dawnforge.hermes.core.data.phonedata.phone

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/** How the plugin's phone data writes times and ids, as iOS does (`PhoneDates`, `EventKitPhoneSource.hashed`). */
object PhoneWire {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    /** An instant as ISO 8601 with seconds and the zone's offset (`Z` for UTC). */
    fun format(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /**
     * An id the host sees instead of the phone's own: [prefix] and 22 characters of the
     * SHA-256 of [parts] (joined by U+001F), base64url, so the phone's ids never leave it.
     */
    fun hashed(prefix: String, vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u001f").toByteArray())
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(HASH_CHARS)
    }

    private const val HASH_CHARS = 22
}
