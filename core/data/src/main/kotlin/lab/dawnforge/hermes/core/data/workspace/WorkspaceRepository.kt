package lab.dawnforge.hermes.core.data.workspace

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/**
 * Files on the Hermes host: a chat's workspace (`/api/fs/...`) and what chats produced (the push
 * plugin's `artifacts`). Hermes reads anywhere on an unmanaged host but hides its own secrets.
 */
class WorkspaceRepository(private val requests: DashboardRequests) {
    /** A folder's entries, folders first. A folder Hermes can't read fails with its reason. */
    suspend fun list(id: DashboardId, profile: String, path: String): NetResult<List<WorkspaceEntry>> = when (
        val result = requests.run(id) { url, jar, cf ->
            get(url, jar, cf, FS, listOf("list"), fsQuery(profile, path))
        }
    ) {
        is NetResult.Failure -> result

        is NetResult.Success -> when (val error = WorkspaceJson.listError(result.value)) {
            null -> NetResult.Success(WorkspaceJson.entries(result.value))
            "ENOENT", "ENOTDIR" -> NetResult.Failure(ConnectionFailure.NotFound(path))
            "EACCES" -> NetResult.Failure(ConnectionFailure.Refused(path))
            else -> NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, error))
        }
    }

    /** The start of a file, or that it isn't text. */
    suspend fun preview(id: DashboardId, profile: String, path: String): NetResult<FilePreview> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, FS, listOf("read-text"), fsQuery(profile, path)).map(WorkspaceJson::preview)
        }

    /**
     * Saves the file at [path] into [target] (`/api/fs/download`, streamed, up to [maxBytes]). On a
     * Hermes without that route it falls back to `/api/fs/read-data-url`, which sends up to 16 MiB.
     */
    suspend fun download(
        id: DashboardId,
        profile: String,
        path: String,
        target: File,
        maxBytes: Long = MAX_DOWNLOAD,
    ): NetResult<Long> {
        target.parentFile?.mkdirs()
        val streamed = requests.run(id) { url, jar, cf ->
            download(url, jar, cf, "$FS/download", fsQuery(profile, path), target, maxBytes)
        }
        val failure = (streamed as? NetResult.Failure)?.failure
        // FastAPI's own "Not Found" means the route is missing; a missing file says "File not found".
        if (failure !is ConnectionFailure.NotFound || failure.detail != ROUTE_MISSING) return streamed
        return requests.run(id) { url, jar, cf ->
            get(url, jar, cf, FS, listOf("read-data-url"), fsQuery(profile, path))
        }.let { result ->
            when (result) {
                is NetResult.Failure -> result

                is NetResult.Success -> {
                    val bytes = (result.value as? JsonObject)?.get("dataUrl")
                        ?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?.let(::decodeDataUrl)
                        ?: return NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "No file in the reply"))
                    target.writeBytes(bytes)
                    NetResult.Success(bytes.size.toLong())
                }
            }
        }
    }

    /** Whether the host's push plugin lists artifacts (capability `artifacts`). */
    suspend fun artifactsSupported(id: DashboardId): NetResult<Boolean> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, PLUGIN, listOf("capabilities")).map { reply ->
            ((reply as? JsonObject)?.get("capabilities") as? JsonArray)
                ?.any { (it as? JsonPrimitive)?.contentOrNull == ARTIFACTS } == true
        }
    }

    /**
     * A page of what chats produced, newest first: the profile's, or ([sessionIds]) one chat's by
     * every id it is known by. [before] continues from a page's `nextBefore`.
     */
    suspend fun artifacts(
        id: DashboardId,
        profile: String,
        sessionIds: List<String> = emptyList(),
        filter: ArtifactFilter = ArtifactFilter.All,
        query: String = "",
        showMissing: Boolean = false,
        before: Long? = null,
    ): NetResult<ArtifactPage> = requests.run(id) { url, jar, cf ->
        val params = mapOf(
            "limit" to PAGE.toString(),
            "kind" to filter.key,
            "q" to query.trim().takeIf(String::isNotEmpty),
            "missing" to if (showMissing) null else "hide",
            "before" to before?.toString(),
            "profile" to profile,
        )
        val ids = sessionIds.filter(String::isNotBlank).distinct().sorted().map { "session_id" to it }
        get(url, jar, cf, PLUGIN, listOf(ARTIFACTS), params, ids).let { reply ->
            when (reply) {
                is NetResult.Failure -> reply

                is NetResult.Success -> WorkspaceJson.artifacts(reply.value)?.let { page ->
                    // Plugins before 0.51.8 ignore `missing=hide`.
                    NetResult.Success(if (showMissing) page else page.copy(items = page.items.filter { it.exists }))
                } ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "Not a page of artifacts"))
            }
        }
    }

    private fun fsQuery(profile: String, path: String) = mapOf("path" to path, "profile" to profile)

    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val marker = ";base64,"
        val at = dataUrl.indexOf(marker)
        if (!dataUrl.startsWith("data:") || at < 0) return null
        return runCatching { java.util.Base64.getDecoder().decode(dataUrl.substring(at + marker.length)) }.getOrNull()
    }

    companion object {
        private const val FS = "/api/fs"
        private const val PLUGIN = "/api/plugins/talaria_push"
        private const val ARTIFACTS = "artifacts"
        private const val ROUTE_MISSING = "Not Found"

        /** Artifacts per page, as iOS asks for them. */
        const val PAGE = 60

        /** The most the phone saves of one file, as iOS. */
        const val MAX_DOWNLOAD = 512L * 1024 * 1024
    }
}
