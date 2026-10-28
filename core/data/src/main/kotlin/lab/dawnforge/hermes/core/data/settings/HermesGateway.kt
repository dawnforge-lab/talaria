package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Hermes' gateway (the process serving messaging platforms and scheduled jobs), from `/api/status`. */
data class GatewayHealth(
    val running: Boolean,
    /** `starting`, `running`, `degraded`, `draining`, `startup_failed`, `stopped`, or null. */
    val state: String?,
    val version: String?,
    /** When the gateway last stamped its heartbeat; a new one after a restart means it's back. */
    val updatedAt: String?,
    val exitReason: String?,
    /** The profiles one shared gateway serves, when there are several. */
    val sharedWith: List<String>,
    val activeAgents: Int,
)

/** A messaging platform the gateway connects to (`/api/messaging/platforms`). */
data class Connector(val id: String, val name: String, val state: String, val error: String?, val enabled: Boolean) {
    val health: ConnectorHealth get() = when (state.lowercase()) {
        "connected", "running", "ready", "online" -> ConnectorHealth.Up
        "connecting", "starting", "retrying", "pending_restart" -> ConnectorHealth.Coming
        else -> ConnectorHealth.Down
    }
}

enum class ConnectorHealth { Up, Coming, Down }

/** The log files Hermes serves (`GET /api/logs?file=`), the gateway's first. */
enum class LogFile(val key: String) { Gateway("gateway"), Agent("agent"), Errors("errors") }

/** A minimum level for the log lines, or every line. */
enum class LogLevel(val key: String) { All("ALL"), Debug("DEBUG"), Info("INFO"), Warning("WARNING"), Error("ERROR") }

/** The part of Hermes a log line came from, or every part. */
enum class LogComponent(val key: String) {
    All("all"),
    Gateway("gateway"),
    Agent("agent"),
    Tools("tools"),
    Cli("cli"),
    Cron("cron"),
}

/** How a background action Hermes started is going (`/api/actions/{name}/status`). */
data class ActionStatus(val running: Boolean, val exitCode: Int?, val lines: List<String>) {
    val succeeded: Boolean get() = !running && exitCode == 0

    /** Why it failed: its last line that isn't one of Hermes' `===` banners. */
    val reason: String? get() = lines.lastOrNull { it.isNotBlank() && !it.trimStart().startsWith("===") }?.trim()
}

