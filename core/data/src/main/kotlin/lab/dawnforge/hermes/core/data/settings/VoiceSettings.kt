package lab.dawnforge.hermes.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.model.DashboardId

/** Who turns dictation into text: the phone's own recognizer, or Hermes' speech-to-text. */
enum class DictationEngine { Phone, Hermes }

/** Who speaks replies: the phone's own voice, or Hermes' text-to-speech. Automatic picks, as on iOS. */
enum class SpeechOutput { Automatic, Phone, Hermes }

/**
 * Whether Hermes may change the phone's settings it's shown (`talaria_settings`), as on iOS:
 * never, after the user's OK each time, or small ones (with an Undo) without asking.
 */
enum class HermesChangesMode(val key: String) {
    Off("off"),
    Ask("ask"),
    Small("small"),
}

/** The phone's voice settings that hold for every dashboard. */
data class DeviceVoicePreferences(
    /** Null picks: the phone when it can recognize speech, else Hermes. */
    val dictationEngine: DictationEngine? = null,
    /** Names and terms the phone's recognizer should expect. */
    val dictationWords: List<String> = emptyList(),
    val readAloudSpeed: Float = 1f,
    /** The screen stays on while a voice conversation is open. */
    val keepScreenAwake: Boolean = false,
    /** What Hermes may change; asking every time by default, as on iOS. */
    val hermesChanges: HermesChangesMode = HermesChangesMode.Ask,
) {
    companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f)
        const val MAX_WORDS = 100
        const val MAX_WORD_CHARS = 60
    }
}

/** Who turns a voice conversation's speech into text; null picks, as for dictation. */
typealias Transcription = DictationEngine

/** The voice settings of one dashboard's profile. */
data class VoicePreferences(
    val speechOutput: SpeechOutput = SpeechOutput.Automatic,
    /** Null picks: the phone when it can recognize speech, else Hermes. */
    val transcription: Transcription? = null,
    /** After Hermes answers, listen again by itself. On by default, as on iOS. */
    val continuousConversation: Boolean = true,
    /** The microphone stays on while the phone is locked. */
    val keepListeningWhenLocked: Boolean = false,
    /** Hermes' replies aren't spoken. */
    val outputMuted: Boolean = false,
    /** Spoken Stop phrases: null follows the built-ins, an empty list turns them off. */
    val stopPhrases: List<String>? = null,
    /** Spoken End phrases: null follows the built-ins, an empty list turns them off. */
    val endPhrases: List<String>? = null,
)

/** Voice settings, as iOS keeps them: some for the phone, the rest per dashboard and profile. */
class VoiceSettings(private val settings: DataStore<Preferences>) : DashboardDataOwner {
    val device: Flow<DeviceVoicePreferences> = settings.data.map { it.device() }.distinctUntilChanged()

    private fun Preferences.device() = DeviceVoicePreferences(
        dictationEngine = this[DICTATION_ENGINE]?.let { runCatching { DictationEngine.valueOf(it) }.getOrNull() },
        dictationWords = this[DICTATION_WORDS].orEmpty().split('\n').filter(String::isNotBlank),
        readAloudSpeed = this[READ_ALOUD_SPEED]?.takeIf { it in DeviceVoicePreferences.SPEEDS } ?: 1f,
        keepScreenAwake = this[KEEP_AWAKE] ?: false,
        hermesChanges =
            HermesChangesMode.entries.firstOrNull { it.key == this[HERMES_CHANGES] } ?: HermesChangesMode.Ask,
    )

    suspend fun updateDevice(change: (DeviceVoicePreferences) -> DeviceVoicePreferences) {
        settings.edit { prefs ->
            val next = change(prefs.device())
            next.dictationEngine?.let { prefs[DICTATION_ENGINE] = it.name } ?: prefs.remove(DICTATION_ENGINE)
            prefs[DICTATION_WORDS] = next.dictationWords
                .map { it.trim().take(DeviceVoicePreferences.MAX_WORD_CHARS) }
                .filter(String::isNotEmpty)
                .distinct()
                .take(DeviceVoicePreferences.MAX_WORDS)
                .joinToString("\n")
            prefs[READ_ALOUD_SPEED] = next.readAloudSpeed.takeIf { it in DeviceVoicePreferences.SPEEDS } ?: 1f
            prefs[KEEP_AWAKE] = next.keepScreenAwake
            prefs[HERMES_CHANGES] = next.hermesChanges.key
        }
    }

