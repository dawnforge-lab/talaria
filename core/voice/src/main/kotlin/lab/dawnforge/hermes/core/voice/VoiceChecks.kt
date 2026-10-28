package lab.dawnforge.hermes.core.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Whether the phone's on-device recognizer has the user's language, Android 13+. */
enum class OfflineSpeech { Installed, Downloading, Missing, Unsupported, Unknown }

/** What the phone itself can do for voice, for the voice settings' checklist. */
class VoiceChecks(private val context: Context) {
    private val voice = PhoneVoice(context)

    fun canRecognize(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun hasOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun canSpeak(): Boolean = voice.available()

    /** Says [text] in the phone's voice, to hear how it sounds. */
    suspend fun sayInPhoneVoice(text: String) {
        for (sentence in Sentences.of(text)) if (!voice.speak(sentence, 1f)) break
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())

    /** Whether the on-device recognizer has the phone's language installed. */
    suspend fun offlineSpeech(): OfflineSpeech {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasOnDevice()) return OfflineSpeech.Unknown
        return withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            try {
                suspendCancellableCoroutine { continuation ->
                    val tag = Locale.getDefault().toLanguageTag()
                    recognizer.checkRecognitionSupport(
                        intent(),
                        context.mainExecutor,
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                val state = when {
                                    support.installedOnDeviceLanguages.any { it.equals(tag, ignoreCase = true) } ->
                                        OfflineSpeech.Installed

                                    support.pendingOnDeviceLanguages.any { it.equals(tag, ignoreCase = true) } ->
                                        OfflineSpeech.Downloading

                                    support.supportedOnDeviceLanguages.any { it.equals(tag, ignoreCase = true) } ->
                                        OfflineSpeech.Missing

                                    else -> OfflineSpeech.Unsupported
                                }
                                if (continuation.isActive) continuation.resume(state)
                            }

                            override fun onError(error: Int) {
                                if (continuation.isActive) continuation.resume(OfflineSpeech.Unknown)
                            }
                        },
                    )
                }
            } finally {
                recognizer.destroy()
            }
        }
    }

    /** Asks Android to download the phone's language for on-device recognition; it asks the user first. */
    suspend fun downloadOfflineSpeech() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !hasOnDevice()) return
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            runCatching { recognizer.triggerModelDownload(intent()) }
            recognizer.destroy()
        }
    }
}
