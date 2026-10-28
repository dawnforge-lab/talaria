package lab.dawnforge.hermes.core.data.cron

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.model.BlueprintField
import lab.dawnforge.hermes.core.model.BlueprintFieldType
import lab.dawnforge.hermes.core.model.CronBlueprint
import lab.dawnforge.hermes.core.model.CronDraft
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronRun
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DeliveryTarget
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** How "run now" went. */
sealed interface CronTrigger {
    data object Finished : CronTrigger

    /** Still running: Hermes keeps going after the phone stops waiting, and the run shows in its list. */
    data object Running : CronTrigger

    /** It was already running (or another scheduler claimed it). */
    data object AlreadyRunning : CronTrigger
}

/**
 * A profile's scheduled jobs (`/api/cron/jobs`), as the desktop's Scheduled jobs page manages them.
 * Each call names the profile; Hermes finds a job in whichever profile holds it anyway.
 */
class CronRepository(private val requests: DashboardRequests) {
    suspend fun jobs(id: DashboardId, profile: String): NetResult<List<CronJob>> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, PATH, query = mapOf("profile" to profile)).map(CronJson::jobs)
        }

    suspend fun job(id: DashboardId, profile: String, jobId: String): NetResult<CronJob?> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, PATH, listOf(jobId), mapOf("profile" to profile)).map {
                (it as? JsonObject)?.let(CronJson::job)
            }
        }

    suspend fun runs(id: DashboardId, profile: String, jobId: String): NetResult<List<CronRun>> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, PATH, listOf(jobId, "runs"), mapOf("profile" to profile, "limit" to "20"))
                .map(CronJson::runs)
        }

    /** A new job; without a name Hermes names it from its prompt, and without a target it's only saved. */
    suspend fun create(id: DashboardId, profile: String, draft: CronDraft): NetResult<CronJob?> =
        requests.run(id) { url, jar, cloudflare ->
            val body = buildJsonObject {
                draft.name?.let { put("name", it) }
                put("prompt", draft.prompt.orEmpty())
                put("schedule", draft.schedule.orEmpty())
                put("deliver", draft.deliver ?: DeliveryTarget.LOCAL)
            }
            post(url, jar, cloudflare, PATH, body, mapOf("profile" to profile)).map {
                (it as? JsonObject)?.let(CronJson::job)
            }
        }

    /** Changes the draft's non-null fields only. */
    suspend fun update(id: DashboardId, profile: String, jobId: String, draft: CronDraft): NetResult<CronJob?> =
        requests.run(id) { url, jar, cloudflare ->
            val body = buildJsonObject {
                put(
                    "updates",
                    buildJsonObject {
                        draft.name?.let { put("name", it) }
                        draft.prompt?.let { put("prompt", it) }
                        draft.schedule?.let { put("schedule", it) }
                        draft.deliver?.let { put("deliver", it) }
                    },
                )
            }
            put(url, jar, cloudflare, PATH, listOf(jobId), body, mapOf("profile" to profile))
                .map { (it as? JsonObject)?.let(CronJson::job) }
        }

    suspend fun pause(id: DashboardId, profile: String, jobId: String) = action(id, profile, jobId, "pause")

    suspend fun resume(id: DashboardId, profile: String, jobId: String) = action(id, profile, jobId, "resume")

    /**
     * Runs the job now. Hermes answers only when the run is over, which can take minutes; the phone
     * waits its usual time, and a run still going then is [CronTrigger.Running].
     */
    suspend fun trigger(id: DashboardId, profile: String, jobId: String): NetResult<CronTrigger> = when (
        val result = requests.run(id) { url, jar, cloudflare ->
            post(url, jar, cloudflare, "$PATH/$jobId/trigger", EMPTY, mapOf("profile" to profile))
        }
    ) {
        is NetResult.Success -> NetResult.Success(CronTrigger.Finished)

        is NetResult.Failure -> when (val failure = result.failure) {
            ConnectionFailure.TimedOut -> NetResult.Success(CronTrigger.Running)

            is ConnectionFailure.UnexpectedResponse ->
                if (failure.status == CONFLICT) NetResult.Success(CronTrigger.AlreadyRunning) else result

            else -> result
        }
    }

    /** Where results can go: save only, then each connected platform and each profile's bot chat. */
    suspend fun deliveryTargets(id: DashboardId, profile: String): NetResult<List<DeliveryTarget>> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, "/api/cron/delivery-targets", query = mapOf("profile" to profile))
                .map(CronJson::targets)
        }

    /** The ready-made jobs this Hermes offers, built in and from plugins. */
    suspend fun blueprints(id: DashboardId, profile: String): NetResult<List<CronBlueprint>> =
        requests.run(id) { url, jar, cloudflare ->
            get(url, jar, cloudflare, BLUEPRINTS, query = mapOf("profile" to profile)).map(CronJson::blueprints)
        }

    /**
     * A job from [blueprint] with [values] for its blanks (left out ones take their defaults).
     * Hermes answers 422 for a value it won't take, with what's wrong.
     */
    suspend fun instantiate(
        id: DashboardId,
        profile: String,
        blueprint: String,
        values: Map<String, String>,
    ): NetResult<CronJob?> = requests.run(id) { url, jar, cloudflare ->
        val body = buildJsonObject {
            put("blueprint", blueprint)
            put("values", buildJsonObject { values.forEach { (name, value) -> put(name, value) } })
        }
        post(url, jar, cloudflare, BLUEPRINTS, body, mapOf("profile" to profile), listOf("instantiate"))
            .map { (it as? JsonObject)?.let(CronJson::job) }
    }

    suspend fun delete(id: DashboardId, profile: String, jobId: String): NetResult<Unit> =
        requests.run(id) { url, jar, cloudflare ->
            delete(url, jar, cloudflare, PATH, listOf(jobId), mapOf("profile" to profile)).map { }
        }

    private suspend fun action(id: DashboardId, profile: String, jobId: String, verb: String): NetResult<CronJob?> =
        requests.run(id) { url, jar, cloudflare ->
            post(url, jar, cloudflare, "$PATH/$jobId/$verb", EMPTY, mapOf("profile" to profile))
                .map { (it as? JsonObject)?.let(CronJson::job) }
        }

    private companion object {
        const val PATH = "/api/cron/jobs"
        const val BLUEPRINTS = "/api/cron/blueprints"
        const val CONFLICT = 409
        val EMPTY = JsonObject(emptyMap())
    }
}

