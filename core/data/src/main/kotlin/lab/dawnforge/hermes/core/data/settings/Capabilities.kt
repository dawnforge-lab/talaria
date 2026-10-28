package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Where a skill came from (`provenance`). */
enum class SkillOrigin { Bundled, Hub, Agent, External }

data class Skill(
    val name: String,
    val description: String,
    /** Hermes' category key, `software_development`; blank is general. */
    val category: String,
    val enabled: Boolean,
    /** How often the agent used, read or changed it. */
    val usage: Long,
    val origin: SkillOrigin,
)

/** A skill in Hermes' official catalog (`/api/skills/hub/official`). */
data class CatalogSkill(
    val name: String,
    val identifier: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    val installed: Boolean,
)

data class Toolset(
    val name: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    /** False when it still needs keys. */
    val configured: Boolean,
    val tools: List<String>,
)

/** A key a backend needs (`env_vars`). Its value is never read back: only whether it is set. */
data class BackendKey(val key: String, val prompt: String, val url: String?, val set: Boolean)

/** How ready a backend is. */
enum class BackendStatus { Ready, NeedsSetup, NeedsAuth, NeedsKeys }

/** A backend of a toolset (`/api/tools/toolsets/{name}/config`), web search or image generation say. */
data class ToolBackend(
    val name: String,
    val badge: String?,
    val tag: String?,
    val keys: List<BackendKey>,
    val active: Boolean,
    val status: BackendStatus,
    /** The web toolset's own name for it, and what it can do there (`search`, `extract`). */
    val webBackend: String?,
    val capabilities: List<String>,
    /** Set when it runs through the user's Nous subscription. */
    val nousFeature: String?,
)

data class ToolsetConfig(
    /** False when the toolset has no backends to choose from. */
    val hasBackends: Boolean,
    val backends: List<ToolBackend>,
    val activeSearch: String?,
    val activeExtract: String?,
    val searchViaNous: Boolean,
    val extractViaNous: Boolean,
) {
    /** True when [backend] is the one the web toolset uses for [capability]. */
    fun serves(backend: ToolBackend, capability: String): Boolean {
        val active = if (capability == "search") activeSearch else activeExtract
        val viaNous = if (capability == "search") searchViaNous else extractViaNous
        return backend.webBackend != null && backend.webBackend == active && (backend.nousFeature != null) == viaNous
    }
}

/** Where the terminal toolset runs commands (`/api/tools/terminal/backends`). */
data class TerminalBackend(
    val name: String,
    val label: String,
    val description: String,
    val active: Boolean,
    /** `ready`, `needs_setup` or `unavailable`. */
    val status: String,
    val detail: String?,
)

/** Toolsets the iOS app leaves out: platform internals with nothing to switch. */
val HIDDEN_TOOLSETS = setOf("discord", "discord_admin", "yuanbao", "context_engine", "moa")

object CapabilitiesJson {
    fun skills(json: JsonElement): List<Skill> = (json as? JsonArray).orEmpty().mapNotNull { row ->
        val skill = row as? JsonObject ?: return@mapNotNull null
        Skill(
            name = skill.text("name") ?: return@mapNotNull null,
            description = skill.text("description").orEmpty(),
            category = skill.text("category").orEmpty(),
            enabled = skill.flag("enabled") != false,
            usage = (skill["usage"] as? JsonPrimitive)?.longOrNull ?: 0,
            origin = when (skill.text("provenance")) {
                "hub" -> SkillOrigin.Hub
                "agent" -> SkillOrigin.Agent
                "external" -> SkillOrigin.External
                else -> SkillOrigin.Bundled
            },
        )
    }

