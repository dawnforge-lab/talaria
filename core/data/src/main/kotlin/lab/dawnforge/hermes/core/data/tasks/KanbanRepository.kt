package lab.dawnforge.hermes.core.data.tasks

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** A new task, as the composer fills it in. */
data class NewTask(
    val title: String,
    val body: String = "",
    /** A profile; null leaves it parked (it won't run until someone assigns it). */
    val assignee: String? = null,
    val priority: Int = 0,
    /** The lane it should land in; Hermes puts a new task in triage, todo or ready itself. */
    val lane: TaskStatus = TaskStatus.Todo,
    val parent: String? = null,
    /** `scratch` (a throwaway folder), `worktree` or `dir`; null takes the board's default. */
    val workspaceKind: String? = null,
    /** For a worktree or folder: where; blank takes the board's default. */
    val workspacePath: String = "",
    val skills: List<String> = emptyList(),
    /** Goal mode: the worker keeps going until the goal is met, up to [goalMaxTurns] (null: no limit). */
    val goalMode: Boolean = false,
    val goalMaxTurns: Int? = null,
    val model: ModelOverride? = null,
)

/** A model for one task instead of its profile's: [provider] and [reasoning] are optional. */
data class ModelOverride(val model: String, val provider: String? = null, val reasoning: String? = null)

/** How each task of a bulk change went: Hermes' reason, or a failure to reach it. */
data class BulkResult(val id: String, val ok: Boolean, val error: String?, val failure: ConnectionFailure? = null)

/** A change to several tasks at once (`/tasks/bulk`); only the set fields change. */
data class BulkChange(
    val status: TaskStatus? = null,
    /** A profile, or "" to unassign; null leaves who runs them as it is. */
    val assignee: String? = null,
    val priority: Int? = null,
    val archive: Boolean = false,
)

/** A provider's models Hermes offers for task overrides (`/model-options`). */
data class ModelOption(val provider: String, val label: String, val models: List<String>)

/** The fields of a task to change; null leaves one as it is. */
data class TaskPatch(
    val title: String? = null,
    val body: String? = null,
    val status: TaskStatus? = null,
    val priority: Int? = null,
)

/**
 * Hermes' task board (the bundled kanban plugin, `/api/plugins/kanban`). The board is shared by
 * every profile, so no call names one; each names its board.
 */
