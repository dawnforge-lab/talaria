package lab.dawnforge.hermes.core.data.voice

import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Audio Hermes made, as a file's bytes and its type (`audio/mpeg`…). */
class EncodedAudio(val bytes: ByteArray, val mimeType: String)

/** What a speech stream sends back while it speaks a reply. */
sealed interface SpeechEvent {
    /** The first audio follows: raw signed 16-bit PCM at [sampleRate]. */
    data class Start(val sampleRate: Int, val channels: Int) : SpeechEvent

    class Audio(val pcm: ByteArray) : SpeechEvent

    data object End : SpeechEvent

    /** Hermes made no audio this way; the text is spoken another way. */
    data object Fallback : SpeechEvent

    data class Failed(val message: String?) : SpeechEvent
}

/** How a live transcription ended. */
sealed interface LiveTranscript {
    data class Final(val text: String) : LiveTranscript

    /** Hermes couldn't transcribe live; [early] when it said so before any words, so it has no live speech-to-text. */
    data class Failed(val message: String?, val early: Boolean) : LiveTranscript
}

/**
 * Hermes' own speech (the `/api/audio` routes): transcribing a recording, speaking text, and the two live
 * sockets the dashboard's voice uses, which take a single-use ticket each.
 */
class HermesSpeech(
    private val dashboards: DashboardRepository,
    private val clients: DashboardClientFactory,
    private val signIn: SignInService,
    private val requests: DashboardRequests,
) {
    /** Dashboards and profiles whose Hermes said it has no live speech-to-text, until the app restarts. */
    private val noLiveTranscription = ConcurrentHashMap.newKeySet<Pair<DashboardId, String>>()

    fun hasLiveTranscription(id: DashboardId, profile: String): Boolean = (id to profile) !in noLiveTranscription

    /**
     * The words in [wav] (`POST /api/audio/transcribe`); an empty string when there were none.
     * Hermes takes up to 25 MB; a long recording gets a long wait.
     */
    suspend fun transcribe(id: DashboardId, profile: String, wav: ByteArray): NetResult<String> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("data_url", "data:audio/wav;base64," + Base64.encodeToString(wav, Base64.NO_WRAP))
                put("mime_type", "audio/wav")
            }
            post(url, jar, cf, TRANSCRIBE, body, mapOf("profile" to profile), timeoutMs = transcribeTimeout(wav.size))
        }.let { result ->
            when (result) {
                is NetResult.Failure -> result

                is NetResult.Success -> {
                    val text = ((result.value as? JsonObject)?.get("transcript") as? JsonPrimitive)?.contentOrNull
                    NetResult.Success(text.orEmpty().trim())
                }
            }
        }

    /** [text] spoken whole (`POST /api/audio/speak`), as an audio file. */
    suspend fun speak(id: DashboardId, profile: String, text: String): NetResult<EncodedAudio> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject { put("text", text) }
            post(url, jar, cf, SPEAK, body, mapOf("profile" to profile), timeoutMs = SPEAK_TIMEOUT_MS)
        }.let { result ->
            when (result) {
                is NetResult.Failure -> result

                is NetResult.Success -> decode(result.value as? JsonObject)?.let { NetResult.Success(it) }
                    ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "Hermes sent no audio"))
            }
        }

    private fun decode(reply: JsonObject?): EncodedAudio? {
        val dataUrl = (reply?.get("data_url") as? JsonPrimitive)?.contentOrNull ?: return null
        val comma = dataUrl.indexOf(',').takeIf { it > 0 && dataUrl.startsWith("data:") } ?: return null
        val type = dataUrl.substring(DATA_PREFIX, comma).substringBefore(';').ifEmpty { "audio/mpeg" }
        val bytes = runCatching { Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
        return bytes?.let { EncodedAudio(it, type) }
    }

    /**
     * A live transcription (`/api/audio/transcribe-stream`) of 16-bit mono PCM at [sampleRate];
     * null when no socket could be opened, or Hermes is known to have no live speech-to-text.
     */
    suspend fun openTranscription(id: DashboardId, profile: String, sampleRate: Int): LiveTranscription? {
        if (!hasLiveTranscription(id, profile)) return null
        val stream = LiveTranscription { early -> if (early) noLiveTranscription += id to profile }
        val socket = open(id, profile, TRANSCRIBE_STREAM, stream.listener) ?: return null
        stream.attach(socket, sampleRate)
        return stream
    }

    /** A speech stream (`/api/audio/speak-stream`) for one reply; null when no socket could be opened. */
    suspend fun openSpeech(id: DashboardId, profile: String): SpeechStream? {
        val stream = SpeechStream()
        val socket = open(id, profile, SPEAK_STREAM, stream.listener) ?: return null
        stream.attach(socket)
        return stream
    }

    private suspend fun open(id: DashboardId, profile: String, path: String, listener: WebSocketListener): WebSocket? {
        val dashboard = dashboards.dashboard(id) ?: return null
        val ticket = signIn.mintGatewayTicket(id) as? TicketResult.Ticket ?: return null
        val cloudflare = dashboards.cloudflareToken(id)
        val client = clients.createWebSocketClient(dashboard.url, signIn.upgradeCookies(id)) { cloudflare }
        // The ticket is single use and a secret: it goes in the query, and the URL is never logged.
        val url = dashboard.url.resolve(path).toHttpUrl().newBuilder()
            .addQueryParameter("ticket", ticket.value)
            .addQueryParameter("profile", profile)
            .build()
        return client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private companion object {
        const val TRANSCRIBE = "/api/audio/transcribe"
        const val SPEAK = "/api/audio/speak"
        const val TRANSCRIBE_STREAM = "/api/audio/transcribe-stream"
        const val SPEAK_STREAM = "/api/audio/speak-stream"
        const val DATA_PREFIX = 5
        const val SPEAK_TIMEOUT_MS = 90_000L
        const val MIN_TRANSCRIBE_MS = 180_000L
        const val MAX_TRANSCRIBE_MS = 600_000L

        /** 3 minutes, and more for a long recording, up to 10, as on iOS. */
        fun transcribeTimeout(bytes: Int): Long {
            val perMegabyte = 20_000L
            return (MIN_TRANSCRIBE_MS + bytes / BYTES_PER_MB * perMegabyte).coerceAtMost(MAX_TRANSCRIBE_MS)
        }

        const val BYTES_PER_MB = 1_048_576
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun text(frame: String): JsonObject? = runCatching { json.parseToJsonElement(frame) as? JsonObject }.getOrNull()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Live words while the user speaks, then the final transcript. */
class LiveTranscription internal constructor(private val onFailed: (early: Boolean) -> Unit) {
    private val _partial = MutableStateFlow("")

    /** The words so far. */
    val partial: StateFlow<String> = _partial.asStateFlow()
    private val result = CompletableDeferred<LiveTranscript>()
    private var socket: WebSocket? = null

    internal val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = text(text) ?: return
            when (frame.string("type")) {
                "partial" -> frame.string("text")?.let { _partial.value = it }
                "final" -> result.complete(LiveTranscript.Final(frame.string("transcript").orEmpty().trim()))
                "error" -> fail(frame.string("message"))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(reason.ifEmpty { null })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(t.message)
        }
    }

    private fun fail(message: String?) {
        val early = _partial.value.isEmpty()
        if (result.complete(LiveTranscript.Failed(message, early))) onFailed(early)
    }

    internal fun attach(socket: WebSocket, sampleRate: Int) {
        this.socket = socket
        socket.send(buildJsonObject { put("sample_rate", sampleRate) }.toString())
    }

    /** Whether it already ended (Hermes refused, or the link dropped). */
    val ended: Boolean get() = result.isCompleted

    fun send(pcm: ByteArray) {
        if (!result.isCompleted) socket?.send(pcm.toByteString())
    }

    /** The recording ended: the final words, or why there are none. */
    suspend fun finish(timeoutMs: Long = FINAL_TIMEOUT_MS): LiveTranscript {
        if (!result.isCompleted) socket?.send(buildJsonObject { put("eos", true) }.toString())
        val outcome = withTimeoutOrNull(timeoutMs) { result.await() }
            ?: LiveTranscript.Failed("Hermes didn't answer in time", early = false)
        socket?.close(NORMAL_CLOSURE, null)
        return outcome
    }

    /** Leaves without words: Hermes drops what it heard. */
    fun cancel() {
        result.complete(LiveTranscript.Failed(null, early = false))
        socket?.close(NORMAL_CLOSURE, null)
    }

    private companion object {
        const val FINAL_TIMEOUT_MS = 10_000L
        const val NORMAL_CLOSURE = 1000
    }
}

/**
 * One reply spoken as it is written: text goes in as it arrives, PCM comes out a sentence at a
 * time. [stop] cuts it off (the user spoke over it).
 */
class SpeechStream internal constructor() {
    private val channel = Channel<SpeechEvent>(Channel.UNLIMITED)
    val events: Flow<SpeechEvent> = channel.receiveAsFlow()
    private var socket: WebSocket? = null

    internal val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = text(text) ?: return
            when (frame.string("type")) {
                "start" -> channel.trySend(
                    SpeechEvent.Start(
                        sampleRate = (frame["sample_rate"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_RATE,
                        channels = (frame["channels"] as? JsonPrimitive)?.intOrNull ?: 1,
                    ),
                )

                "end" -> end(SpeechEvent.End)

                "fallback" -> end(SpeechEvent.Fallback)

                "error" -> end(SpeechEvent.Failed(frame.string("message")))
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            channel.trySend(SpeechEvent.Audio(bytes.toByteArray()))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            end(SpeechEvent.End)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            end(SpeechEvent.Failed(t.message))
        }
    }

    private fun end(event: SpeechEvent) {
        channel.trySend(event)
        channel.close()
    }

    internal fun attach(socket: WebSocket) {
        this.socket = socket
    }

    fun append(text: String) {
        if (text.isNotEmpty()) socket?.send(buildJsonObject { put("text", text) }.toString())
    }

    /** All the text is in; Hermes speaks what's left and ends. */
    fun finish() {
        socket?.send(buildJsonObject { put("done", true) }.toString())
    }

    fun stop() {
        socket?.send(buildJsonObject { put("stop", true) }.toString())
        socket?.close(NORMAL_CLOSURE, null)
        channel.close()
    }

    private companion object {
        const val DEFAULT_RATE = 24_000
        const val NORMAL_CLOSURE = 1000
    }
}
