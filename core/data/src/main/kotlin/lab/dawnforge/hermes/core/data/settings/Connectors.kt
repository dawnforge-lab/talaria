package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** An MCP server in the profile's config (`/api/mcp/servers`). */
data class McpServer(
    val name: String,
    /** `http` or `stdio`. */
    val transport: String,
    val url: String?,
    val command: String?,
    val args: List<String>,
    /** `oauth`, `header`, or none. */
    val auth: String?,
    val enabled: Boolean,
    /** Set when a plugin brings it: it is changed from Plugins, not here. */
    val plugin: String?,
) {
    /** Whether it can be signed in to from the phone: a remote server without a fixed key. */
    val canSignIn: Boolean get() = transport == "http" && url != null && auth != "header" && plugin == null

    /** What it runs: its address, or its command line. */
    val runs: String? get() = url ?: command?.let { (listOf(it) + args).joinToString(" ") }
}

/** A key a catalog entry asks for. */
data class CatalogKey(val name: String, val prompt: String, val required: Boolean)

/** An MCP server in Hermes' catalog (`/api/mcp/catalog`). */
data class McpCatalogEntry(
    val name: String,
    val description: String,
    val transport: String,
    /** `none`, `api_key` or `oauth`. */
    val authType: String,
    val keys: List<CatalogKey>,
    val url: String?,
    val command: String?,
    val args: List<String>,
    val installed: Boolean,
) {
    val runs: String? get() = url ?: command?.let { (listOf(it) + args).joinToString(" ") }
}

/** What testing a server found (`POST /api/mcp/servers/{name}/test`). */
data class McpProbe(val ok: Boolean, val error: String?, val tools: List<Pair<String, String>>) {
    /** The error reads as a missing or refused sign-in. */
    val needsSignIn: Boolean get() = !ok && error != null && SIGN_IN.containsMatchIn(error)

    private companion object {
        val SIGN_IN = Regex("401|unauthori[sz]ed|forbidden|invalid token|authentication|oauth", RegexOption.IGNORE_CASE)
    }
}

/** An OAuth sign-in Hermes runs for a server: the page to open, then its progress. */
data class McpSignIn(val flowId: String, val url: String?, val status: String, val error: String?)

/** An agent plugin installed on the host (`/api/dashboard/plugins/hub` → `plugins[]`). */
data class AgentPlugin(
    val name: String,
    val version: String,
    val description: String,
    /** `bundled`, `user`, `git`, `project` or `entrypoint`. */
    val source: String,
    /** `enabled`, `disabled` or `inactive`. */
    val status: String,
    val canRemove: Boolean,
    val canUpdate: Boolean,
    /** The folder it was installed to: what update and uninstall name it by. */
    val folder: String,
    val signInCommand: String?,
) {
    val enabled: Boolean get() = status == "enabled"
}

/** A plugin in Hermes' reviewed catalog (`/api/dashboard/plugins/catalog`). */
data class CatalogPlugin(
    val name: String,
    val title: String,
    val description: String,
    /** `official` or `community`. */
    val tier: String,
    val summary: String,
    val installed: Boolean,
    val updateAvailable: Boolean,
)

/** Bundled plugins worth showing: the rest are Hermes' own parts, as on iOS. */
private val SHOWN_BUNDLED = setOf("disk-cleanup", "security-guidance")
private val HIDDEN_PREFIXES = listOf("dashboard_auth/", "model-providers/", "platforms/")

object ConnectorsJson {
    fun servers(json: JsonElement): List<McpServer> =
        ((json as? JsonObject)?.get("servers") as? JsonArray).orEmpty().mapNotNull { row ->
            val server = row as? JsonObject ?: return@mapNotNull null
            McpServer(
                name = server.text("name") ?: return@mapNotNull null,
                transport = server.text("transport") ?: "stdio",
                url = server.text("url"),
                command = server.text("command"),
                args = server.strings("args"),
                auth = server.text("auth"),
                enabled = server.flag("enabled") != false,
                plugin = if (server.text("source") == "plugin") server.text("plugin") ?: "" else null,
            )
        }

    fun catalog(json: JsonElement): List<McpCatalogEntry> =
        ((json as? JsonObject)?.get("entries") as? JsonArray).orEmpty().mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            McpCatalogEntry(
                name = entry.text("name") ?: return@mapNotNull null,
                description = entry.text("description").orEmpty(),
                transport = entry.text("transport") ?: "stdio",
                authType = entry.text("auth_type") ?: "none",
                keys = (entry["required_env"] as? JsonArray).orEmpty().mapNotNull { key ->
                    val spec = key as? JsonObject ?: return@mapNotNull null
                    val name = spec.text("name") ?: return@mapNotNull null
                    CatalogKey(name, spec.text("prompt") ?: name, spec.flag("required") != false)
                },
                url = entry.text("url"),
                command = entry.text("command"),
                args = entry.strings("args"),
                installed = entry.flag("installed") == true,
            )
        }

    fun probe(json: JsonElement): McpProbe {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        return McpProbe(
            ok = reply.flag("ok") == true,
            error = reply.text("error"),
            tools = (reply["tools"] as? JsonArray).orEmpty().mapNotNull { row ->
                val tool = row as? JsonObject ?: return@mapNotNull null
                (tool.text("name") ?: return@mapNotNull null) to tool.text("description").orEmpty()
            },
        )
    }

    fun signIn(json: JsonElement): McpSignIn? {
        val flow = json as? JsonObject ?: return null
        return McpSignIn(
            flowId = flow.text("flow_id") ?: return null,
            url = flow.text("authorization_url"),
            status = flow.text("status") ?: "starting",
            error = flow.text("error"),
        )
    }

    fun plugins(json: JsonElement): List<AgentPlugin> =
        ((json as? JsonObject)?.get("plugins") as? JsonArray).orEmpty().mapNotNull { row ->
            val plugin = row as? JsonObject ?: return@mapNotNull null
            val name = plugin.text("name") ?: return@mapNotNull null
            val source = plugin.text("source") ?: "user"
            if (HIDDEN_PREFIXES.any(name::startsWith)) return@mapNotNull null
            if (source == "bundled" && name !in SHOWN_BUNDLED) return@mapNotNull null
            AgentPlugin(
                name = name,
                version = plugin.text("version").orEmpty(),
                description = plugin.text("description").orEmpty(),
                source = source,
                status = plugin.text("runtime_status") ?: "inactive",
                canRemove = plugin.flag("can_remove") == true,
                canUpdate = plugin.flag("can_update_git") == true,
                folder =
                    plugin.text("path")?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\') ?: name,
                signInCommand = if (plugin.flag("auth_required") == true) plugin.text("auth_command") else null,
            )
        }.sortedBy { it.name.lowercase() }

    fun pluginCatalog(json: JsonElement): List<CatalogPlugin> =
        ((json as? JsonObject)?.get("entries") as? JsonArray).orEmpty().mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            val name = entry.text("name") ?: return@mapNotNull null
            CatalogPlugin(
                name = name,
                title = entry.text("title") ?: name,
                description = entry.text("description").orEmpty(),
                tier = entry.text("tier") ?: "community",
                summary = entry.text("capability_summary") ?: entry.text("description").orEmpty(),
                installed = entry.flag("installed") == true,
                updateAvailable = entry.flag("update_available") == true,
            )
        }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
