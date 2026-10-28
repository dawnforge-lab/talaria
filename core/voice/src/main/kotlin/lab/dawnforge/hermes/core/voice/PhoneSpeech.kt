package lab.dawnforge.hermes.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sentences, as speech is cut so it can pause, resume and change speed between them. */
object Sentences {
    private val END = Regex("""(?<=[.!?…])\s+|\n+""")

    fun of(text: String): List<String> = text.split(END).map(String::trim).filter(String::isNotEmpty)
}

/**
 * The phone's own voice (Android's text-to-speech), started once and kept. It speaks one sentence
 * at a time; [speak] returns when it's said, or false if it was stopped or couldn't be.
 */
class PhoneVoice(private val context: Context) {
    private val lock = Mutex()
    private var engine: TextToSpeech? = null
    private var pending: ((Boolean) -> Unit)? = null

    private suspend fun ready(): TextToSpeech? = lock.withLock {
        engine?.let { return it }
        val created = suspendCancellableCoroutine<TextToSpeech?> { continuation ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                if (continuation.isActive) continuation.resume(tts.takeIf { status == TextToSpeech.SUCCESS })
            }
        } ?: return null
        created.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = finish(true)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(false)

                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(false)
            },
        )
        engine = created
        created
    }

    private fun finish(done: Boolean) {
        val callback = pending
        pending = null
        callback?.invoke(done)
    }

    /** Whether the phone has a voice at all. */
    suspend fun available(): Boolean = ready() != null

    suspend fun speak(sentence: String, speed: Float): Boolean {
        val tts = ready() ?: return false
        return suspendCancellableCoroutine { continuation ->
            pending = { done -> if (continuation.isActive) continuation.resume(done) }
            tts.setSpeechRate(speed)
            val queued = tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, Bundle(), UUID.randomUUID().toString())
            if (queued != TextToSpeech.SUCCESS) finish(false)
            continuation.invokeOnCancellation { tts.stop() }
        }
    }

    fun stop() {
        engine?.stop()
    }
}

/** What the phone's recognizer heard. */
sealed interface Heard {
    data class Partial(val text: String) : Heard

    data class Final(val text: String) : Heard

    /**
     * [noSpeech] when it heard nothing it could use, which isn't a fault. [missingLanguage] when the
     * on-device recognizer has no language pack for it: the regular recognizer can still try.
     */
    data class Failed(val code: Int, val noSpeech: Boolean, val missingLanguage: Boolean = false) : Heard
}

/** What the app asks of the phone's recognizer for one utterance. */
internal object RecognitionRequest {
    const val LONG_SILENCE_MS = 10_000L

    /**
     * The request's extras on Android [sdk]: [local] prefers the on-device recognizer, [words] are
     * names and terms to expect (Android 13+).
     *
     * Words are written as said: Google's recognizer masks swearing ("f***") unless asked not to
     * (Android 13+). The user is talking to their own agent, and a masked word changes what they
     * meant.
     */
    fun extras(local: Boolean, words: List<String>, sdk: Int): Map<String, Any> = buildMap {
        put(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        put(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        put(RecognizerIntent.EXTRA_PREFER_OFFLINE, local)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            put(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            if (words.isNotEmpty()) put(RecognizerIntent.EXTRA_BIASING_STRINGS, words.toList())
        }
        // A pause doesn't end dictation; the user taps to stop.
        put(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_SILENCE_MS)
        put(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, LONG_SILENCE_MS)
    }
}

/**
 * The phone's own speech recognizer, on the device when it has one. One utterance per flow:
 * partial results while the user speaks, then the final words; [stop] ends it early.
 *
 * One recognizer is kept for the owner's utterances and let go with [release]. Making a new one for
 * each utterance raced Android: a start sent while the last one was still unbinding was dropped
 * without an answer, and listening hung. A start that gets no answer within [READY_TIMEOUT_MS] is
 * given up as heard nothing, with a fresh recognizer for the next utterance.
 */
class PhoneRecognizer(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var kept: SpeechRecognizer? = null
    private var keptOnDevice = false
    private var active: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * [words] are names and terms to expect (Android 13+, on-device recognizers). [onDevice] uses
     * the phone's on-device recognizer when it has one.
     */
    fun listen(words: List<String>, onDevice: Boolean = true): Flow<Heard> = callbackFlow {
        val local = onDevice && hasOnDevice()
        val recognizer = recognizer(local)
        active = recognizer
        var answered = false
        var finished = false
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) {
                    best(partialResults)?.let { trySend(Heard.Partial(it)) }
                }

                override fun onResults(results: Bundle?) {
                    finished = true
                    trySend(Heard.Final(best(results).orEmpty()))
                    close()
                }

                override fun onError(error: Int) {
                    finished = true
                    val noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    // Anything else may leave it unusable: the next utterance gets a new one.
                    if (!noSpeech) discard(recognizer)
                    val missing = local && (error == LANGUAGE_NOT_SUPPORTED || error == LANGUAGE_UNAVAILABLE)
                    trySend(Heard.Failed(error, noSpeech, missing))
                    close()
                }

                override fun onReadyForSpeech(params: Bundle?) {
                    answered = true
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        RecognitionRequest.extras(local, words, Build.VERSION.SDK_INT).forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is List<*> -> intent.putStringArrayListExtra(key, ArrayList(value.filterIsInstance<String>()))
            }
        }
        recognizer.startListening(intent)
        val watchdog = launch {
            delay(READY_TIMEOUT_MS)
            if (!answered && !finished) {
                finished = true
                discard(recognizer)
                trySend(Heard.Failed(STALLED, noSpeech = true))
                close()
            }
        }
        awaitClose {
            watchdog.cancel()
            // Ended early (stopped, or no longer wanted): it stays for the next utterance.
            if (!finished && kept === recognizer) recognizer.cancel()
            if (active === recognizer) active = null
        }
    }.flowOn(Dispatchers.Main)

    /** Ends the utterance: the final words follow. */
    fun stop() {
        onMain { active?.stopListening() }
    }

    /** Lets the kept recognizer go, when its owner is done: the next utterance makes a new one. */
    fun release() {
        onMain {
            kept?.destroy()
            kept = null
            active = null
        }
    }

    // Main thread only, as SpeechRecognizer.
    private fun recognizer(onDevice: Boolean): SpeechRecognizer {
        kept?.takeIf { keptOnDevice == onDevice }?.let { return it }
        kept?.destroy()
        val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        kept = created
        keptOnDevice = onDevice
        return created
    }

    private fun discard(recognizer: SpeechRecognizer) {
        if (kept === recognizer) kept = null
        recognizer.destroy()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf(String::isNotBlank)

    private companion object {
        /** Android answers a start well within this; past it, the start was lost. */
        const val READY_TIMEOUT_MS = 8_000L

        // SpeechRecognizer.ERROR_SERVER_DISCONNECTED (Android 12): the start got no answer.
        const val STALLED = 11

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (Android 12).
        const val LANGUAGE_NOT_SUPPORTED = 12
        const val LANGUAGE_UNAVAILABLE = 13
    }
}
