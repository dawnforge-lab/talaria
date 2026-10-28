package lab.dawnforge.hermes.core.data.tasks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A lane of the board, in Hermes' workflow order. Some are Hermes' own: a task gets there only
 * through the dispatcher or the worker, never by hand ([manual] false).
 */
enum class TaskStatus(val key: String, val manual: Boolean, val canCreate: Boolean) {
    Triage("triage", true, true),
    Todo("todo", true, true),
    Scheduled("scheduled", false, false),
    Ready("ready", true, true),
    Running("running", false, false),
    Blocked("blocked", true, true),
    Review("review", false, false),
    Done("done", true, true),
    Archived("archived", true, false),
    Other("", false, false),
    ;

    companion object {
        fun of(key: String?): TaskStatus = entries.firstOrNull { it.key == key && it != Other } ?: Other

        /** The lanes a task can be moved to by hand, archive aside. */
        val MOVABLE = entries.filter { it.manual && it != Archived }
    }
}

data class KanbanBoard(
    val slug: String,
    val name: String,
    val description: String,
    val archived: Boolean,
    val current: Boolean,
    val total: Int,
)

data class Boards(val boards: List<KanbanBoard>, val current: String)

/** A task as the board and its page show it. Times are epoch seconds. */
data class KanbanTask(
    val id: String,
    val title: String,
    val body: String,
    val status: TaskStatus,
    val statusKey: String,
    val assignee: String?,
    val priority: Int,
    val createdAt: Long?,
    val startedAt: Long?,
    val completedAt: Long?,
    val lastHeartbeatAt: Long?,
    val latestSummary: String?,
    val commentCount: Int,
    val parentCount: Int,
    val childCount: Int,
    val progressDone: Int?,
    val progressTotal: Int?,
    val warningCount: Int,
    val result: String?,
    val createdBy: String?,
    val workerPid: Long?,
    val consecutiveFailures: Int,
    val lastFailure: String?,
    val workspaceKind: String?,
    val workspacePath: String?,
    val modelOverride: String?,
    val providerOverride: String?,
    val reasoningEffort: String?,
    val diagnostics: List<Diagnostic>,
) {
    /** `t_1a2b3c4d` as `1a2b3c`, as iOS shows it. */
    val shortId: String get() = id.removePrefix("t_").take(SHORT_ID)

    /** A running task whose worker hasn't checked in for a while. */
    fun silent(now: Long): Boolean =
        status == TaskStatus.Running && lastHeartbeatAt != null && now - lastHeartbeatAt > SILENT_AFTER_S

    private companion object {
        const val SHORT_ID = 6
        const val SILENT_AFTER_S = 120
    }
}

/** Something wrong with a task Hermes noticed, and what can be done about it. */
data class Diagnostic(
    val kind: String,
    val severity: String,
    val title: String,
    val detail: String?,
    val actions: List<DiagnosticAction>,
)

data class DiagnosticAction(val kind: String, val label: String, val command: String?)

data class BoardView(
    val lanes: List<Pair<TaskStatus, List<KanbanTask>>>,
    val assignees: List<String>,
    val latestEventId: Long?,
    val now: Long,
) {
    val tasks: List<KanbanTask> get() = lanes.flatMap { it.second }
}

data class TaskComment(val id: Long, val author: String, val body: String, val createdAt: Long)

data class TaskEvent(val id: Long, val kind: String, val payload: JsonObject, val createdAt: Long)

data class TaskRun(
    val id: Long,
    val profile: String?,
    val stepKey: String?,
    val status: String,
    val outcome: String?,
    val summary: String?,
    val error: String?,
    val workerPid: Long?,
    val startedAt: Long?,
    val endedAt: Long?,
) {
    val failed: Boolean get() = outcome in FAILED

    private companion object {
        val FAILED = setOf("crashed", "failed", "timed_out", "gave_up", "spawn_failed")
    }
}

/** A task linked to this one: its id, title and lane. */
data class LinkedTask(val id: String, val title: String, val status: TaskStatus)

data class TaskDetail(
    val task: KanbanTask,
    val comments: List<TaskComment>,
    val events: List<TaskEvent>,
    val parents: List<LinkedTask>,
    val children: List<LinkedTask>,
    val runs: List<TaskRun>,
) {
    /** What the worker asked when it blocked: the newest `blocked` event's reason, else a blocked run's summary. */
    val question: String?
        get() = if (task.status != TaskStatus.Blocked) {
            null
        } else {
            events.filter { it.kind == "blocked" }.maxByOrNull { it.createdAt }?.payload?.text("reason")
                ?: runs.firstOrNull { it.outcome == "blocked" }?.summary
        }
}

data class WorkerLog(val exists: Boolean, val sizeBytes: Long, val content: String, val truncated: Boolean)

