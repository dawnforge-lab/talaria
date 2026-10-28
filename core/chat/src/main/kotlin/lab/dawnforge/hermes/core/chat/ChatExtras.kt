package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A chat's own switches, as `session.info` reports them. */
data class ChatSettings(
    /** Fast mode: the provider's priority tier, where the model has one. */
    val fast: Boolean,
    /** Commands run without asking for approval. */
    val yolo: Boolean,
    /** The profile's approval mode: `manual`, `smart` or `off`. */
    val approvalMode: String?,
) {
    /** With approvals off for the whole profile, every chat runs without asking; one chat can't turn it back on. */
    val yoloLocked: Boolean get() = approvalMode == "off"

    companion object {
        fun from(sessionInfo: JsonObject): ChatSettings? {
            val fast = sessionInfo.flag("fast")
            val yolo = sessionInfo.flag("yolo")
            val mode = sessionInfo.string("approval_mode")
            if (fast == null && yolo == null && mode == null) return null
            return ChatSettings(fast ?: false, yolo ?: (mode == "off"), mode)
        }
    }
}

/** A slash command Hermes offers, for the composer's suggestions. */
data class SlashCommand(
    /** Without the slash. */
    val name: String,
    val aliases: List<String>,
    /** Hermes' description, with its usage hint folded in ("… (usage: /name <arg>)"). */
    val description: String,
    val category: String,
) {
    fun matches(prefix: String): Boolean {
        val wanted = prefix.lowercase()
        return name.startsWith(wanted) || aliases.any { it.startsWith(wanted) }
    }

    companion object {
        /**
         * `commands.catalog`: the categories' commands, then the rest (skills and extensions), with
         * their aliases from `canon`. Commands Hermes marks for the terminal, messaging apps, voice,
         * or as hidden are left out, as the desktop leaves them out of its popover.
         */
        fun parseCatalog(catalog: JsonObject): List<SlashCommand> {
            val details = catalog.obj("commands")
            fun shown(name: String): Boolean {
                val desktop = details?.obj(name)?.string("desktop") ?: return true
                return desktop in SHOWN_DESKTOP_MARKS
            }
            val aliases = mutableMapOf<String, MutableList<String>>()
            catalog.obj("canon")?.forEach { (alias, target) ->
                val canonical = (target as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                if (alias !=
                    canonical
                ) {
                    aliases.getOrPut(canonical.removePrefix("/")) { mutableListOf() } +=
                        alias.removePrefix("/")
                }
            }
            val commands = linkedMapOf<String, SlashCommand>()
            fun add(pairs: JsonArray?, category: String) {
                for (pair in pairs.orEmpty()) {
                    val row = pair as? JsonArray ?: continue
                    val key = (row.getOrNull(0) as? JsonPrimitive)?.content ?: continue
                    val name = key.removePrefix("/").trim()
                    if (name.isEmpty() || name in commands || !shown(key)) continue
                    val description = (row.getOrNull(1) as? JsonPrimitive)?.content.orEmpty()
                    commands[name] = SlashCommand(name, aliases[name].orEmpty().sorted(), description, category)
                }
            }
            (catalog["categories"] as? JsonArray).orEmpty().forEach { entry ->
                val category = entry as? JsonObject ?: return@forEach
                add(category["pairs"] as? JsonArray, category.string("name").orEmpty())
            }
            add(catalog["pairs"] as? JsonArray, EXTENSIONS)
            return commands.values.toList()
        }

        /** Where the catalog's own top-level entries (skills, quick and plugin commands) are filed. */
        const val EXTENSIONS = ""

        private val SHOWN_DESKTOP_MARKS = setOf("advanced", "settings")
    }
}

/** What a slash command run on Hermes (`slash.exec`, or `command.dispatch`) asks the app to do. */
sealed interface SlashResult {
    /** Text to show, and a note when Hermes could only do part of it. */
    data class Output(val text: String, val warning: String?) : SlashResult

    /** A message to send as an ordinary turn; [display] is how to show it, when it differs. */
    data class Send(val message: String, val display: String?) : SlashResult

    /** Text to put in the composer for the user to finish, with an optional note. */
    data class Prefill(val message: String, val notice: String?) : SlashResult

    /** Another command to run in this one's place. */
    data class Alias(val target: String) : SlashResult

    companion object {
        fun parse(result: JsonObject): SlashResult {
            val message = result.string("message")
            val notice = result.string("notice")
            return when (result.string("type")) {
                "send", "skill" -> message?.let { Send(it, result.string("display")) }
                "prefill" -> message?.let { Prefill(it, notice) }
                "alias" -> result.string("target")?.let(::Alias)
                else -> null
            } ?: Output(
                result.string("output") ?: message ?: notice ?: "",
                result.string("warning")?.takeIf(String::isNotBlank),
            )
        }
    }
}

/** What `session.compress` did. */
sealed interface CompressOutcome {
    /** Done: [text] says what changed. */
    data class Done(val text: String) : CompressOutcome

    /** Nothing changed: there was nothing to compress, it would have grown, or another one holds the lock. */
    data class Unchanged(val text: String?) : CompressOutcome

    /** Still running on the host; the transcript changes when it finishes. */
    data class Pending(val text: String?) : CompressOutcome

    companion object {
        fun parse(result: JsonObject): CompressOutcome {
            if (result.flag("lock_held") == true) return Unchanged(result.string("message"))
            val status = result.string("status")
            if (status == "pending") return Pending(result.string("message"))
            val summary = result.obj("summary")
            val lines = listOfNotNull(
                summary?.string("headline"),
                summary?.string("token_line"),
                summary?.string("note"),
            ).map(String::trim).filter(String::isNotEmpty)
            val text = lines.joinToString("\n").ifEmpty {
                result.obj("host_ack")?.string("output")?.trim().orEmpty()
            }
            val unchanged = status == "aborted" || summary?.flag("noop") == true ||
                summary?.flag("aborted") == true
            return if (unchanged) Unchanged(text.ifEmpty { null }) else Done(text)
        }
    }
}

/** How a chat's context window is used (`session.context_breakdown`). */
data class ContextBreakdown(
    val used: Long,
    val max: Long,
    val percent: Int,
    /** True when Hermes estimated it rather than read it from the provider. */
    val estimated: Boolean,
    val categories: List<Category>,
) {
    data class Category(val id: String, val label: String, val tokens: Long)

    companion object {
        fun parse(result: JsonObject): ContextBreakdown {
            val used = result.long("context_used") ?: result.long("estimated_total") ?: 0
            val max = result.long("context_max") ?: 0
            val percent = result.long("context_percent")?.toInt()
                ?: if (max > 0) (used * PERCENT / max).toInt() else 0
            val categories = (result["categories"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val id = row.string("id") ?: return@mapNotNull null
                Category(id, row.string("label") ?: id, row.long("tokens") ?: 0)
            }
            return ContextBreakdown(used, max, percent, result.flag("context_estimated") == true, categories)
        }

        private const val PERCENT = 100
    }
}

/** A sub-agent a turn delegated to, from the turn's `subagent.*` events and `subagent.list`. */
data class DelegateAgent(
    /** Hermes' sub-agent id, or one made from the delegation while it has none yet. */
    val id: String,
    /** Hermes' sub-agent id, which steering and stopping need. */
    val subagentId: String?,
    val goal: String,
    val status: AgentStatus,
    val model: String?,
    val toolCount: Int,
    /** The latest thing it did or said, one line. */
    val lastLine: String?,
    val summary: String?,
) {
    val isActive: Boolean get() = status == AgentStatus.Queued || status == AgentStatus.Running

    companion object {
        /** The agent a `subagent.*` event is about, updated from it; null when the event names none. */
        fun fromEvent(type: String, payload: JsonObject, previous: Map<String, DelegateAgent>): DelegateAgent? {
            val subagentId = payload.string("subagent_id")?.takeIf(String::isNotEmpty)
            val id = subagentId ?: payload.string("delegation_id")?.let { "$it#${payload.long("task_index") ?: 0}" }
                ?: payload.string("goal")?.let { "#${payload.long("task_index") ?: 0}:$it" }
                ?: return null
            val before = previous[id]
            val status = statusOf(payload.string("status"))
                ?: when (type) {
                    "subagent.spawn_requested" -> AgentStatus.Queued
                    "subagent.complete" -> AgentStatus.Completed
                    else -> AgentStatus.Running
                }
            val line = when (type) {
                "subagent.tool" -> payload.string("tool_preview") ?: payload.string("text")
                    ?: payload.string("tool_name")

                "subagent.thinking", "subagent.progress" -> payload.string("text")

                else -> null
            }?.lineSequence()?.map(String::trim)?.lastOrNull(String::isNotEmpty)
            return DelegateAgent(
                id = id,
                subagentId = subagentId ?: before?.subagentId,
                goal = payload.string("goal") ?: before?.goal.orEmpty(),
                status = status,
                model = payload.string("model") ?: before?.model,
                toolCount = payload.long("tool_count")?.toInt() ?: before?.toolCount ?: 0,
                lastLine = line ?: before?.lastLine,
                summary = payload.string("summary") ?: before?.summary,
            )
        }

        /** `subagent.list`: the sub-agents Hermes still has for the chat. */
        fun parseList(result: JsonObject): List<DelegateAgent> =
            (result["subagents"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val row = entry as? JsonObject ?: return@mapNotNull null
                val id = row.string("subagent_id") ?: return@mapNotNull null
                DelegateAgent(
                    id = id,
                    subagentId = id,
                    goal = row.string("goal").orEmpty(),
                    status = statusOf(row.string("status")) ?: AgentStatus.Running,
                    model = row.string("model"),
                    toolCount = row.long("tool_count")?.toInt() ?: 0,
                    lastLine = row.string("last_tool"),
                    summary = null,
                )
            }

        private fun statusOf(status: String?): AgentStatus? = when (status) {
            "queued" -> AgentStatus.Queued
            "running" -> AgentStatus.Running
            "completed" -> AgentStatus.Completed
            "failed", "error", "timeout" -> AgentStatus.Failed
            "interrupted" -> AgentStatus.Interrupted
            else -> null
        }
    }
}

enum class AgentStatus { Queued, Running, Completed, Failed, Interrupted }
