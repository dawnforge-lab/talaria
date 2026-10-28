package lab.dawnforge.hermes.core.data.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/**
 * Projects (`projects.*` on the live connection), moving a chat into one
 * (`session.workspace.move`), and the files a new project needs (`/api/fs`).
 */
class ProjectsRepository(
    private val requests: DashboardRequests,
    /** The active dashboard's live connection, if any. */
    private val gateway: () -> GatewayClient?,
) {
    /** The profile's projects with a few chats each; null when this Hermes has no projects. */
    suspend fun tree(profile: String): NetResult<List<Project>?> = rpc(
        "projects.tree",
        buildJsonObject {
            put("preview_limit", PREVIEWS)
            put("profile", profile)
        },
        missing = { null },
    ) { ProjectsJson.tree(it) }

    /** A project's chats by folder; null when Hermes doesn't know the project (any more). */
    suspend fun sessions(profile: String, projectId: String): NetResult<ProjectDetail?> = rpc(
        "projects.project_sessions",
        buildJsonObject {
            put("project_id", projectId)
            put("profile", profile)
        },
    ) { ProjectsJson.detail(it) }

    /**
     * Makes a project of [folders] (the first is where its chats start) and makes it Hermes' active
     * one, as the desktop does. An [idea] is saved as `IDEA.md` in the first folder; that it
     * couldn't be is not a failure.
     */
    suspend fun create(
        id: DashboardId,
        profile: String,
        name: String,
        folders: List<String>,
        idea: String,
    ): NetResult<Project> {
        val paths = folders.map(String::trim).filter(String::isNotEmpty).distinct()
        val made = rpc(
            "projects.create",
            buildJsonObject {
                put("name", name.trim())
                put("folders", JsonArray(paths.map(::JsonPrimitive)))
                put("primary_path", paths.firstOrNull().orEmpty())
                put("use", true)
                put("profile", profile)
            },
        ) { reply ->
            ((reply as? JsonObject)?.get("project") as? JsonObject)?.let(ProjectsJson::project)
        }
        val project = when (made) {
            is NetResult.Failure -> return made
            is NetResult.Success -> made.value ?: return NetResult.Failure(NO_PROJECT)
        }
        val first = paths.firstOrNull()
        if (idea.isNotBlank() && first != null) {
            val separator = if (first.endsWith('/') || first.endsWith('\\')) {
                ""
            } else if ('\\' in first) {
                "\\"
            } else {
                "/"
            }
            requests.run(id) { url, jar, cf ->
                post(
                    url,
                    jar,
                    cf,
                    "/api/fs/write-text",
                    buildJsonObject {
                        put("path", "$first${separator}IDEA.md")
                        put("content", idea.trim() + "\n")
                    },
                    mapOf("profile" to profile),
                )
            }
        }
        return NetResult.Success(project)
    }

    suspend fun rename(profile: String, projectId: String, name: String): NetResult<Unit> = rpc(
        "projects.update",
        buildJsonObject {
            put("id", projectId)
            put("name", name.trim())
            put("profile", profile)
        },
    ) { }

    /** Forgets a project; its folders, files and chats stay where they are. */
    suspend fun delete(profile: String, projectId: String): NetResult<Unit> = rpc(
        "projects.delete",
        buildJsonObject {
            put("id", projectId)
            put("profile", profile)
        },
    ) { }

    /**
     * Moves a chat (by its stored id) into [cwd], a project's folder: Hermes points the chat there,
     * and a running agent with it. No files move. Answers the folder Hermes stored.
     */
    suspend fun move(profile: String, storedId: String, cwd: String): NetResult<String> = rpc(
        "session.workspace.move",
        buildJsonObject {
            put("session_key", storedId)
            put("cwd", cwd)
            put("profile", profile)
        },
    ) { reply -> (reply as? JsonObject)?.string("cwd") ?: cwd }

    /** Where a folder picker starts: the profile's working folder on the host. */
    suspend fun defaultFolder(id: DashboardId, profile: String): NetResult<String> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, "/api/fs/default-cwd", query = mapOf("profile" to profile))
    }.let { result ->
        when (result) {
            is NetResult.Failure -> result

            is NetResult.Success -> (result.value as? JsonObject)?.string("cwd")?.let { NetResult.Success(it) }
                ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "No folder in the reply"))
        }
    }

    private suspend fun <T> rpc(
        method: String,
        params: JsonObject,
        missing: (() -> T)? = null,
        parse: (JsonElement) -> T,
    ): NetResult<T> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            NetResult.Success(parse(client.call(method, params, timeoutMs = TIMEOUT_MS)))
        } catch (e: RpcException) {
            if (missing != null && e.code == METHOD_NOT_FOUND) {
                NetResult.Success(missing())
            } else {
                NetResult.Failure(ConnectionFailure.UnexpectedResponse(e.code, e.message))
            }
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    companion object {
        /** Chats shown under each project in the list, as iOS asks for them. */
        const val PREVIEWS = 3

        /** Hermes walks every chat to build the tree, on a worker thread. */
        private const val TIMEOUT_MS = 30_000L
        private const val METHOD_NOT_FOUND = -32601
        private val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
        private val NO_PROJECT = ConnectionFailure.UnexpectedResponse(null, "No project in the reply")
    }
}