data class KanbanProfile(val name: String, val isDefault: Boolean, val description: String, val autoDescribed: Boolean)

/** Who runs tasks nobody was given (`/orchestration`). */
data class Orchestration(
    val orchestratorProfile: String,
    val defaultAssignee: String,
    val autoDecompose: Boolean,
    val autoPromoteChildren: Boolean,
    val resolvedOrchestrator: String?,
    val resolvedDefaultAssignee: String?,
)

/** What a triage helper (specify, decompose) came to: Hermes answers 200 either way. */
data class TriageResult(val ok: Boolean, val reason: String?, val newTitle: String?, val childIds: List<String>)

object KanbanJson {
    fun boards(json: JsonElement): Boards {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        val boards = (reply["boards"] as? JsonArray).orEmpty().mapNotNull { row ->
            val board = row as? JsonObject ?: return@mapNotNull null
            val slug = board.text("slug") ?: return@mapNotNull null
            KanbanBoard(
                slug = slug,
                name = board.text("name") ?: slug,
                description = board.text("description").orEmpty(),
                archived = board.flag("archived") == true,
                current = board.flag("is_current") == true,
                total = board.long("total")?.toInt() ?: 0,
            )
        }
        val current = reply.text("current") ?: boards.firstOrNull { it.current }?.slug ?: DEFAULT_BOARD
        return Boards(boards, current)
    }

