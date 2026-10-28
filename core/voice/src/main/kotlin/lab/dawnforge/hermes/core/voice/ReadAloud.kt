package lab.dawnforge.hermes.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import lab.dawnforge.hermes.core.chat.SpokenText
import lab.dawnforge.hermes.core.data.settings.SpeechOutput
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.SpeechEvent
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.NetResult

/** The reply being read aloud. */
data class ReadAloudState(
    val key: String,
    val phase: Phase,
    val speed: Float,
    /** The reply's first line, for the lock screen. */
    val title: String = "",
) {
    enum class Phase { Preparing, Playing, Paused }
}

/** Who reads a reply aloud. */
enum class Speaker { Phone, Hermes }

/**
 * Who speaks for [profile], as its setting says; automatic picks Hermes when its voice is set up
 * and isn't Microsoft Edge's, else the phone, as on iOS.
 */
suspend fun speakerFor(settings: VoiceSettings, readiness: SpeechReadiness, id: DashboardId, profile: String): Speaker =
    when (settings.profile(id, profile).first().speechOutput) {
        SpeechOutput.Phone -> Speaker.Phone

        SpeechOutput.Hermes -> Speaker.Hermes

        SpeechOutput.Automatic -> readiness.of(id, profile).let { hermes ->
            if (hermes.canSpeak && !hermes.speakerIsEdge) Speaker.Hermes else Speaker.Phone
        }
    }

/**
 * Read Aloud: one reply at a time, by Hermes' voice or the phone's, as the profile's setting says
 * (automatic: Hermes when its voice is set up and isn't Microsoft Edge's, else the phone). It
 * pauses, resumes and changes speed while it plays; a reply paused for ten minutes stops.
 */
