package lab.dawnforge.hermes.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import lab.dawnforge.hermes.core.data.gateway.ConnectionHolds
import lab.dawnforge.hermes.core.data.settings.DictationEngine
import lab.dawnforge.hermes.core.data.settings.VoicePreferences
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.LiveTranscript
import lab.dawnforge.hermes.core.data.voice.LiveTranscription
import lab.dawnforge.hermes.core.data.voice.SpeechReadiness
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** What a voice conversation is doing. */
enum class VoicePhase { Starting, Listening, Transcribing, Thinking, Speaking, Paused, Ended }

/** A line of the conversation as it went. */
data class VoiceLine(val fromUser: Boolean, val text: String)

sealed interface VoiceProblem {
    data object NoPermission : VoiceProblem

    data object MicBusy : VoiceProblem

    data class Transcription(val failure: ConnectionFailure) : VoiceProblem

    /** The phone's recognizer failed ([code] is Android's). */
    data class Recognizer(val code: Int) : VoiceProblem

    /** What was said couldn't be sent to Hermes. */
    data object NotSent : VoiceProblem
}

data class VoiceState(
    val phase: VoicePhase = VoicePhase.Starting,
    /** The microphone's level, 0 to 1. */
    val level: Float = 0f,
    /** Words heard so far in what the user is saying. */
    val hearing: String = "",
    val lines: List<VoiceLine> = emptyList(),
    val muted: Boolean = false,
    val problem: VoiceProblem? = null,
    /** The screen stays on while the conversation shows. */
    val keepAwake: Boolean = false,
)

/** The chat a conversation talks in: it sends what was said and stops the running turn. */
interface VoiceHost {
    /** Sends [text] as a spoken turn, or as a steer while a turn runs. False when it couldn't go. */
    suspend fun said(text: String, afterInterrupt: Boolean = false): Boolean

    /** Stops the running turn: the user spoke over the reply. */
    fun interrupt()

    val turnRunning: Boolean
}

/**
 * The hands-free voice conversation in one chat, as iOS's Classic voice: it listens, sends what
 * was said, speaks the reply as it streams, and listens again.
 *
 * - **Listening:** with Hermes' speech-to-text, the app's own microphone and an adaptive speech
 *   detector end each utterance after a short silence (live words where Hermes streams them, else
 *   the utterance is uploaded). With the phone's, Android's recognizer takes one utterance at a time.
 * - **While Hermes thinks**, what the user says steers the running turn. **While it speaks**, on a
 *   headset, speaking over it stops it and starts a new turn; on the loudspeaker the microphone
 *   waits, so Hermes never hears itself.
 * - **Spoken commands:** a Stop phrase interrupts, an End phrase closes the conversation.
 * - **After a reply**, continuous conversation listens again; otherwise the microphone waits for
 *   Listen. Locking the phone pauses it unless the profile keeps listening when locked.
 */