class KanbanRepository(private val requests: DashboardRequests) {
    suspend fun boards(id: DashboardId): NetResult<Boards> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, listOf("boards")).map(KanbanJson::boards)
    }

    suspend fun board(id: DashboardId, board: String, includeArchived: Boolean = false): NetResult<BoardView> =
        requests.run(id) { url, jar, cf ->
            val query = mapOf("board" to board, "include_archived" to if (includeArchived) "true" else null)
            get(url, jar, cf, API, listOf("board"), query).map(KanbanJson::board)
        }

    suspend fun task(id: DashboardId, board: String, taskId: String): NetResult<TaskDetail> = requests.run(id) {
            url,
            jar,
            cf,
        ->
        when (val read = get(url, jar, cf, API, listOf("tasks", taskId), mapOf("board" to board))) {
            is NetResult.Failure -> read

            is NetResult.Success -> KanbanJson.detail(read.value)?.let { NetResult.Success(it) }
                ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "no task in the reply"))
        }
    }

    /**
     * Creates [task]. Hermes lands a new task in triage, or else in ready (todo while a parent is
     * open); for Blocked or Done it is moved there right after, as iOS does. Answers the task as it
     * ended up.
     */
    suspend fun create(id: DashboardId, board: String, task: NewTask): NetResult<KanbanTask> {
        val created = requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("title", task.title.trim())
                task.body.trim().takeIf(String::isNotEmpty)?.let { put("body", it) }
                task.assignee?.let { put("assignee", it) }
                put("priority", task.priority)
                put("triage", task.lane == TaskStatus.Triage)
                task.parent?.let { put("parents", JsonArray(listOf(JsonPrimitive(it)))) }
                task.workspaceKind?.let { kind ->
                    put("workspace_kind", kind)
                    task.workspacePath.trim().takeIf {
                        kind != "scratch" && it.isNotEmpty()
                    }?.let { put("workspace_path", it) }
                }
                task.skills.map(String::trim).filter(String::isNotEmpty).distinct().takeIf {
                    it.isNotEmpty()
                }?.let { skills ->
                    put("skills", JsonArray(skills.map(::JsonPrimitive)))
                }
                put("goal_mode", task.goalMode)
                if (task.goalMode) task.goalMaxTurns?.let { put("goal_max_turns", it) }
                task.model?.let { model ->
                    put("model_override", model.model)
                    model.provider?.let { put("provider_override", it) }
                    model.reasoning?.let { put("reasoning_effort", it) }
                }
            }
            post(url, jar, cf, API, body, mapOf("board" to board), listOf("tasks")).map { reply ->
                (reply as? JsonObject)?.get("task")?.let { it as? JsonObject }?.let(KanbanJson::task)
            }
        }
        val made = when (created) {
            is NetResult.Failure -> return created

            is NetResult.Success ->
                created.value
                    ?: return NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "no task in the reply"))
        }
        // To do is Hermes' own word for waiting on a parent: a task without open ones is promoted to
        // Ready on the dispatcher's next tick, so moving it there would not hold.
        if (task.lane == TaskStatus.Triage || task.lane == TaskStatus.Todo || made.status == task.lane) {
            return NetResult.Success(made)
        }
        return update(id, board, made.id, TaskPatch(status = task.lane))
    }

    suspend fun update(id: DashboardId, board: String, taskId: String, patch: TaskPatch): NetResult<KanbanTask> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                patch.title?.let { put("title", it.trim()) }
                patch.body?.let { put("body", it) }
                patch.status?.let { put("status", it.key) }
                patch.priority?.let { put("priority", it) }
            }
            patch(url, jar, cf, API, listOf("tasks", taskId), body, mapOf("board" to board)).map { reply ->
                (reply as? JsonObject)?.get("task")?.let { it as? JsonObject }?.let(KanbanJson::task)
            }.let { result ->
                when (result) {
                    is NetResult.Failure -> result

                    is NetResult.Success -> result.value?.let { NetResult.Success(it) }
                        ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "no task in the reply"))
                }
            }
        }

    /** Sets the task's own model, or (null) goes back to its profile's. */
    suspend fun setModel(id: DashboardId, board: String, taskId: String, model: ModelOverride?): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                if (model == null) {
                    put("clear_model_override", true)
                    put("clear_reasoning_effort", true)
                } else {
                    put("model_override", model.model)
                    model.provider?.let { put("provider_override", it) }
                    if (model.reasoning !=
                        null
                    ) {
                        put("reasoning_effort", model.reasoning)
                    } else {
                        put("clear_reasoning_effort", true)
                    }
                }
            }
            patch(url, jar, cf, API, listOf("tasks", taskId), body, mapOf("board" to board)).map { }
        }

    /** Changes several tasks at once; each one succeeds or fails on its own. */
    suspend fun bulk(
        id: DashboardId,
        board: String,
        ids: List<String>,
        change: BulkChange,
    ): NetResult<List<BulkResult>> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("ids", JsonArray(ids.map(::JsonPrimitive)))
            change.status?.let { put("status", it.key) }
            change.assignee?.let {
                put("assignee", it)
                put("reclaim_first", true)
            }
            change.priority?.let { put("priority", it) }
            if (change.archive) put("archive", true)
        }
        post(url, jar, cf, API, body, mapOf("board" to board), listOf("tasks", "bulk")).map { reply ->
            val results = ((reply as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { row ->
                val result = row as? JsonObject ?: return@mapNotNull null
                BulkResult(
                    id = result.text("id") ?: return@mapNotNull null,
                    ok = (result["ok"] as? JsonPrimitive)?.booleanOrNull == true,
                    error = result.text("error"),
                )
            }
            // A task Hermes said nothing about did not change.
            ids.map { taskId -> results.firstOrNull { it.id == taskId } ?: BulkResult(taskId, false, null) }
        }
    }

    /** Deletes several tasks, a few at a time (there is no bulk delete). */
    suspend fun deleteAll(id: DashboardId, board: String, ids: List<String>): List<BulkResult> = coroutineScope {
        ids.chunked(DELETE_BATCH).flatMap { batch ->
            batch.map { taskId ->
                async {
                    when (val result = delete(id, board, taskId)) {
                        is NetResult.Success -> BulkResult(taskId, true, null)
                        is NetResult.Failure -> BulkResult(taskId, false, null, result.failure)
                    }
                }
            }.awaitAll()
        }
    }

    /** Creates a board; its [slug] is fixed for good. Answers it (an existing slug answers that board). */
    suspend fun createBoard(id: DashboardId, slug: String, name: String, description: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("slug", slug)
                name.trim().takeIf(String::isNotEmpty)?.let { put("name", it) }
                description.trim().takeIf(String::isNotEmpty)?.let { put("description", it) }
                put("switch", false)
            }
            post(url, jar, cf, API, body, segments = listOf("boards")).map { }
        }

    /** Renames a board or changes its description ("" clears it). */
    suspend fun updateBoard(id: DashboardId, slug: String, name: String, description: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("name", name.trim())
                put("description", description.trim())
            }
            patch(url, jar, cf, API, listOf("boards", slug), body).map { }
        }

    /** Archives a board: it leaves the list, its tasks are kept. The default board can't be. */
    suspend fun archiveBoard(id: DashboardId, slug: String): NetResult<Unit> = requests.run(id) { url, jar, cf ->
        delete(url, jar, cf, API, listOf("boards", slug)).map { }
    }

    /** Changes the set fields of how Hermes routes tasks; "" means its default. */
    suspend fun setOrchestration(
        id: DashboardId,
        orchestrator: String? = null,
        defaultAssignee: String? = null,
        autoDecompose: Boolean? = null,
        autoPromoteChildren: Boolean? = null,
    ): NetResult<Orchestration> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            orchestrator?.let { put("orchestrator_profile", it) }
            defaultAssignee?.let { put("default_assignee", it) }
            autoDecompose?.let { put("auto_decompose", it) }
            autoPromoteChildren?.let { put("auto_promote_children", it) }
        }
        put(url, jar, cf, API, listOf("orchestration"), body).map(KanbanJson::orchestration)
    }

    /** Sets what a profile is for, which Hermes reads to route split tasks ("" clears it). */
    suspend fun describeProfile(id: DashboardId, name: String, description: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            patch(
                url,
                jar,
                cf,
                API,
                listOf("profiles", name),
                buildJsonObject {
                    put("description", description.trim())
                },
            ).map { }
        }

    /** Has Hermes write a profile's description from its setup; answers it (saved already). */
    suspend fun describeProfileAuto(id: DashboardId, name: String): NetResult<TriageResult> =
        requests.run(id) { url, jar, cf ->
            post(
                url,
                jar,
                cf,
                API,
                buildJsonObject {
                    put("overwrite", true)
                },
                segments = listOf("profiles", name, "describe-auto"),
            )
                .map { reply ->
                    val answer = reply as? JsonObject
                    TriageResult(
                        ok = (answer?.get("ok") as? JsonPrimitive)?.booleanOrNull == true,
                        reason = answer?.text("reason"),
                        newTitle = answer?.text("description"),
                        childIds = emptyList(),
                    )
                }
        }

    /** The models a task can be given instead of its profile's. */
    suspend fun modelOptions(id: DashboardId): NetResult<List<ModelOption>> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, listOf("model-options")).map { reply ->
            ((reply as? JsonObject)?.get("providers") as? JsonArray).orEmpty().mapNotNull { row ->
                val provider = row as? JsonObject ?: return@mapNotNull null
                val slug = provider.text("slug") ?: return@mapNotNull null
                val models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
                if (models.isEmpty()) null else ModelOption(slug, provider.text("label") ?: slug, models)
            }
        }
    }

    /** Deletes a task for good, with its comments and history. */
    suspend fun delete(id: DashboardId, board: String, taskId: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            delete(url, jar, cf, API, listOf("tasks", taskId), mapOf("board" to board)).map { }
        }

    suspend fun comment(id: DashboardId, board: String, taskId: String, body: String): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val comment = buildJsonObject {
                put("author", AUTHOR)
                put("body", body.trim())
            }
            post(url, jar, cf, API, comment, mapOf("board" to board), listOf("tasks", taskId, "comments")).map { }
        }

    /**
     * Gives the task to [profile] (null: nobody, so it is parked), stopping its worker first if it
     * runs.
     */
    suspend fun reassign(id: DashboardId, board: String, taskId: String, profile: String?): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("profile", profile?.let(::JsonPrimitive) ?: JsonNull)
                put("reclaim_first", true)
            }
            post(url, jar, cf, API, body, mapOf("board" to board), listOf("tasks", taskId, "reassign")).map { }
        }

    /** Stops the task's worker and puts it back in the queue. */
    suspend fun reclaim(id: DashboardId, board: String, taskId: String, reason: String? = null): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject { reason?.let { put("reason", it) } }
            post(url, jar, cf, API, body, mapOf("board" to board), listOf("tasks", taskId, "reclaim")).map { }
        }

    /** Has Hermes write a triage task up properly ([decompose] false) or split it into tasks. */
    suspend fun triage(id: DashboardId, board: String, taskId: String, decompose: Boolean): NetResult<TriageResult> =
        requests.run(id) { url, jar, cf ->
            val action = if (decompose) "decompose" else "specify"
            post(
                url,
                jar,
                cf,
                API,
                buildJsonObject {
                    put("author", AUTHOR)
                },
                mapOf("board" to board),
                listOf("tasks", taskId, action),
            )
                .map(KanbanJson::triage)
        }

    /** The end of the task's worker log, up to [tailBytes]. */
    suspend fun log(id: DashboardId, board: String, taskId: String, tailBytes: Int = LOG_TAIL): NetResult<WorkerLog> =
        requests.run(id) { url, jar, cf ->
            get(
                url,
                jar,
                cf,
                API,
                listOf("tasks", taskId, "log"),
                mapOf(
                    "board" to board,
                    "tail" to tailBytes.toString(),
                ),
            )
                .map(KanbanJson::log)
        }

    /** Asks the dispatcher to look at the board now rather than on its next tick. */
    suspend fun nudge(id: DashboardId, board: String): NetResult<Unit> = requests.run(id) { url, jar, cf ->
        post(url, jar, cf, API, JsonObject(emptyMap()), mapOf("board" to board), listOf("dispatch")).map { }
    }

    suspend fun profiles(id: DashboardId): NetResult<List<KanbanProfile>> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, listOf("profiles")).map(KanbanJson::profiles)
    }

    suspend fun orchestration(id: DashboardId): NetResult<Orchestration> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, API, listOf("orchestration")).map(KanbanJson::orchestration)
    }

    private companion object {
        const val API = "/api/plugins/kanban"

        /** How the phone signs what it writes on the board. */
        const val AUTHOR = "android"
        const val LOG_TAIL = 65_536
        const val DELETE_BATCH = 8
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
