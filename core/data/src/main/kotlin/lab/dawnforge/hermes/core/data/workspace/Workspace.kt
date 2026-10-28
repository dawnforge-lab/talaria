package lab.dawnforge.hermes.core.data.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A file or folder in a chat's workspace on the host (`/api/fs/list`). */
data class WorkspaceEntry(val name: String, val path: String, val isDirectory: Boolean)

/** The start of a text file as Hermes reads it (`/api/fs/read-text`, at most 512 KiB). */
data class FilePreview(
    /** Hermes judged the file not to be text: nothing is shown, it can be opened or shared. */
    val binary: Boolean,
    val byteSize: Long,
    val language: String,
    val mimeType: String,
    val text: String,
    /** The file is longer than [text]. */
    val truncated: Boolean,
)

/** What a chat produced: a file it sent, wrote or drew, a file the user attached, or a link. */
enum class ArtifactKind(val key: String) {
    Image("image"),
    Video("video"),
    Audio("audio"),
    Page("page"),
    Document("document"),
    File("file"),
    Link("link"),
    ;

    /** Pictures and videos go in a grid; the rest in rows. */
    val isVisual: Boolean get() = this == Image || this == Video

    companion object {
        fun of(key: String?): ArtifactKind? = entries.firstOrNull { it.key == key }
    }
}

/** How an artifact came about. */
enum class ArtifactSource(val key: String) {
    /** A file Hermes sent (`MEDIA:`). */
    Delivered("delivered"),

    /** A page Hermes drew in place (`::preview{file=…}`). */
    Preview("preview"),

    /** A file one of Hermes' tools wrote. */
    Written("written"),

    /** A file the user attached. */
    Attached("attached"),
    Link("link"),
    ;

    companion object {
        fun of(key: String?): ArtifactSource = entries.firstOrNull { it.key == key } ?: Delivered
    }
}

data class Artifact(
    val kind: ArtifactKind,
    val source: ArtifactSource,
    val name: String,
    /** The file on the host; null for a link. */
    val path: String?,
    /** A web link (http or https only); null for a file. */
    val url: String?,
    val host: String?,
    /** Whether the file is still on the host; always true for a link. */
    val exists: Boolean,
    val size: Long?,
    val sessionId: String,
    val sessionTitle: String?,
    /** The message that mentions it, to show it in its chat. */
    val messageId: Long,
    /** Epoch seconds. */
    val timestamp: Long,
) {
    /** The same file or link mentioned twice is one artifact. */
    val id: String get() = path ?: url ?: "$messageId|$name"
}

/** The list's filter chips, as the plugin's `kind` parameter takes them. */
enum class ArtifactFilter(val key: String?, private val kinds: Set<ArtifactKind>) {
    All(null, ArtifactKind.entries.toSet()),
    Images("images", setOf(ArtifactKind.Image, ArtifactKind.Video)),
    Pages("pages", setOf(ArtifactKind.Page)),
    Files("files", setOf(ArtifactKind.Audio, ArtifactKind.Document, ArtifactKind.File)),
    Links("links", setOf(ArtifactKind.Link)),
    ;

    fun includes(kind: ArtifactKind): Boolean = kind in kinds
}

/** A page of artifacts, newest first; [nextBefore] asks for the next one. */
data class ArtifactPage(val items: List<Artifact>, val nextBefore: Long?)

object WorkspaceJson {
    /** Folders first, then by name, as iOS sorts them; entries without a name or path are dropped. */
    fun entries(json: JsonElement): List<WorkspaceEntry> {
        val reply = json as? JsonObject ?: return emptyList()
        return (reply["entries"] as? JsonArray).orEmpty().mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            val name = entry.text("name") ?: return@mapNotNull null
            val path = entry.text("path") ?: return@mapNotNull null
            WorkspaceEntry(name, path, entry.flag("isDirectory") == true)
        }.sortedWith(compareBy<WorkspaceEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** The error `/api/fs/list` reports with HTTP 200 (`ENOENT`, `EACCES`, …); null when there is none. */
    fun listError(json: JsonElement): String? = (json as? JsonObject)?.text("error")

    fun preview(json: JsonElement): FilePreview {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        return FilePreview(
            binary = reply.flag("binary") == true,
            byteSize = reply.long("byteSize") ?: 0,
            language = reply.text("language") ?: "text",
            mimeType = reply.text("mimeType") ?: "text/plain",
            text = (reply["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            truncated = reply.flag("truncated") == true,
        )
    }

    /** A page of artifacts; null when the reply isn't one (`ok` missing or false, no `items`). */
    fun artifacts(json: JsonElement): ArtifactPage? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        val items = reply["items"] as? JsonArray ?: return null
        return ArtifactPage(items.mapNotNull { (it as? JsonObject)?.let(::artifact) }, reply.long("next_before"))
    }

    fun artifact(json: JsonObject): Artifact? {
        val kind = ArtifactKind.of(json.text("kind")) ?: return null
        val sessionId = json.text("session_id") ?: return null
        val messageId = json.long("message_id") ?: return null
        val timestamp = json.long("timestamp") ?: return null
        val path = json.text("path")
        val url = json.text("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (if (kind == ArtifactKind.Link) url == null else path == null) return null
        val name = json.text("name")?.trim()?.takeIf(String::isNotEmpty)
            ?: path?.let(::baseName)
            ?: url?.substringAfter("://")?.substringBefore('/')
            ?: return null
        return Artifact(
            kind = kind,
            source = ArtifactSource.of(json.text("source")),
            name = name,
            path = path,
            url = url,
            host = json.text("host"),
            exists = kind == ArtifactKind.Link || json.flag("exists") != false,
            size = json.long("size"),
            sessionId = sessionId,
            sessionTitle = json.text("session_title"),
            messageId = messageId,
            timestamp = timestamp,
        )
    }

    /** A host path's last part, for either separator. */
    fun baseName(path: String): String =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

    private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
