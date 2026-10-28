package lab.dawnforge.hermes.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lab.dawnforge.hermes.core.data.settings.DictationEngine
import lab.dawnforge.hermes.core.data.settings.VoiceSettings
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.LiveTranscript
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** How dictation is going. */
sealed interface DictationUpdate {
    /** Listening; [words] are what was heard so far (empty until the first). */
    data class Listening(val words: String, val level: Float = 0f) : DictationUpdate

    /** The recording went up to Hermes, which is turning it into text. */
    data object Transcribing : DictationUpdate

    /** The words heard; empty when there were none. */
    data class Done(val words: String) : DictationUpdate

    data class Failed(val problem: DictationProblem) : DictationUpdate
}

sealed interface DictationProblem {
    /** The app may not use the microphone. */
    data object NoPermission : DictationProblem

    /** Another app holds the microphone. */
    data object MicBusy : DictationProblem

    /** The phone's recognizer failed ([code] is Android's). */
    data class Phone(val code: Int) : DictationProblem

    /** Hermes couldn't transcribe it. */
    data class Hermes(val failure: ConnectionFailure) : DictationProblem

    /** Hermes took longer than ten minutes. */
    data object TimedOut : DictationProblem
}

/**
 * Speech to text into the composer, never sent by itself, as on iOS. The phone's recognizer, or
 * Hermes: live words over its socket where Hermes has live speech-to-text, else the recording is
 * uploaded when the user stops. One dictation at a time; [stop] ends it and the words follow.
 */
class Dictation(private val context: Context, private val speech: HermesSpeech, private val settings: VoiceSettings) {
    private val recognizer = PhoneRecognizer(context)
    private var stopRequest: CompletableDeferred<Unit>? = null

    /** The engine dictation would use now. */
    suspend fun engine(): DictationEngine = settings.device.first().dictationEngine
        ?.takeUnless { it == DictationEngine.Phone && !recognizer.isAvailable() }
        ?: if (recognizer.isAvailable()) DictationEngine.Phone else DictationEngine.Hermes

    /** Dictates once; without the microphone permission it ends at once with [DictationProblem.NoPermission]. */
    fun dictate(id: DashboardId, profile: String): Flow<DictationUpdate> = channelFlow {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            send(DictationUpdate.Failed(DictationProblem.NoPermission))
            return@channelFlow
        }
        val stop = CompletableDeferred<Unit>()
        stopRequest = stop
        try {
            when (engine()) {
                DictationEngine.Phone -> phone(settings.device.first().dictationWords, stop) { send(it) }
                DictationEngine.Hermes -> hermes(id, profile, stop) { send(it) }
            }
        } finally {
            if (stopRequest === stop) stopRequest = null
            // Kept for this dictation's utterances only.
            recognizer.release()
        }
    }

    /** Ends the recording; the words come as [DictationUpdate.Done]. */
    fun stop() {
        stopRequest?.complete(Unit)
        recognizer.stop()
    }

    private suspend fun phone(
        words: List<String>,
        stop: CompletableDeferred<Unit>,
        send: suspend (DictationUpdate) -> Unit,
    ) {
        send(DictationUpdate.Listening(""))
        var onDevice = true
        // The recognizer ends an utterance at a pause; dictation goes on until the user stops, its
        // utterances joined, as on iOS. A long silence ends it too.
        var heardSoFar = ""
        var silences = 0
        while (true) {
            var retry = false
            var failed = false
            var heardSpeech = false
            recognizer.listen(words, onDevice).collect { heard ->
                when (heard) {
                    is Heard.Partial -> {
                        heardSpeech = true
                        send(DictationUpdate.Listening(joined(heardSoFar, heard.text)))
                    }

                    is Heard.Final -> {
                        if (heard.text.isNotBlank()) heardSpeech = true
                        heardSoFar = joined(heardSoFar, heard.text)
                    }

                    is Heard.Failed -> when {
                        // No language pack on the device yet: the regular recognizer tries instead.
                        heard.missingLanguage && onDevice -> retry = true

                        heard.noSpeech -> Unit

                        else -> {
                            failed = true
                            send(DictationUpdate.Failed(DictationProblem.Phone(heard.code)))
                        }
                    }
                }
            }
            if (failed) return
            if (retry) {
                onDevice = false
                continue
            }
            silences = if (heardSpeech) 0 else silences + 1
            if (!stop.isActive || silences >= MAX_SILENCES) break
            send(DictationUpdate.Listening(heardSoFar))
        }
        send(DictationUpdate.Done(heardSoFar))
        if (stop.isActive) stop.complete(Unit)
    }

    // Only reached once dictate() found the permission granted.
    @SuppressLint("MissingPermission")
    private suspend fun hermes(
        id: DashboardId,
        profile: String,
        stop: CompletableDeferred<Unit>,
        send: suspend (DictationUpdate) -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        send(DictationUpdate.Listening(""))
        val recording = ByteArrayOutputStream()
        val live = speech.openTranscription(id, profile, Pcm.MIC_RATE)
        var level = 0f
        var words = ""
        val partials = live?.let { stream ->
            launch {
                stream.partial.collect {
                    words = it
                    if (it.isNotEmpty()) send(DictationUpdate.Listening(it, level))
                }
            }
        }
        var micFailed = false
        val mic = launch {
            try {
                MicCapture.frames().collect { frame ->
                    recording.write(frame.pcm)
                    live?.send(frame.pcm)
                    level = frame.level
                    send(DictationUpdate.Listening(words, level))
                }
            } catch (_: MicUnavailableException) {
                micFailed = true
                stop.complete(Unit)
            }
        }
        withTimeoutOrNull(MAX_RECORDING_MS) { stop.await() }
        mic.cancelAndJoin()
        if (micFailed) {
            live?.cancel()
            partials?.cancel()
            send(DictationUpdate.Failed(DictationProblem.MicBusy))
            return@coroutineScope
        }
        val heardLive = (live?.finish() as? LiveTranscript.Final)?.text?.takeIf(String::isNotEmpty)
        partials?.cancel()
        if (heardLive != null) {
            send(DictationUpdate.Done(heardLive))
            return@coroutineScope
        }
        // No live words: the whole recording goes up.
        send(DictationUpdate.Transcribing)
        val result = withTimeoutOrNull(WATCHDOG_MS) { speech.transcribe(id, profile, Pcm.wav(recording.toByteArray())) }
        send(
            when (result) {
                null -> DictationUpdate.Failed(DictationProblem.TimedOut)
                is NetResult.Success -> DictationUpdate.Done(result.value)
                is NetResult.Failure -> DictationUpdate.Failed(DictationProblem.Hermes(result.failure))
            },
        )
    }

    private companion object {
        /** About fifteen seconds without a word (Google's recognizer gives up after five). */
        const val MAX_SILENCES = 3
        const val MAX_RECORDING_MS = 10 * 60_000L

        /** Utterances joined with a space. */
        fun joined(before: String, words: String): String = when {
            words.isBlank() -> before
            before.isBlank() -> words.trim()
            else -> before.trimEnd() + " " + words.trim()
        }
        const val WATCHDOG_MS = 610_000L
    }
}
