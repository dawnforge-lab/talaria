package lab.dawnforge.hermes.core.data.chat

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** A file from the Hermes host: its bytes and media type. */
class MediaFile(val bytes: ByteArray, val mimeType: String)

/**
 * Files a chat shows from the Hermes host: pictures the user sent, and what the agent names with a
 * `MEDIA:` tag (images, audio, documents).
 */
class MediaRepository(private val requests: DashboardRequests) {
    /**
     * The file at [path] on the host (`/api/fs/read-data-url`, up to 16 MiB). Hermes refuses paths
     * outside what it manages (403) and files too large (413).
     */
    suspend fun file(id: DashboardId, profile: String, path: String): NetResult<MediaFile> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, "/api/fs/read-data-url", query = mapOf("path" to path, "profile" to profile))
        }.decoded { (it as? JsonObject)?.text("dataUrl") }

    /**
     * The [index]th picture stored in the prompt row [rowId] of [sessionId], for when the file it was
     * sent from is gone (Hermes clears its media cache after about a day). The row comes back with its
     * pictures inline.
     */
    suspend fun promptImage(
        id: DashboardId,
        profile: String,
        sessionId: String,
        rowId: Long,
        index: Int,
    ): NetResult<MediaFile> = requests.run(id) { url, jar, cloudflare ->
        get(
            url,
            jar,
            cloudflare,
            "/api/sessions",
            listOf(sessionId, "messages", "around"),
            mapOf("row_id" to rowId.toString(), "limit" to "1", "profile" to profile),
        )
    }.decoded { body ->
        val row = ((body as? JsonObject)?.get("messages") as? JsonArray)?.firstOrNull() as? JsonObject
        imageUrls(row?.get("content") as? JsonArray).getOrNull(index)
    }

    /** The data URLs of a row's picture parts, in order, whichever shape each part has. */
    private fun imageUrls(parts: JsonArray?): List<String> = parts.orEmpty().mapNotNull { element ->
        val part = element as? JsonObject ?: return@mapNotNull null
        when (part.text("type")) {
            "image_url", "input_image", "image" -> {
                val nested = part["image_url"]
                (nested as? JsonObject)?.text("url") ?: (nested as? JsonPrimitive)?.contentOrNull ?: part.text("url")
                    ?: (part["source"] as? JsonObject)?.let { source ->
                        val data = source.text("data") ?: return@let null
                        "data:${source.text("media_type") ?: "image/png"};base64,$data"
                    }
                    ?: part.text("data")?.let { "data:${part.text("mimeType") ?: "image/png"};base64,$it" }
            }

            else -> null
        }
    }

    private inline fun NetResult<kotlinx.serialization.json.JsonElement>.decoded(
        dataUrl: (kotlinx.serialization.json.JsonElement) -> String?,
    ): NetResult<MediaFile> = when (this) {
        is NetResult.Failure -> this

        is NetResult.Success -> dataUrl(value)?.let(::decode)?.let { NetResult.Success(it) }
            ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "No file in the reply"))
    }

    private fun decode(dataUrl: String): MediaFile? {
        if (!dataUrl.startsWith("data:")) return null
        val comma = dataUrl.indexOf(',').takeIf { it > 0 } ?: return null
        val header = dataUrl.substring(DATA_PREFIX, comma)
        val mime = header.substringBefore(';').ifEmpty { "application/octet-stream" }
        val payload = dataUrl.substring(comma + 1)
        val bytes = if (header.endsWith(";base64")) {
            runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: return null
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
        }
        return MediaFile(bytes, mime)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

    private companion object {
        const val DATA_PREFIX = 5
    }
}