    fun catalog(json: JsonElement): List<CatalogSkill> =
        ((json as? JsonObject)?.get("skills") as? JsonArray).orEmpty().mapNotNull { row ->
            val skill = row as? JsonObject ?: return@mapNotNull null
            val name = skill.text("name") ?: return@mapNotNull null
            CatalogSkill(
                name = name,
                identifier = skill.text("identifier") ?: name,
                description = skill.text("description").orEmpty(),
                category = skill.text("category") ?: "general",
                tags = (skill["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                installed = skill.flag("installed") == true,
            )
        }

    fun toolsets(json: JsonElement): List<Toolset> = (json as? JsonArray).orEmpty().mapNotNull { row ->
        val set = row as? JsonObject ?: return@mapNotNull null
        val name = set.text("name") ?: return@mapNotNull null
        if (name in HIDDEN_TOOLSETS) return@mapNotNull null
        Toolset(
            name = name,
            label = toolsetLabel(name, set.text("label")),
            description = set.text("description").orEmpty(),
            enabled = set.flag("enabled") == true,
            configured = set.flag("configured") != false,
            tools = (set["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
        )
    }

    /** Hermes' label without its leading emoji, or the name in words. */
    fun toolsetLabel(name: String, label: String?): String {
        val text = label?.dropWhile { !it.isLetterOrDigit() }?.trim()
        return text?.takeIf(String::isNotEmpty)
            ?: name.split('_').joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }
    }

    /** Calls per tool over the year (`/api/analytics/usage?days=365` → `tools[]`). */
    fun toolUsage(json: JsonElement): Map<String, Long> =
        ((json as? JsonObject)?.get("tools") as? JsonArray).orEmpty().mapNotNull { row ->
            val tool = row as? JsonObject ?: return@mapNotNull null
            val name = tool.text("tool") ?: return@mapNotNull null
            name to ((tool["count"] as? JsonPrimitive)?.longOrNull ?: 0)
        }.toMap()

    fun toolsetConfig(json: JsonElement): ToolsetConfig {
        val config = json as? JsonObject ?: JsonObject(emptyMap())
        val backends = (config["providers"] as? JsonArray).orEmpty().mapNotNull { row ->
            val backend = row as? JsonObject ?: return@mapNotNull null
            ToolBackend(
                name = backend.text("name") ?: return@mapNotNull null,
                badge = backend.text("badge"),
                tag = backend.text("tag"),
                keys = (backend["env_vars"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val key = entry as? JsonObject ?: return@mapNotNull null
                    val name = key.text("key") ?: return@mapNotNull null
                    BackendKey(name, key.text("prompt") ?: name, key.text("url"), key.flag("is_set") == true)
                },
                active = backend.flag("is_active") == true,
                status = when (backend.text("status")) {
                    "needs_setup" -> BackendStatus.NeedsSetup
                    "needs_auth" -> BackendStatus.NeedsAuth
                    "needs_keys" -> BackendStatus.NeedsKeys
                    else -> BackendStatus.Ready
                },
                webBackend = backend.text("web_backend"),
                capabilities = (backend["capabilities"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.content
                },
                nousFeature = backend.text("managed_nous_feature"),
            )
        }
        return ToolsetConfig(
            hasBackends = config.flag("has_category") == true && backends.isNotEmpty(),
            backends = backends,
            activeSearch = config.text("active_search_backend"),
            activeExtract = config.text("active_extract_backend"),
            searchViaNous = config.flag("search_via_nous") == true,
            extractViaNous = config.flag("extract_via_nous") == true,
        )
    }

    fun terminalBackends(json: JsonElement): List<TerminalBackend> =
        ((json as? JsonObject)?.get("backends") as? JsonArray).orEmpty().mapNotNull { row ->
            val backend = row as? JsonObject ?: return@mapNotNull null
            val name = backend.text("name") ?: return@mapNotNull null
            TerminalBackend(
                name = name,
                label = backend.text("label") ?: name,
                description = backend.text("description").orEmpty(),
                active = backend.flag("active") == true,
                status = backend.text("status") ?: "ready",
                detail = backend.text("detail"),
            )
        }
}

/**
 * The fields of a `SKILL.md`'s front matter, the top-level `key: value` lines between its `---`
 * fences (nested lines are left out, quotes taken off), and the text after it.
 */
fun splitSkillFile(content: String): Pair<Map<String, String>, String> {
    val lines = content.lines()
    if (lines.firstOrNull()?.trim() != "---") return emptyMap<String, String>() to content
    val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
    if (end < 0) return emptyMap<String, String>() to content
    val fields = lines.subList(1, end + 1).mapNotNull { line ->
        if (line.isEmpty() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return@mapNotNull null
        val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
        val value = line.substring(colon + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        if (value.isEmpty()) null else line.substring(0, colon).trim() to value
    }.toMap()
    return fields to lines.drop(end + 2).joinToString("\n").trimStart('\n')
}

/** `software_development` as "Software development"; blank is "General". */
fun prettyCategory(category: String): String =
    category.replace('_', ' ').replace('-', ' ').trim().ifEmpty { "general" }.replaceFirstChar(Char::titlecase)

/** A use count as iOS shows it: `×7`, `×1.2k`, `×3.4M`. */
fun usageLabel(count: Long): String = "×" + when {
    count >= 1_000_000 -> compact(count / 1_000_000.0) + "M"
    count >= 1_000 -> compact(count / 1_000.0) + "k"
    else -> count.toString()
}

private fun compact(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
