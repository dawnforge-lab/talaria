package lab.dawnforge.hermes.core.data.voice

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import lab.dawnforge.hermes.core.data.settings.BackendStatus
import lab.dawnforge.hermes.core.data.settings.CapabilitiesRepository
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult

/** Whether Hermes can listen and speak for a profile, and with what. */
data class HermesVoice(
    /** Speech-to-text is on and its provider is set up. */
    val canListen: Boolean,
    /** The text-to-speech provider is set up. */
    val canSpeak: Boolean,
    /** The text-to-speech provider's name, as Hermes lists it. */
    val speaker: String?,
) {
    /**
     * Microsoft Edge's free voice: the phone's own voice is used instead unless the user picks
     * Hermes, as on iOS (Edge sends the text to Microsoft and is often refused).
     */
    val speakerIsEdge: Boolean get() = speaker?.contains("edge", ignoreCase = true) == true

    companion object {
        val NONE = HermesVoice(canListen = false, canSpeak = false, speaker = null)
    }
}

/** Reads, and remembers for a few minutes, what Hermes' speech toolsets can do. */
class SpeechReadiness(
    private val capabilities: CapabilitiesRepository,
    private val settings: HermesSettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Known(val voice: HermesVoice, val at: Long)

    private val known = ConcurrentHashMap<Pair<DashboardId, String>, Known>()

    suspend fun of(id: DashboardId, profile: String, fresh: Boolean = false): HermesVoice {
        val key = id to profile
        known[key]?.takeIf { !fresh && clock() - it.at < TTL_MS }?.let { return it.voice }
        val listenEnabled = (settings.config(id, profile) as? NetResult.Success)?.value
            ?.let { ((it["stt"] as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull != false } ?: false
        val stt = (capabilities.toolsetConfig(id, profile, "stt") as? NetResult.Success)?.value
        val tts = (capabilities.toolsetConfig(id, profile, "tts") as? NetResult.Success)?.value
        val listener = stt?.backends?.firstOrNull { it.active }
        val speaker = tts?.backends?.firstOrNull { it.active }
        val voice = HermesVoice(
            canListen = listenEnabled && listener?.status == BackendStatus.Ready,
            canSpeak = speaker?.status == BackendStatus.Ready,
            speaker = speaker?.name,
        )
        known[key] = Known(voice, clock())
        return voice
    }

    /** Something failed: read it again next time. */
    fun forget(id: DashboardId, profile: String) {
        known.remove(id to profile)
    }

    private companion object {
        const val TTL_MS = 5 * 60_000L
    }
}
