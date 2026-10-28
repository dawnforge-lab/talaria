package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult

/** How installing a catalog server went: done, or running in the background as an action. */
sealed interface McpInstall {
    data object Done : McpInstall

    data class Running(val action: String) : McpInstall
}

/** What changing a plugin did. */
sealed interface PluginChange {
    /** Done; [restart] when the gateway must restart to apply it. */
    data class Done(val restart: Boolean, val missingKeys: List<String> = emptyList()) : PluginChange

    data object Unchanged : PluginChange

    /** The update asks for more than the installed version; [lines] says what. */
    data class NeedsConsent(val lines: List<String>) : PluginChange
}

/**
 * A profile's MCP servers and the host's agent plugins, as the iOS app's Capabilities pages manage
 * them. Server changes apply to new sessions; a plugin change says when it needs a restart.
 */
class ConnectorsRepository(private val requests: DashboardRequests) {
    suspend fun servers(id: DashboardId, profile: String): NetResult<List<McpServer>> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, MCP, listOf("servers"), mapOf("profile" to profile)).map(ConnectorsJson::servers)
        }

    suspend fun catalog(id: DashboardId, profile: String): NetResult<List<McpCatalogEntry>> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, MCP, listOf("catalog"), mapOf("profile" to profile)).map(ConnectorsJson::catalog)
        }

    /** Connects to a server, lists its tools and disconnects. */
    suspend fun test(id: DashboardId, profile: String, name: String): NetResult<McpProbe> =
        requests.run(id) { url, jar, cf ->
            post(url, jar, cf, MCP, EMPTY, mapOf("profile" to profile), listOf("servers", name, "test"))
                .map(ConnectorsJson::probe)
        }

    suspend fun setEnabled(id: DashboardId, profile: String, name: String, enabled: Boolean): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("enabled", enabled)
                put("profile", profile)
            }
            put(url, jar, cf, MCP, listOf("servers", name, "enabled"), body, mapOf("profile" to profile)).map { }
        }

    /** Takes the server out of the profile's config; its keys stay in `.env`. */
    suspend fun remove(id: DashboardId, profile: String, name: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            delete(url, jar, cf, MCP, listOf("servers", name), mapOf("profile" to profile)).map { }
        }

    /** Installs a catalog server with its [keys] (secret ones go to `.env`), turned on. */
    suspend fun install(
        id: DashboardId,
        profile: String,
        name: String,
        keys: Map<String, String>,
    ): NetResult<McpInstall> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("name", name)
            putJsonObject("env") { keys.forEach { (key, value) -> put(key, value) } }
            put("enable", true)
            put("profile", profile)
        }
        post(url, jar, cf, MCP, body, mapOf("profile" to profile), listOf("catalog", "install")).map { reply ->
            val answer = reply as? JsonObject
            val action = (answer?.get("action") as? JsonPrimitive)?.content
            if (answer?.flag("background") == true &&
                action != null
            ) {
                McpInstall.Running(action)
            } else {
                McpInstall.Done
            }
        }
    }

    /** Starts an OAuth sign-in to a remote server: Hermes answers the page to open. */
    suspend fun signIn(id: DashboardId, profile: String, name: String): NetResult<McpSignIn?> =
        requests.run(id) { url, jar, cf ->
            post(url, jar, cf, MCP, EMPTY, mapOf("profile" to profile), listOf("servers", name, "auth"))
                .map(ConnectorsJson::signIn)
        }

    /** A sign-in's progress: `starting`, `authorization_required`, `approved` or `error`. */
    suspend fun signInStatus(id: DashboardId, flowId: String): NetResult<McpSignIn?> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, MCP, listOf("oauth", "flows", flowId)).map(ConnectorsJson::signIn)
        }

    suspend fun cancelSignIn(id: DashboardId, flowId: String): NetResult<Unit> = requests.run(id) { url, jar, cf ->
        delete(url, jar, cf, MCP, listOf("oauth", "flows", flowId)).map { }
    }

    suspend fun plugins(id: DashboardId, profile: String): NetResult<List<AgentPlugin>> = requests.run(id) {
            url,
            jar,
            cf,
        ->
        get(
            url,
            jar,
            cf,
            "/api/dashboard/plugins/hub",
            query = mapOf("profile" to profile),
        ).map(ConnectorsJson::plugins)
    }

    suspend fun pluginCatalog(id: DashboardId): NetResult<List<CatalogPlugin>> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, "/api/dashboard/plugins/catalog").map(ConnectorsJson::pluginCatalog)
    }

    /** Turns a plugin on or off for the host's default profile, which plugins belong to. */
    suspend fun setPlugin(id: DashboardId, name: String, enabled: Boolean): NetResult<PluginChange> =
        requests.run(id) { url, jar, cf ->
            post(url, jar, cf, PLUGINS, EMPTY, segments = name.split('/') + if (enabled) "enable" else "disable")
                .map { PluginChange.Done(restart = (it as? JsonObject)?.flag("restart_required") == true) }
        }

    /** Updates a plugin; [acceptMore] agrees to an update that asks for more than before. */
    suspend fun updatePlugin(id: DashboardId, folder: String, acceptMore: Boolean = false): NetResult<PluginChange> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject { if (acceptMore) put("accept_capabilities", true) }
            post(url, jar, cf, PLUGINS, body, segments = folder.split('/') + "update").map { reply ->
                val answer = reply as? JsonObject ?: JsonObject(emptyMap())
                when {
                    answer.flag("consent_required") == true -> PluginChange.NeedsConsent(
                        (answer["delta_lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                            ?.takeIf { it.isNotEmpty() }
                            ?: listOfNotNull((answer["error"] as? JsonPrimitive)?.content),
                    )

                    answer.flag("unchanged") == true -> PluginChange.Unchanged

                    else -> PluginChange.Done(restart = answer.flag("restart_required") != false)
                }
            }
        }

    /** Deletes a plugin's files from the host; the gateway unloads it on its next restart. */
    suspend fun uninstallPlugin(id: DashboardId, folder: String): NetResult<PluginChange> =
        requests.run(id) { url, jar, cf ->
            delete(url, jar, cf, PLUGINS, folder.split('/')).map { PluginChange.Done(restart = true) }
        }

    suspend fun installPlugin(id: DashboardId, catalogName: String): NetResult<PluginChange> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("identifier", "")
                put("catalog_name", catalogName)
                put("enable", true)
            }
            post(url, jar, cf, PLUGINS, body, segments = listOf("install")).map { reply ->
                val answer = reply as? JsonObject ?: JsonObject(emptyMap())
                PluginChange.Done(
                    restart = answer.flag("restart_required") == true,
                    missingKeys = (answer["missing_env"] as? JsonArray).orEmpty().mapNotNull { entry ->
                        (entry as? JsonPrimitive)?.content
                            ?: ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                    },
                )
            }
        }

    private companion object {
        const val MCP = "/api/mcp"

        /** A plugin name may hold a `/`: each part is its own path segment. */
        const val PLUGINS = "/api/dashboard/agent-plugins"
        val EMPTY = JsonObject(emptyMap())
    }
}

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