    fun board(json: JsonElement): BoardView {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        val lanes = (reply["columns"] as? JsonArray).orEmpty().mapNotNull { row ->
            val column = row as? JsonObject ?: return@mapNotNull null
            val status = TaskStatus.of(column.text("name"))
            status to (column["tasks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::task) }
        }.sortedBy { it.first.ordinal }
        return BoardView(
            lanes = lanes,
            assignees = (reply["assignees"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            },
            latestEventId = reply.long("latest_event_id")?.takeIf { it >= 0 },
            now = reply.long("now") ?: (System.currentTimeMillis() / 1000),
        )
    }

    fun task(json: JsonObject): KanbanTask? {
        val id = json.text("id") ?: return null
        val links = json["link_counts"] as? JsonObject
        val progress = json["progress"] as? JsonObject
        val warnings = json["warnings"] as? JsonObject
        return KanbanTask(
            id = id,
            title = json.text("title").orEmpty(),
            body = json.text("body").orEmpty(),
            status = TaskStatus.of(json.text("status")),
            statusKey = json.text("status").orEmpty(),
            assignee = json.text("assignee"),
            priority = json.long("priority")?.toInt() ?: 0,
            createdAt = json.long("created_at"),
            startedAt = json.long("started_at"),
            completedAt = json.long("completed_at"),
            lastHeartbeatAt = json.long("last_heartbeat_at"),
            latestSummary = json.text("latest_summary"),
            commentCount = json.long("comment_count")?.toInt() ?: 0,
            parentCount = links?.long("parents")?.toInt() ?: 0,
            childCount = links?.long("children")?.toInt() ?: 0,
            progressDone = progress?.long("done")?.toInt(),
            progressTotal = progress?.long("total")?.toInt(),
            warningCount = warnings?.long("count")?.toInt() ?: 0,
            result = json.text("result"),
            createdBy = json.text("created_by"),
            workerPid = json.long("worker_pid"),
            consecutiveFailures = json.long("consecutive_failures")?.toInt() ?: 0,
            lastFailure = json.text("last_failure_error"),
            workspaceKind = json.text("workspace_kind"),
            workspacePath = json.text("workspace_path"),
            modelOverride = json.text("model_override"),
            providerOverride = json.text("provider_override"),
            reasoningEffort = json.text("reasoning_effort"),
            diagnostics = (json["diagnostics"] as? JsonArray).orEmpty().mapNotNull { row ->
                val diagnostic = row as? JsonObject ?: return@mapNotNull null
                Diagnostic(
                    kind = diagnostic.text("kind").orEmpty(),
                    severity = diagnostic.text("severity") ?: "warning",
                    title = diagnostic.text("title") ?: diagnostic.text("kind").orEmpty(),
                    detail = diagnostic.text("detail"),
                    actions = (diagnostic["actions"] as? JsonArray).orEmpty().mapNotNull { action ->
                        val entry = action as? JsonObject ?: return@mapNotNull null
                        DiagnosticAction(
                            kind = entry.text("kind") ?: return@mapNotNull null,
                            label = entry.text("label") ?: entry.text("kind").orEmpty(),
                            command = (entry["payload"] as? JsonObject)?.text("command"),
                        )
                    },
                )
            },
        )
    }

    fun detail(json: JsonElement): TaskDetail? {
        val reply = json as? JsonObject ?: return null
        val task = (reply["task"] as? JsonObject)?.let(::task) ?: return null
        val linked = (reply["link_tasks"] as? JsonArray).orEmpty().mapNotNull { row ->
            val link = row as? JsonObject ?: return@mapNotNull null
            val id = link.text("id") ?: return@mapNotNull null
            id to LinkedTask(id, link.text("title").orEmpty(), TaskStatus.of(link.text("status")))
        }.toMap()
        val links = reply["links"] as? JsonObject
        fun ids(key: String) =
            (links?.get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .map { linked[it] ?: LinkedTask(it, "", TaskStatus.Other) }
        return TaskDetail(
            task = task,
            comments = (reply["comments"] as? JsonArray).orEmpty().mapNotNull { row ->
                val comment = row as? JsonObject ?: return@mapNotNull null
                TaskComment(
                    id = comment.long("id") ?: return@mapNotNull null,
                    author = comment.text("author").orEmpty(),
                    body = comment.text("body").orEmpty(),
                    createdAt = comment.long("created_at") ?: 0,
                )
            },
            events = (reply["events"] as? JsonArray).orEmpty().mapNotNull { row ->
                val event = row as? JsonObject ?: return@mapNotNull null
                TaskEvent(
                    id = event.long("id") ?: return@mapNotNull null,
                    kind = event.text("kind").orEmpty(),
                    payload = event["payload"] as? JsonObject ?: JsonObject(emptyMap()),
                    createdAt = event.long("created_at") ?: 0,
                )
            },
            parents = ids("parents"),
            children = ids("children"),
            runs = (reply["runs"] as? JsonArray).orEmpty().mapNotNull { row ->
                val run = row as? JsonObject ?: return@mapNotNull null
                TaskRun(
                    id = run.long("id") ?: return@mapNotNull null,
                    profile = run.text("profile"),
                    stepKey = run.text("step_key"),
                    status = run.text("status").orEmpty(),
                    outcome = run.text("outcome"),
                    summary = run.text("summary"),
                    error = run.text("error"),
                    workerPid = run.long("worker_pid"),
                    startedAt = run.long("started_at"),
                    endedAt = run.long("ended_at"),
                )
            }.sortedByDescending { it.startedAt ?: 0 },
        )
    }

    fun log(json: JsonElement): WorkerLog {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        return WorkerLog(
            exists = reply.flag("exists") == true,
            sizeBytes = reply.long("size_bytes") ?: 0,
            // The worker writes for a terminal: its colour and style codes are taken out.
            content = (reply["content"] as? JsonPrimitive)?.contentOrNull.orEmpty().replace(TERMINAL_CODES, ""),
            truncated = reply.flag("truncated") == true,
        )
    }

    fun profiles(json: JsonElement): List<KanbanProfile> =
        ((json as? JsonObject)?.get("profiles") as? JsonArray).orEmpty().mapNotNull { row ->
            val profile = row as? JsonObject ?: return@mapNotNull null
            KanbanProfile(
                name = profile.text("name") ?: return@mapNotNull null,
                isDefault = profile.flag("is_default") == true,
                description = profile.text("description").orEmpty(),
                autoDescribed = profile.flag("description_auto") == true,
            )
        }

    fun orchestration(json: JsonElement): Orchestration {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        return Orchestration(
            orchestratorProfile = reply.text("orchestrator_profile").orEmpty(),
            defaultAssignee = reply.text("default_assignee").orEmpty(),
            autoDecompose = reply.flag("auto_decompose") == true,
            autoPromoteChildren = reply.flag("auto_promote_children") == true,
            resolvedOrchestrator = reply.text("resolved_orchestrator_profile"),
            resolvedDefaultAssignee = reply.text("resolved_default_assignee"),
        )
    }

    fun triage(json: JsonElement): TriageResult {
        val reply = json as? JsonObject ?: JsonObject(emptyMap())
        return TriageResult(
            ok = reply.flag("ok") == true,
            reason = reply.text("reason"),
            newTitle = reply.text("new_title"),
            childIds = (reply["child_ids"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            },
        )
    }

    /** The cursor and touched task ids of an event-stream frame: `{events: [{task_id}], cursor}`. */
    fun frame(json: JsonElement): Pair<Long?, Set<String>> {
        val frame = json as? JsonObject ?: return null to emptySet()
        val ids = (frame["events"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("task_id") as? JsonPrimitive)?.contentOrNull
        }
        return frame.long("cursor") to ids.toSet()
    }

    const val DEFAULT_BOARD = "default"

    /** ANSI escape sequences: CSI (colours, cursor) and OSC (titles, links). */
    private val TERMINAL_CODES = Regex("\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\))")
}

internal fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? =
    (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
