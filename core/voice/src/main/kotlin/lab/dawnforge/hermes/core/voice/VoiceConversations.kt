package lab.dawnforge.hermes.core.voice

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import lab.dawnforge.hermes.core.data.gateway.ConnectionHolds
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.model.DashboardId

/** Makes a chat's voice conversation, with what it needs from the app. */
class VoiceConversations(
    private val context: Context,
    private val speech: HermesSpeech,
    private val readiness: SpeechReadiness,
    private val settings: VoiceSettings,
    private val holds: ConnectionHolds,
) {
    fun create(scope: CoroutineScope, id: DashboardId, profile: String, host: VoiceHost) =
        VoiceConversation(context, speech, readiness, settings, scope, id, profile, host, holds)
}