/** Hermes' cron JSON, read leniently: a field it leaves out is left out here. */
internal object CronJson {
    fun jobs(element: JsonElement): List<CronJob> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::job) }

    fun job(row: JsonObject): CronJob? {
        val id = row.text("id") ?: return null
        val schedule = row["schedule"] as? JsonObject
        return CronJob(
            id = id,
            name = row.text("name") ?: id,
            prompt = row.text("prompt").orEmpty(),
            schedule = row.text("schedule_display") ?: schedule?.text("display") ?: schedule?.text("expr") ?: "",
            scheduleInput = when (schedule?.text("kind")) {
                "cron" -> schedule.text("expr")
                "interval" -> schedule.number("minutes")?.let { "every ${it}m" }
                "once" -> schedule.text("run_at")
                else -> null
            } ?: schedule?.text("display").orEmpty(),
            scheduleKind = schedule?.text("kind"),
            state = when (row.text("state")) {
                "paused" -> CronState.Paused
                "completed" -> CronState.Completed
                "error" -> CronState.Error
                else -> if (row.flag("enabled") == false) CronState.Paused else CronState.Scheduled
            },
            nextRunAtMs = row.text("next_run_at")?.let(::isoMs),
            lastRunAtMs = row.text("last_run_at")?.let(::isoMs),
            lastStatus = row.text("last_status"),
            lastError = row.text("last_error"),
            deliver = row.text("deliver") ?: "local",
            model = row.text("model"),
            isScript = row.flag("no_agent") == true,
        )
    }

    fun runs(element: JsonElement): List<CronRun> {
        val rows = ((element as? JsonObject)?.get("runs") as? JsonArray).orEmpty()
        return rows.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = row.text("id") ?: return@mapNotNull null
            CronRun(
                id = id,
                title = row.text("title") ?: row.text("preview") ?: id,
                startedAtMs = row.seconds("started_at"),
                endedAtMs = row.seconds("ended_at"),
                profile = row.text("profile"),
                // A script's output is a row of text, not a stored chat.
                opensAsChat = row.text("source") != "cron_output" && !id.startsWith("cron_output:"),
            )
        }
    }

    fun targets(element: JsonElement): List<DeliveryTarget> {
        val rows = ((element as? JsonObject)?.get("targets") as? JsonArray).orEmpty()
        val targets = rows.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val id = row.text("id") ?: return@mapNotNull null
            DeliveryTarget(id, row.text("name") ?: id, row.flag("home_target_set") != false)
        }
        // Saving only is always there, first.
        return if (targets.any { it.id == DeliveryTarget.LOCAL }) {
            targets.sortedBy { it.id != DeliveryTarget.LOCAL }
        } else {
            listOf(DeliveryTarget(DeliveryTarget.LOCAL, "Local (save only)", hasHome = true)) + targets
        }
    }

    fun blueprints(element: JsonElement): List<CronBlueprint> {
        val rows = ((element as? JsonObject)?.get("blueprints") as? JsonArray).orEmpty()
        return rows.mapNotNull { entry ->
            val row = entry as? JsonObject ?: return@mapNotNull null
            val key = row.text("key") ?: return@mapNotNull null
            val fields = (row["fields"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::field) }
            CronBlueprint(
                key = key,
                title = row.text("title") ?: key,
                description = row.text("description").orEmpty(),
                category = row.text("category").orEmpty(),
                scheduleHuman = row.text("scheduleHuman"),
                // A name twice would fill one blank twice.
                fields = fields.distinctBy { it.name },
            )
        }
    }

    private fun field(row: JsonObject): BlueprintField? {
        val name = row.text("name") ?: return null
        val type = when (row.text("type")) {
            "time" -> BlueprintFieldType.Time
            "enum" -> BlueprintFieldType.Choice
            "weekdays" -> BlueprintFieldType.Weekdays
            "text" -> BlueprintFieldType.Text
            else -> return null
        }
        return BlueprintField(
            name = name,
            type = type,
            label = row.text("label") ?: name,
            default = row.text("default"),
            options = (row["options"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            },
            optional = row.flag("optional") == true,
            help = row.text("help"),
        )
    }

    private fun isoMs(value: String): Long? = try {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun JsonObject.seconds(key: String): Long? = (this[key] as? JsonPrimitive)?.doubleOrNull?.let {
        (it * 1000).toLong()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.doubleOrNull?.toLong()

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

private inline fun <T, R> NetResult<T>.map(transform: (T) -> R): NetResult<R> = when (this) {
    is NetResult.Success -> NetResult.Success(transform(value))
    is NetResult.Failure -> this
}
