package lab.dawnforge.hermes.core.voice

import android.speech.RecognizerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RecognitionRequestTest {
    @Test
    fun `swearing is written as said on Android 13 and later`() {
        val extras = RecognitionRequest.extras(local = true, words = emptyList(), sdk = 33)

        assertEquals(false, extras[RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS])
    }

    @Test
    fun `older Androids aren't sent extras they don't know`() {
        val extras = RecognitionRequest.extras(local = false, words = listOf("Hermes"), sdk = 32)

        assertFalse(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS in extras)
        assertFalse(RecognizerIntent.EXTRA_BIASING_STRINGS in extras)
    }

    @Test
    fun `the rest of the request is unchanged`() {
        val extras = RecognitionRequest.extras(local = true, words = listOf("Hermes", "Nina"), sdk = 37)

        assertEquals(
            mapOf(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                RecognizerIntent.EXTRA_PARTIAL_RESULTS to true,
                RecognizerIntent.EXTRA_PREFER_OFFLINE to true,
                RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS to false,
                RecognizerIntent.EXTRA_BIASING_STRINGS to listOf("Hermes", "Nina"),
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS to 10_000L,
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS to 10_000L,
            ),
            extras,
        )
    }

    @Test
    fun `no expected words, no biasing`() {
        val extras = RecognitionRequest.extras(local = false, words = emptyList(), sdk = 37)

        assertFalse(RecognizerIntent.EXTRA_BIASING_STRINGS in extras)
    }
}
