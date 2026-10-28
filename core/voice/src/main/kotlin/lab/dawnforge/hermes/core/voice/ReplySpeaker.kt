package lab.dawnforge.hermes.core.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.chat.SpokenText
import lab.dawnforge.hermes.core.data.voice.HermesSpeech
import lab.dawnforge.hermes.core.data.voice.SpeechEvent
import lab.dawnforge.hermes.core.data.voice.SpeechStream
import lab.dawnforge.hermes.core.model.DashboardId

/**
 * One reply, spoken while it is written. Text goes in as Hermes streams it, without narrated
 * actions and emoji. Hermes' voice takes the text as it comes and cuts the sentences itself; the
 * phone's voice says each sentence once it is complete. A Hermes voice that makes no sound hands
 * over to the phone. [stop] cuts it off (the user spoke over it).
 */
class ReplySpeaker(
    private val speaker: Speaker,
    private val speech: HermesSpeech,
    private val voice: PhoneVoice,
    private val id: DashboardId,
    private val profile: String,
    private val scope: CoroutineScope,
    /** The first sound is about to play. */
    private val onStart: () -> Unit,
) {
    private val filter = SpokenText()
    private val pieces = Channel<String>(Channel.UNLIMITED)
    private val done = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var player: PcmPlayer? = null
    private var stream: SpeechStream? = null
    private var started = false

    fun append(delta: String) = send(filter.feed(delta))

    /** The reply is complete: what's left is spoken, then [awaitDone] returns. */
    fun finish() {
        send(filter.finish())
        pieces.close()
        if (job == null) done.complete(Unit)
    }

    suspend fun awaitDone() = done.await()

    fun stop() {
        job?.cancel()
        stream?.stop()
        player?.release()
        player = null
        voice.stop()
        pieces.close()
        done.complete(Unit)
    }

    private fun send(text: String) {
        if (text.isEmpty()) return
        if (job == null) {
            job = scope.launch {
                try {
                    when (speaker) {
                        Speaker.Hermes -> byHermes()
                        Speaker.Phone -> byPhone("", pieces)
                    }
                } finally {
                    player?.release()
                    player = null
                    done.complete(Unit)
                }
            }
        }
        pieces.trySend(text)
    }

    private fun startOnce() {
        if (!started) {
            started = true
            onStart()
        }
    }

    private suspend fun byHermes() = coroutineScope {
        val opened = speech.openSpeech(id, profile)
        if (opened == null) {
            byPhone("", pieces)
            return@coroutineScope
        }
        stream = opened
        val sent = StringBuilder()
        val feeder = launch {
            for (piece in pieces) {
                sent.append(piece)
                opened.append(piece)
            }
            opened.finish()
        }
        var heard = false
        opened.events.collect { event ->
            when (event) {
                is SpeechEvent.Start -> {
                    player = PcmPlayer(event.sampleRate, event.channels)
                    startOnce()
                }

                is SpeechEvent.Audio -> {
                    player?.write(event.pcm)
                    heard = true
                }

                SpeechEvent.End -> player?.drain()

                SpeechEvent.Fallback, is SpeechEvent.Failed -> Unit
            }
        }
        feeder.cancel()
        // No sound from Hermes: the phone says what Hermes got, then the rest as it comes.
        if (!heard) byPhone(sent.toString(), pieces)
    }

    /** The phone's voice, a sentence at a time as each completes. */
    private suspend fun byPhone(already: String, more: ReceiveChannel<String>) {
        val buffer = StringBuilder(already)
        suspend fun say(final: Boolean): Boolean {
            val text = buffer.toString()
            val cut = if (final) text.length else lastSentenceEnd(text)
            if (cut <= 0) return true
            buffer.delete(0, cut)
            for (sentence in Sentences.of(text.substring(0, cut))) {
                startOnce()
                if (!voice.speak(sentence, 1f)) return false
            }
            return true
        }
        if (!say(final = false)) return
        for (piece in more) {
            buffer.append(piece)
            if (!say(final = false)) return
        }
        say(final = true)
    }

    private companion object {
        private val SENTENCE_END = Regex(
            """[.!?…]["')\]]*\s|
""",
        )

        /** Where the last complete sentence ends (after its trailing space); 0 when none has. */
        fun lastSentenceEnd(text: String): Int = SENTENCE_END.findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0
    }
}