    fun profile(id: DashboardId, profile: String): Flow<VoicePreferences> = settings.data.map { prefs ->
        prefs.voicePreferences(id, profile)
    }.distinctUntilChanged()

    private fun Preferences.voicePreferences(id: DashboardId, profile: String) = VoicePreferences(
        speechOutput = this[key(id, profile, OUTPUT)]
            ?.let { runCatching { SpeechOutput.valueOf(it) }.getOrNull() }
            ?: SpeechOutput.Automatic,
        transcription = this[key(id, profile, TRANSCRIPTION)]?.let {
            runCatching { DictationEngine.valueOf(it) }.getOrNull()
        },
        continuousConversation = this[key(id, profile, CONTINUOUS)]?.toBooleanStrictOrNull() ?: true,
        keepListeningWhenLocked = this[key(id, profile, LOCKED)]?.toBooleanStrictOrNull() ?: false,
        outputMuted = this[key(id, profile, MUTED)]?.toBooleanStrictOrNull() ?: false,
        stopPhrases = this[key(id, profile, STOP_PHRASES)]?.let(::phrases),
        endPhrases = this[key(id, profile, END_PHRASES)]?.let(::phrases),
    )

    suspend fun updateProfile(id: DashboardId, profile: String, change: (VoicePreferences) -> VoicePreferences) {
        settings.edit { prefs ->
            val next = change(prefs.voicePreferences(id, profile))
            prefs[key(id, profile, OUTPUT)] = next.speechOutput.name
            next.transcription?.let { prefs[key(id, profile, TRANSCRIPTION)] = it.name }
                ?: prefs.remove(key(id, profile, TRANSCRIPTION))
            prefs[key(id, profile, CONTINUOUS)] = next.continuousConversation.toString()
            prefs[key(id, profile, LOCKED)] = next.keepListeningWhenLocked.toString()
            prefs[key(id, profile, MUTED)] = next.outputMuted.toString()
            next.stopPhrases?.let { prefs[key(id, profile, STOP_PHRASES)] = stored(it) }
                ?: prefs.remove(key(id, profile, STOP_PHRASES))
            next.endPhrases?.let { prefs[key(id, profile, END_PHRASES)] = stored(it) }
                ?: prefs.remove(key(id, profile, END_PHRASES))
        }
    }

    suspend fun setSpeechOutput(id: DashboardId, profile: String, output: SpeechOutput) =
        updateProfile(id, profile) { it.copy(speechOutput = output) }

    /** Signing out of a dashboard, or removing it, forgets its profiles' voice settings. */
    override suspend fun forget(id: DashboardId) {
        settings.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("voice.profile.${id.value}.") }.forEach { prefs.remove(it) }
        }
    }

    private fun key(id: DashboardId, profile: String, name: String) =
        stringPreferencesKey("voice.profile.${id.value}.$profile.$name")

    // A stored list of phrases: one per line. Present but empty means turned off.
    private fun phrases(stored: String): List<String> = stored.split('\n').map(String::trim).filter(String::isNotEmpty)

    private fun stored(phrases: List<String>): String =
        phrases.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n")

    private companion object {
        const val OUTPUT = "speech_output"
        const val TRANSCRIPTION = "transcription"
        const val CONTINUOUS = "continuous_conversation"
        const val LOCKED = "keep_listening_locked"
        const val MUTED = "output_muted"
        const val STOP_PHRASES = "stop_phrases"
        const val END_PHRASES = "end_phrases"
        val DICTATION_ENGINE = stringPreferencesKey("voice.dictation_engine")
        val DICTATION_WORDS = stringPreferencesKey("voice.dictation_words")
        val READ_ALOUD_SPEED = floatPreferencesKey("voice.read_aloud_speed")
        val KEEP_AWAKE = booleanPreferencesKey("voice.keep_screen_awake")
        val HERMES_CHANGES = stringPreferencesKey("voice.hermes_changes")
    }
}
