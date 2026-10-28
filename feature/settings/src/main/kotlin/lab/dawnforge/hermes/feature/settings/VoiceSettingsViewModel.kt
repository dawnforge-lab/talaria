package lab.dawnforge.hermes.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.plugin.HermesChanges
import lab.dawnforge.hermes.core.data.plugin.ReviewItem
import lab.dawnforge.hermes.core.data.settings.DeviceVoicePreferences
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository
import lab.dawnforge.hermes.core.data.settings.VoicePreferences
import lab.dawnforge.hermes.core.data.settings.VoiceReplies
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesVoice
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.voice.OfflineSpeech
import lab.dawnforge.hermes.core.voice.ReadAloud
import lab.dawnforge.hermes.core.voice.VoiceChecks

/** What the phone and Hermes can do for voice, for the checklist. */
data class VoiceReadiness(
    val phoneRecognizes: Boolean = false,
    val phoneSpeaks: Boolean = false,
    val offlineSpeech: OfflineSpeech = OfflineSpeech.Unknown,
    val hermes: HermesVoice? = null,
)

/** Hermes' settings on the voice page: live words, and how spoken turns are answered. */
data class HermesVoiceSettings(
    val liveWords: Boolean? = null,
    val replies: VoiceReplies? = null,
    val models: ModelChoices? = null,
    val saving: Boolean = false,
    val failure: ConnectionFailure? = null,
)

/** The voice page: the active profile's voice settings, the phone's, and Hermes'. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val voice: VoiceSettings,
    private val readiness: SpeechReadiness,
    private val hermesSettings: HermesSettingsRepository,
    private val checks: VoiceChecks,
    private val readAloud: ReadAloud,
    private val changes: HermesChanges,
) : ViewModel() {
    private val place = MutableStateFlow<Pair<DashboardId, String>?>(null)

    val profile: StateFlow<VoicePreferences?> = place.flatMapLatest { at ->
        if (at == null) flowOf(null) else voice.profile(at.first, at.second)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val device: StateFlow<DeviceVoicePreferences?> =
        voice.device.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val profileName: StateFlow<String?> = place.map { it?.second }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _voiceReadiness = MutableStateFlow(VoiceReadiness())
    val voiceReadiness: StateFlow<VoiceReadiness> = _voiceReadiness.asStateFlow()

    private val _hermes = MutableStateFlow(HermesVoiceSettings())
    val hermes: StateFlow<HermesVoiceSettings> = _hermes.asStateFlow()

    /** Changes Hermes asked for that wait for the user's Apply. */
    val review: StateFlow<List<ReviewItem>> = changes.review

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            place.value = id to dashboards.activeProfile(id).first()
            refresh()
        }
        // Opening the voice page shares the settings again, as on iOS.
        changes.sync()
    }

    /** Reads again what the phone and Hermes can do (after setting something up, say). */
    fun refresh() {
        val (id, name) = place.value ?: return
        viewModelScope.launch {
            _voiceReadiness.value = VoiceReadiness(
                phoneRecognizes = checks.canRecognize(),
                phoneSpeaks = checks.canSpeak(),
                offlineSpeech = checks.offlineSpeech(),
                hermes = readiness.of(id, name, fresh = true),
            )
        }
        viewModelScope.launch {
            val config = (hermesSettings.config(id, name) as? NetResult.Success)?.value
            val liveWords = ((config?.get("stt") as? JsonObject)?.get("streaming") as? JsonPrimitive)?.booleanOrNull
            val replies = (hermesSettings.voiceReplies(id, name) as? NetResult.Success)?.value
            val models = config?.let { (hermesSettings.models(id, name, it) as? NetResult.Success)?.value?.choices }
            _hermes.update { it.copy(liveWords = liveWords ?: false, replies = replies, models = models) }
        }
    }

    fun updateProfile(change: (VoicePreferences) -> VoicePreferences) {
        val (id, name) = place.value ?: return
        viewModelScope.launch {
            voice.updateProfile(id, name, change)
            // A setting Hermes can see changed: it hears the new value.
            changes.sync()
        }
    }

    fun updateDevice(change: (DeviceVoicePreferences) -> DeviceVoicePreferences) {
        viewModelScope.launch {
            voice.updateDevice(change)
            changes.sync()
        }
    }

    /** Hermes' live words while the user speaks (`stt.streaming`). */
    fun setLiveWords(on: Boolean) = saveHermes {
        val (id, name) = place.value ?: return@saveHermes null
        hermesSettings.save(id, name, "stt.streaming", JsonPrimitive(on)).also { result ->
            if (result is NetResult.Success) _hermes.update { it.copy(liveWords = on) }
        }
    }

    /** How Hermes answers spoken turns: its model (empty: the chats' model) and reasoning. */
    fun setVoiceReplies(replies: VoiceReplies) = saveHermes {
        val (id, name) = place.value ?: return@saveHermes null
        hermesSettings.setVoiceReplies(id, name, replies).also { result ->
            if (result is NetResult.Success) _hermes.update { it.copy(replies = replies) }
        }
    }

    private fun saveHermes(save: suspend () -> NetResult<Unit>?) {
        viewModelScope.launch {
            _hermes.update { it.copy(saving = true, failure = null) }
            val result = save()
            _hermes.update { it.copy(saving = false, failure = (result as? NetResult.Failure)?.failure) }
        }
    }

    /** Hears how replies sound, in the voice that would speak them. */
    fun testSpeaking(text: String) {
        val (id, name) = place.value ?: return
        readAloud.toggle(VOICE_TEST, text, id, name)
    }

    /** Asks Android to download the phone's language for recognition without the network. */
    fun downloadOfflineSpeech() {
        viewModelScope.launch {
            checks.downloadOfflineSpeech()
            _voiceReadiness.update { it.copy(offlineSpeech = checks.offlineSpeech()) }
        }
    }

    fun resolve(items: List<ReviewItem>, apply: Boolean) = changes.resolve(items, apply)

    private companion object {
        const val VOICE_TEST = "voice-settings-test"
    }
}