/** Hermes' gateway as the dashboard reports it: its health, its connectors and its logs. */
class GatewayStatusRepository(private val requests: DashboardRequests) {
    suspend fun health(id: DashboardId): NetResult<GatewayHealth> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, "/api/status").map { (it as? JsonObject ?: EMPTY).toHealth() }
    }

    /** The profile's platforms that are set up. */
    suspend fun connectors(id: DashboardId, profile: String): NetResult<List<Connector>> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/messaging/platforms", query = mapOf("profile" to profile)).map { reply ->
                ((reply as? JsonObject)?.get("platforms") as? JsonArray).orEmpty().mapNotNull { row ->
                    val platform = row as? JsonObject ?: return@mapNotNull null
                    if (platform.flag("configured") == false) return@mapNotNull null
                    val id = platform.text("id") ?: platform.text("name") ?: return@mapNotNull null
                    Connector(
                        id = id,
                        name = platform.text("name") ?: platform.text("platform") ?: id,
                        state = platform.text("state") ?: platform.text("status") ?: "unknown",
                        error = platform.text("error_message") ?: platform.text("error"),
                        enabled = platform.flag("enabled") != false,
                    )
                }
            }
        }

    /** The last [lines] lines of [file] (Hermes keeps their line ends), newest last. */
    suspend fun logs(
        id: DashboardId,
        file: LogFile,
        level: LogLevel,
        component: LogComponent,
        search: String?,
        lines: Int = LOG_LINES,
    ): NetResult<List<String>> = requests.run(id) { url, jar, cf ->
        val query = mapOf(
            "file" to file.key,
            "lines" to lines.toString(),
            "level" to level.key.takeUnless { level == LogLevel.All },
            "component" to component.key,
            "search" to search?.trim()?.takeIf(String::isNotEmpty),
        )
        get(url, jar, cf, "/api/logs", query = query).map { reply ->
            ((reply as? JsonObject)?.get("lines") as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trimEnd('\n', '\r')
            }
        }
    }

    /**
     * Asks Hermes to restart its gateway (`POST /api/gateway/restart`). Hermes runs
     * `hermes gateway restart` and answers at once; a call soon after another joins it. Null when
     * this Hermes can't restart it from the dashboard.
     */
    suspend fun restart(id: DashboardId): NetResult<Unit?> = when (
        val result = requests.run(id) { url, jar, cf -> post(url, jar, cf, "/api/gateway/restart", EMPTY) }
    ) {
        is NetResult.Success -> NetResult.Success(Unit)
        is NetResult.Failure -> if (result.failure.unsupported()) NetResult.Success(null) else result
    }

    /** The restart's progress; null once Hermes no longer has it. */
    suspend fun restartAction(id: DashboardId): NetResult<ActionStatus?> = action(id, "gateway-restart", lines = 20)

    /**
     * A background action Hermes runs (`/api/actions/{name}/status`): a restart, a skill install.
     * Null once Hermes no longer knows it (it keeps them in memory only).
     */
    suspend fun action(id: DashboardId, name: String, lines: Int = 200): NetResult<ActionStatus?> = when (
        val result = requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/actions", listOf(name, "status"), mapOf("lines" to lines.toString()))
        }
    ) {
        is NetResult.Success -> NetResult.Success(
            (result.value as? JsonObject)?.let { action ->
                ActionStatus(
                    running = action.flag("running") == true,
                    exitCode = (action["exit_code"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.intOrNull,
                    lines = (action["lines"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.content?.trimEnd('\n', '\r')
                    },
                )
            },
        )

        is NetResult.Failure -> if (result.failure is ConnectionFailure.NotFound) NetResult.Success(null) else result
    }

    private fun ConnectionFailure.unsupported(): Boolean = when (this) {
        is ConnectionFailure.NotFound -> true
        is ConnectionFailure.UnexpectedResponse -> status == 405 || status == 501
        else -> false
    }

    private companion object {
        const val LOG_LINES = 200
        val EMPTY = JsonObject(emptyMap())
    }
}

internal fun JsonObject.toHealth() = GatewayHealth(
    running = flag("gateway_running") == true,
    state = text("gateway_state"),
    version = text("version"),
    updatedAt = text("gateway_updated_at"),
    exitReason = text("gateway_exit_reason"),
    sharedWith = (get("gateway_shared_with") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
    activeAgents = (get("active_agents") as? JsonPrimitive)?.intOrNull ?: 0,
)

/** Where a gateway restart stands, as the phone tells it. */
sealed interface RestartStage {
    data object Asking : RestartStage

    /** Waiting for running tasks to finish before stopping. */
    data object Draining : RestartStage

    data object Stopping : RestartStage

    data object Starting : RestartStage

    data class Done(val sharedWith: List<String>) : RestartStage

    /** `hermes gateway restart` failed; [line] is its last word. */
    data class Failed(val line: String?) : RestartStage

    data object DidntStart : RestartStage

    data object NotBackYet : RestartStage

    data object Unsupported : RestartStage

    val finished: Boolean get() = this is Done || this is Failed || this is DidntStart || this is NotBackYet ||
        this is Unsupported
}

/**
 * Follows one restart from what each poll reads, by the iOS app's rules. The gateway is back once
 * it runs in a settled state and one of these shows it really restarted: the restart command
 * finished well, the gateway was seen down, its heartbeat is a new one, or [SETTLE_MS] passed (a
 * gateway without a service never lets the command finish: it becomes the gateway).
 */
class RestartMonitor(private val before: GatewayHealth?, private val startedAtMs: Long) {
    private var seenDown = false
    private var exitedAtMs: Long? = null
    private var exitedWell = false

    /** The newest line of the command's log worth showing. */
    var lastLine: String? = null
        private set

    fun next(nowMs: Long, action: ActionStatus?, health: GatewayHealth?): RestartStage {
        action?.lines?.lastOrNull { it.isNotBlank() && !it.trimStart().startsWith("===") }?.let {
            lastLine = it.trim().take(MAX_LINE)
        }
        if (action != null && !action.running && action.exitCode != null && exitedAtMs == null) {
            exitedAtMs = nowMs
            if (action.exitCode != 0) return RestartStage.Failed(lastLine)
            exitedWell = true
        }
        if (health != null && (!health.running || health.state in DOWN_STATES)) seenDown = true

        val settled = health != null && health.running && health.state !in UNSETTLED_STATES
        val renewed = health?.updatedAt != null && health.updatedAt != before?.updatedAt
        if (settled && (exitedWell || seenDown || renewed || nowMs - startedAtMs >= SETTLE_MS)) {
            return RestartStage.Done(health.sharedWith.takeIf { it.size >= 2 }.orEmpty())
        }

        val exited = exitedAtMs
        if ((exited != null && nowMs - exited >= AFTER_EXIT_MS) || nowMs - startedAtMs >= OVERALL_MS) {
            return if (health?.state == "startup_failed") RestartStage.DidntStart else RestartStage.NotBackYet
        }
        return when {
            health?.state == "draining" -> RestartStage.Draining
            seenDown || health?.state == "starting" -> RestartStage.Starting
            action?.running == true || exitedWell -> RestartStage.Stopping
            else -> RestartStage.Asking
        }
    }

    companion object {
        /** How often a restart is polled. */
        const val POLL_MS = 1_500L
        const val SETTLE_MS = 30_000L
        const val AFTER_EXIT_MS = 90_000L
        const val OVERALL_MS = 600_000L
        private const val MAX_LINE = 240
        private val DOWN_STATES = setOf("stopped", "startup_failed")
        private val UNSETTLED_STATES = setOf("starting", "stopped", "startup_failed", "draining")
    }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