class VoiceConversation(
    private val context: Context,
    private val speech: HermesSpeech,
    private val readiness: SpeechReadiness,
    private val settings: VoiceSettings,
    private val scope: CoroutineScope,
    private val id: DashboardId,
    private val profile: String,
    private val host: VoiceHost,
    private val holds: ConnectionHolds,
) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val voice = PhoneVoice(context)
    private val recognizer = PhoneRecognizer(context)
    private val detector = SpeechDetector()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var prefs = VoicePreferences()
    private var speaker = Speaker.Phone
    private var engine = DictationEngine.Phone

    private var loop: Job? = null
    private var reply: ReplySpeaker? = null
    private var replyEnded: CompletableDeferred<Unit>? = null
    private val micWanted = MutableStateFlow(true)
    private val speaking = MutableStateFlow(false)
    private var screenOff: BroadcastReceiver? = null

    /** Keeps the live connection open while the conversation runs out of sight. */
    private var connection: (() -> Unit)? = null

    val active: Boolean get() = _state.value.phase != VoicePhase.Ended

    /** Starts listening; [opening] (a call from Hermes saying why it called) is said first. */
    fun start(opening: String? = null) {
        if (loop != null) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(problem = VoiceProblem.NoPermission, phase = VoicePhase.Ended) }
            return
        }
        VoiceSessions.started(this)
        connection = holds.hold()
        VoiceService.start(context)
        watchScreen()
        loop = scope.launch {
            prefs = settings.profile(id, profile).first()
            speaker = speakerFor(settings, readiness, id, profile)
            engine = prefs.transcription
                ?.takeUnless { it == DictationEngine.Phone && !recognizer.isAvailable() }
                ?: if (recognizer.isAvailable()) DictationEngine.Phone else DictationEngine.Hermes
            val device = settings.device.first()
            _state.update { it.copy(muted = prefs.outputMuted, keepAwake = device.keepScreenAwake) }
            opening?.takeIf(String::isNotBlank)?.let { say(it) }
            detector.reset()
            while (true) {
                micWanted.first { it }
                when (val heard = listen(idle = IDLE_MS)) {
                    Said.Idle -> if (!prefs.continuousConversation) pause()

                    // Words still arriving after the microphone was paused aren't heard.
                    is Said.Words -> if (micWanted.value && !handle(heard.text, duringTurn = false)) return@launch

                    is Said.Problem -> {
                        _state.update { it.copy(problem = heard.problem) }
                        pause()
                    }
                }
            }
        }
    }

    /** Ends the conversation: listening and speech stop, the running turn goes on. */
    fun end() {
        if (_state.value.phase == VoicePhase.Ended) return
        loop?.cancel()
        loop = null
        reply?.stop()
        reply = null
        recognizer.release()
        voice.stop()
        connection?.invoke()
        connection = null
        screenOff?.let { runCatching { context.unregisterReceiver(it) } }
        screenOff = null
        _state.update { it.copy(phase = VoicePhase.Ended, level = 0f, hearing = "") }
        VoiceService.stop(context)
        VoiceSessions.ended(this)
    }

    /** Listen (after a pause, or when not listening by itself). */
    fun resumeMic() {
        micWanted.value = true
        _state.update { it.copy(problem = null) }
    }

    /** Pause mic: nothing is heard until Listen. */
    fun pause() {
        micWanted.value = false
        recognizer.stop()
        _state.update { it.copy(phase = if (it.phase == VoicePhase.Ended) it.phase else VoicePhase.Paused, level = 0f) }
    }

    /** Interrupt: stops the reply being spoken and the running turn. */
    fun interrupt() {
        reply?.stop()
        if (host.turnRunning) host.interrupt()
    }

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
        if (muted) reply?.stop()
        scope.launch { settings.updateProfile(id, profile) { it.copy(outputMuted = muted) } }
    }

    fun problemShown() = _state.update { it.copy(problem = null) }

    // ---- The reply, as the chat streams it ------------------------------------------------------

    /**
     * The reply's text, held until it's known how this stretch of it ends: text that ends in a
     * tool call is the model thinking aloud ([replySegmentEnded] drops it), only the reply's last
     * stretch is spoken.
     */
    private val segment = StringBuilder()

    fun replyDelta(text: String) {
        if (text.isEmpty()) return
        segment.append(text)
        _state.update { state ->
            val last = state.lines.lastOrNull()
            val lines = if (last != null && !last.fromUser) {
                state.lines.dropLast(1) + last.copy(text = last.text + text)
            } else {
                state.lines + VoiceLine(fromUser = false, text = text)
            }
            state.copy(lines = lines)
        }
    }

    /** A tool call followed: what came before it was working, not the answer. It's neither said nor kept. */
    fun replySegmentEnded() {
        if (segment.isEmpty()) return
        segment.setLength(0)
        _state.update { state ->
            val last = state.lines.lastOrNull()
            state.copy(lines = if (last != null && !last.fromUser) state.lines.dropLast(1) else state.lines)
        }
    }

    /** The turn ended (finished, failed or stopped): its last stretch is spoken. */
    fun replyEnded() {
        val answer = segment.toString()
        segment.setLength(0)
        reply?.let { speaker ->
            if (!_state.value.muted && answer.isNotBlank()) speaker.append(answer)
            speaker.finish()
        }
        replyEnded?.complete(Unit)
    }

    /** Says [text] as Hermes' own line, before anything is heard. */
    private suspend fun say(text: String) {
        _state.update { it.copy(lines = it.lines + VoiceLine(fromUser = false, text = text)) }
        if (_state.value.muted) return
        val saying = ReplySpeaker(speaker, speech, voice, id, profile, scope) {
            speaking.value = true
            _state.update { it.copy(phase = VoicePhase.Speaking) }
        }
        reply = saying
        saying.append(text)
        saying.finish()
        saying.awaitDone()
        speaking.value = false
        reply = null
    }

    // ---- Listening ------------------------------------------------------------------------------

    private sealed interface Said {
        data object Idle : Said

        data class Words(val text: String) : Said

        data class Problem(val problem: VoiceProblem) : Said
    }

    /** One utterance, or nothing said for [idle] ms. */
    private suspend fun listen(idle: Long): Said {
        _state.update { it.copy(phase = if (speaking.value) VoicePhase.Speaking else listeningPhase(), hearing = "") }
        return when (engine) {
            DictationEngine.Hermes -> listenHermes(idle)
            DictationEngine.Phone -> listenPhone(idle)
        }
    }

    private fun listeningPhase() = if (replyEnded?.isCompleted == false) VoicePhase.Thinking else VoicePhase.Listening

    private suspend fun listenPhone(idle: Long): Said {
        // The phone's recognizer would hear the reply on the speaker: it waits until it's said.
        speaking.first { !it }
        var onDevice = true
        val until = System.currentTimeMillis() + idle
        while (true) {
            var result: Said? = null
            var retry = false
            recognizer.listen(emptyList(), onDevice).collect { heard ->
                when (heard) {
                    is Heard.Partial -> _state.update { it.copy(hearing = heard.text) }

                    is Heard.Final -> result = Said.Words(heard.text)

                    is Heard.Failed -> when {
                        heard.missingLanguage && onDevice -> retry = true
                        heard.noSpeech -> Unit
                        else -> result = Said.Problem(VoiceProblem.Recognizer(heard.code))
                    }
                }
            }
            if (retry) {
                onDevice = false
                continue
            }
            val words = (result as? Said.Words)?.text
            result?.let { found -> if (words == null || words.isNotBlank()) return found }
            if (System.currentTimeMillis() >= until) return Said.Idle
        }
    }

    // Reached only after start() found the microphone permission granted.
    @SuppressLint("MissingPermission")
    private suspend fun listenHermes(idle: Long): Said {
        val recording = ByteArrayOutputStream()
        val preRoll = ArrayDeque<ByteArray>()
        var live: LiveTranscription? = null
        var partials: Job? = null
        var inSpeech = false
        val started = System.currentTimeMillis()
        var speechStarted = 0L
        var lastSpeech = 0L
        var idleOut = false
        try {
            MicCapture.frames().first { frame ->
                val now = System.currentTimeMillis()
                _state.update { it.copy(level = frame.level) }
                // On the loudspeaker the microphone waits while Hermes speaks.
                if (speaking.value && !fullDuplex()) {
                    preRoll.clear()
                    return@first false
                }
                val detection = detector.observe(frame.peak)
                if (!inSpeech) {
                    preRoll.addLast(frame.pcm)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (detection == SpeechDetector.Detection.Started) {
                        inSpeech = true
                        speechStarted = now
                        lastSpeech = now
                        live = speech.openTranscription(id, profile, Pcm.MIC_RATE)
                        partials = live?.partial?.let { words ->
                            scope.launch { words.collect { w -> _state.update { it.copy(hearing = w) } } }
                        }
                        preRoll.forEach {
                            recording.write(it)
                            live?.send(it)
                        }
                        preRoll.clear()
                    }
                    if (now - started > idle) idleOut = true
                    return@first idleOut
                }
                recording.write(frame.pcm)
                live?.send(frame.pcm)
                if (detection != SpeechDetector.Detection.None) lastSpeech = now
                now - lastSpeech > TRAILING_SILENCE_MS || now - speechStarted > MAX_UTTERANCE_MS
            }
        } catch (_: MicUnavailableException) {
            live?.cancel()
            return Said.Problem(VoiceProblem.MicBusy)
        } finally {
            partials?.cancel()
        }
        detector.endUtterance()
        _state.update { it.copy(level = 0f) }
        if (idleOut) return Said.Idle
        _state.update { it.copy(phase = VoicePhase.Transcribing) }
        (live?.finish() as? LiveTranscript.Final)?.text?.takeIf(String::isNotEmpty)?.let { return Said.Words(it) }
        return when (val result = speech.transcribe(id, profile, Pcm.wav(recording.toByteArray()))) {
            is NetResult.Success -> Said.Words(result.value)
            is NetResult.Failure -> Said.Problem(VoiceProblem.Transcription(result.failure))
        }
    }

    /** A headset (wired, Bluetooth or USB) hears none of Hermes' voice: the user can talk over it. */
    private fun fullDuplex(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in HEADSETS
    }

    // ---- Turns ---------------------------------------------------------------------------------

    /**
     * What the user said: a command, or a turn. False when the conversation ended. [duringTurn]
     * when Hermes was still on the last one.
     */
    private suspend fun handle(text: String, duringTurn: Boolean, afterInterrupt: Boolean = false): Boolean {
        val words = text.trim()
        if (words.isEmpty()) return true
        if (SpokenCommands.matches(words, prefs.endPhrases ?: SpokenCommands.END)) {
            end()
            return false
        }
        if (SpokenCommands.matches(words, prefs.stopPhrases ?: SpokenCommands.STOP)) {
            interrupt()
            return true
        }
        _state.update { it.copy(lines = it.lines + VoiceLine(fromUser = true, text = words), hearing = "") }
        if (duringTurn) {
            // Said while Hermes thinks: it steers the running turn.
            if (!host.said(words)) _state.update { it.copy(problem = VoiceProblem.NotSent) }
            return true
        }
        return turn(words, afterInterrupt)
    }

    private suspend fun turn(words: String, afterInterrupt: Boolean): Boolean {
        val ended = CompletableDeferred<Unit>()
        replyEnded = ended
        val speakerForReply = ReplySpeaker(speaker, speech, voice, id, profile, scope) {
            speaking.value = true
            if (engine == DictationEngine.Phone) recognizer.stop()
            _state.update { it.copy(phase = VoicePhase.Speaking) }
        }
        reply = speakerForReply
        _state.update { it.copy(phase = VoicePhase.Thinking) }
        if (!host.said(words, afterInterrupt)) {
            _state.update { it.copy(problem = VoiceProblem.NotSent, phase = VoicePhase.Listening) }
            reply = null
            replyEnded = null
            return true
        }
        // The reply and its speech, listening over it where the user may speak.
        val done: Deferred<Unit> = scope.async {
            ended.await()
            speakerForReply.awaitDone()
        }
        while (!done.isCompleted) {
            val listening = scope.async { listen(idle = Long.MAX_VALUE) }
            val heard = select<Said?> {
                done.onAwait { null }
                listening.onAwait { it }
            }
            if (heard == null) {
                listening.cancel()
                recognizer.stop()
                break
            }
            if (heard !is Said.Words || heard.text.isBlank()) continue
            if (speaking.value) {
                // Spoken over the reply: it stops, and what was said is the next turn.
                speakerForReply.stop()
                host.interrupt()
                done.cancel()
                speaking.value = false
                reply = null
                return handle(heard.text, duringTurn = false, afterInterrupt = true)
            }
            if (!handle(heard.text, duringTurn = true)) return false
        }
        speaking.value = false
        reply = null
        if (!prefs.continuousConversation) pause()
        return true
    }

    private fun watchScreen() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!prefs.keepListeningWhenLocked) pause()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOff = receiver
    }

    private companion object {
        /** iOS's timing: a utterance ends after 1.25 s of silence, lasts at most a minute. */
        const val TRAILING_SILENCE_MS = 1_250L
        const val MAX_UTTERANCE_MS = 60_000L
        const val IDLE_MS = 12_000L

        /** About half a second of what came just before speech, so its first sound isn't lost. */
        const val PRE_ROLL_FRAMES = 5
        val HEADSETS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}

/** The conversation running now, so the voice notification can end it. One at a time. */
object VoiceSessions {
    @Volatile
    var current: VoiceConversation? = null
        private set

    internal fun started(conversation: VoiceConversation) {
        current?.takeIf { it !== conversation }?.end()
        current = conversation
    }

    internal fun ended(conversation: VoiceConversation) {
        if (current === conversation) current = null
    }
}
