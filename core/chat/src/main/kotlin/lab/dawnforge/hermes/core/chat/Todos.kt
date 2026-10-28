package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One item of the agent's plan (its `todo_list` tool), with how deep it sits under others. */
data class Todo(val id: String, val content: String, val status: TodoStatus, val depth: Int)

enum class TodoStatus { Pending, InProgress, Completed, Cancelled }

/**
 * The tools whose result can be the agent's plan: `todo_list` (and its old name), and the
 * `tool_call` bridge a model calls it through, which answers with the plan itself.
 */
val TODO_TOOLS = setOf("todo_list", "todo", "tool_call")

/**
 * The plan a `todo_list` result holds (`{"todos": [{id, content, status, parent?}], …}`), subtasks
 * under their parents and nested at most [MAX_DEPTH] deep; null when it isn't one.
 */
fun parseTodos(output: String?): List<Todo>? {
    val text = output?.trim()?.takeIf { it.startsWith("{") } ?: return null
    val root = runCatching { chatJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
    val rows = (root["todos"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
    val seen = HashSet<String>()
    val items = rows.mapNotNull { row ->
        val id = row.scalar("id") ?: return@mapNotNull null
        if (!seen.add(id)) return@mapNotNull null
        val status = when (row.string("status")) {
            "in_progress" -> TodoStatus.InProgress
            "completed" -> TodoStatus.Completed
            "cancelled" -> TodoStatus.Cancelled
            else -> TodoStatus.Pending
        }
        Triple(
            id,
            row.scalar("parent")?.takeIf {
                it != "0" && it != id
            },
            Todo(id, row.string("content").orEmpty(), status, 0),
        )
    }
    val parentOf = items.associate { it.first to it.second }
    fun depth(id: String): Int {
        var depth = 0
        var at = parentOf[id]
        while (at != null && at in parentOf && depth < MAX_DEPTH) {
            depth++
            at = parentOf[at]
        }
        return depth
    }
    return items.map { (id, _, todo) -> todo.copy(depth = depth(id)) }
}

private const val MAX_DEPTH = 6
