package lab.dawnforge.hermes.core.data.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What the host's push plugin (`talaria_push`) reports about itself (`/capabilities`): the version
 * on disk, the one the dashboard runs, and what it serves.
 */
data class PluginStatus(
    val version: String?,
    /** The version this dashboard process imported; null on plugins before 0.24. */
    val loadedVersion: String?,
    /** A newer plugin is on disk than the dashboard runs. */
    val restartNeeded: Boolean,
    /** Changes each time the dashboard process starts. */
    val bootId: String?,
    val capabilities: Set<String>,
) {
    fun reports(capability: String): Boolean = capability in capabilities

    /** The version that runs; null when a restart is due and the plugin can't say which one runs. */
    val runningVersion: String? get() = loadedVersion ?: version.takeIf { !restartNeeded }
}

/** How the plugin stands on a host. */
sealed interface PluginState {
    data class Reported(val status: PluginStatus) : PluginState

    /** No capabilities route: the plugin is missing, older than 0.4, or not served. */
    data class Absent(val hosted: HostedPlugin) : PluginState
}

/** Whether Hermes has the plugin at all, from its plugin list. */
enum class HostedPlugin { Missing, Old, NotServed, Unknown }

/** What taking a chat over came to (`/sessions/takeover`). */
enum class TakeoverStatus {
    /** Nobody held it: send. */
    Free,

    /** The other app's claim was dropped: send. */
    TakenOver,

    /** The other app is replying in it: ask again shortly. */
    Busy,

    /** This dashboard itself holds it (another phone, or the web chat): nothing to take. */
    SameHost,
}

/** When Hermes Desktop or the web dashboard last had a chat on screen. */
data class DesktopView(
    /** Host epoch seconds. */
    val seenThrough: Double,
    /** On screen there right now. */
    val open: Boolean,
)

data class DesktopViews(val observing: Boolean, val views: Map<String, DesktopView>)

/** Which senders' messages get a "reply ready" notification, by source. */
data class ReplySources(val app: Boolean, val desktop: Boolean, val messaging: Boolean, val terminal: Boolean) {
    fun with(source: ReplySource, notify: Boolean): ReplySources = when (source) {
        ReplySource.App -> copy(app = notify)
        ReplySource.Desktop -> copy(desktop = notify)
        ReplySource.Messaging -> copy(messaging = notify)
        ReplySource.Terminal -> copy(terminal = notify)
    }

    fun of(source: ReplySource): Boolean = when (source) {
        ReplySource.App -> app
        ReplySource.Desktop -> desktop
        ReplySource.Messaging -> messaging
        ReplySource.Terminal -> terminal
    }
}

/** The plugin's names for them: this app's turns count as `talaria` (it sends `X-Talaria-Client`). */
enum class ReplySource(val key: String) {
    App("talaria"),
    Desktop("desktop"),
    Messaging("messaging"),
    Terminal("terminal"),
}

object PushPluginJson {
    fun status(json: JsonElement): PluginStatus? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        val capabilities = reply["capabilities"] as? JsonArray ?: return null
        return PluginStatus(
            version = reply.text("version"),
            loadedVersion = reply.text("loaded_version"),
            restartNeeded = reply.flag("restart_needed") == true,
            bootId = reply.text("boot_id"),
            capabilities = capabilities.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull },
        )
    }

    fun takeover(json: JsonElement): TakeoverStatus? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        return when (reply.text("status")) {
            "free" -> TakeoverStatus.Free
            "taken_over" -> TakeoverStatus.TakenOver
            "busy" -> TakeoverStatus.Busy
            "same_host" -> TakeoverStatus.SameHost
            else -> null
        }
    }

    fun desktopViews(json: JsonElement): DesktopViews? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        val views = (reply["views"] as? JsonObject).orEmpty().mapNotNull { (id, value) ->
            val view = value as? JsonObject ?: return@mapNotNull null
            val seen = (view["seen_through"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            id to DesktopView(seen, view.flag("open") == true)
        }.toMap()
        return DesktopViews(reply.flag("observing") == true, views)
    }

    fun muted(json: JsonElement): Set<String>? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        val muted = reply["muted"] as? JsonArray ?: return emptySet()
        return muted.mapNotNullTo(LinkedHashSet()) { (it as? JsonPrimitive)?.contentOrNull }
    }

    /** A source the host doesn't name notifies, as it does on the host. */
    fun replySources(json: JsonElement): ReplySources? {
        val reply = json as? JsonObject ?: return null
        if (reply.flag("ok") != true) return null
        val sources = reply["sources"] as? JsonObject ?: return null
        fun on(source: ReplySource) = (sources[source.key] as? JsonPrimitive)?.booleanOrNull ?: true
        return ReplySources(
            app = on(ReplySource.App),
            desktop = on(ReplySource.Desktop),
            messaging = on(ReplySource.Messaging),
            terminal = on(ReplySource.Terminal),
        )
    }

    /**
     * Who holds a chat, from Hermes' refusal: "Details: session <id> opened by <surface> 5m ago.".
     * Answers the stored id and the surface (`desktop`, `cli`, `tui`, …), either null when missing.
     */
    fun ownerOf(refusal: String): Pair<String?, String?> {
        val words = refusal.substringAfter("session ", "").split(' ').filter(String::isNotBlank)
        val id = words.firstOrNull()?.trimEnd('.', ',')
        val opened = words.getOrNull(1) == "opened" && words.getOrNull(2) == "by"
        val surface = words.getOrNull(3)?.trimEnd('.')?.takeIf { opened }
        return id to surface
    }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

    private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