class ReadAloud(
    private val context: Context,
    private val speech: HermesSpeech,
    private val readiness: SpeechReadiness,
    private val settings: VoiceSettings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ReadAloudState?>(null)
    val state: StateFlow<ReadAloudState?> = _state.asStateFlow()

    init {
        active = this
    }

    private val voice = PhoneVoice(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var job: Job? = null
    private var player: PcmPlayer? = null
    private var file: MediaPlayer? = null
    private val resumed = MutableStateFlow(true)

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stop() }
        .build()

    /** Reads [text] aloud, or stops it if [key] is the reply being read. */
    fun toggle(key: String, text: String, id: DashboardId, profile: String) {
        if (_state.value?.key == key) {
            stop()
            return
        }
        stop()
        val spoken = SpokenText.filter(forSpeech(text)).trim()
        if (spoken.isEmpty()) return
        job = scope.launch {
            val speed = settings.device.first().readAloudSpeed
            _state.value = ReadAloudState(key, ReadAloudState.Phase.Preparing, speed, title(spoken))
            // On the lock screen, and going on while the app is out of sight.
            ReadAloudService.start(context)
            audio.requestAudioFocus(focus)
            try {
                when (speaker(id, profile)) {
                    Speaker.Hermes -> if (!byHermes(id, profile, spoken)) byPhone(spoken)
                    Speaker.Phone -> byPhone(spoken)
                }
            } finally {
                release()
                audio.abandonAudioFocusRequest(focus)
                if (_state.value?.key == key) _state.value = null
            }
        }
    }

    suspend fun speaker(id: DashboardId, profile: String): Speaker = speakerFor(settings, readiness, id, profile)

    fun pause() {
        val current = _state.value ?: return
        resumed.value = false
        player?.pause()
        file?.pause()
        voice.stop()
        _state.value = current.copy(phase = ReadAloudState.Phase.Paused)
        val paused = current.key
        // A reply left paused stops after a while.
        scope.launch {
            delay(PAUSED_STOP_MS)
            if (_state.value?.let { it.key == paused && it.phase == ReadAloudState.Phase.Paused } == true) stop()
        }
    }

    fun resume() {
        val current = _state.value ?: return
        player?.resume()
        file?.start()
        resumed.value = true
        _state.value = current.copy(phase = ReadAloudState.Phase.Playing)
    }

    fun setSpeed(speed: Float) {
        _state.update { it?.copy(speed = speed) }
        player?.setSpeed(speed)
        file?.let { media -> runCatching { media.playbackParams = PlaybackParams().setSpeed(speed) } }
        scope.launch { settings.updateDevice { it.copy(readAloudSpeed = speed) } }
    }

    fun stop() {
        job?.cancel()
        job = null
        voice.stop()
        release()
        resumed.value = true
        _state.value = null
    }

    private fun playing() = _state.update { it?.copy(phase = ReadAloudState.Phase.Playing) }

    private fun speed(): Float = _state.value?.speed ?: 1f

    /** Hermes' voice: streamed, else the whole reply as a file. False when neither made a sound. */
    private suspend fun byHermes(id: DashboardId, profile: String, text: String): Boolean {
        val stream = speech.openSpeech(id, profile)
        if (stream != null) {
            stream.append(text)
            stream.finish()
            var heard = false
            stream.events.collect { event ->
                when (event) {
                    is SpeechEvent.Start -> {
                        player = PcmPlayer(event.sampleRate, event.channels, speed())
                        playing()
                    }

                    is SpeechEvent.Audio -> {
                        resumed.first { it }
                        player?.write(event.pcm)
                        heard = true
                    }

                    SpeechEvent.End -> player?.drain()

                    SpeechEvent.Fallback, is SpeechEvent.Failed -> Unit
                }
            }
            if (heard) return true
        }
        val whole = speech.speak(id, profile, text) as? NetResult.Success ?: run {
            readiness.forget(id, profile)
            return false
        }
        return playFile(whole.value.bytes)
    }

    private suspend fun playFile(bytes: ByteArray): Boolean {
        val temp = File.createTempFile("read-aloud", null, context.cacheDir).apply { writeBytes(bytes) }
        return try {
            suspendCancellableCoroutine { continuation ->
                val media = MediaPlayer()
                file = media
                runCatching {
                    media.setDataSource(temp.path)
                    media.prepare()
                    runCatching { media.playbackParams = PlaybackParams().setSpeed(speed()) }
                    media.setOnCompletionListener { if (continuation.isActive) continuation.resume(true) }
                    media.setOnErrorListener { _, _, _ ->
                        if (continuation.isActive) continuation.resume(false)
                        true
                    }
                    media.start()
                    playing()
                }.onFailure { if (continuation.isActive) continuation.resume(false) }
            }
        } finally {
            temp.delete()
        }
    }

    /** The phone's voice, a sentence at a time: a pause restarts the sentence it stopped in. */
    private suspend fun byPhone(text: String) {
        playing()
        for (sentence in Sentences.of(text)) {
            while (true) {
                resumed.first { it }
                if (voice.speak(sentence, speed())) break
                // Stopped by a pause: say it again once resumed; anything else ends here.
                if (resumed.value) return
            }
        }
    }

    private fun release() {
        player?.release()
        player = null
        file?.release()
        file = null
    }

    companion object {
        /** The one reader, for its lock-screen controls. */
        @Volatile
        var active: ReadAloud? = null
            private set

        private const val PAUSED_STOP_MS = 10 * 60_000L
        private const val TITLE_LENGTH = 80

        /** The first line, at most 80 characters, as iOS's now playing shows it. */
        fun title(spoken: String): String {
            val line = spoken.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
            return if (line.length > TITLE_LENGTH) line.take(TITLE_LENGTH - 1).trimEnd() + "…" else line
        }

        /** A reply as speech: no `MEDIA:` lines, code blocks, link targets or Markdown marks. */
        fun forSpeech(markdown: String): String = markdown.lineSequence()
            .filterNot { it.trimStart().startsWith("MEDIA:") }
            .joinToString("\n")
            .replace(Regex("""```[\s\S]*?```"""), " ")
            .replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
            .replace(Regex("""(?m)^\s*([-*+]|\d+[.)])\s+"""), "")
            .replace(Regex("""(?m)^\s*>\s?"""), "")
            .replace("|", " ")
            .let(lab.dawnforge.hermes.core.chat.PlainText::of)
    }
}
