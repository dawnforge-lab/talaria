package lab.dawnforge.hermes.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** One read from the microphone: 16-bit mono PCM, little-endian, how loud it was (0–1), and its peak. */
class MicFrame(val pcm: ByteArray, val level: Float, val peak: Float = level)

/** Signed 16-bit mono PCM, as Hermes takes and sends it. */
object Pcm {
    /** What the microphone records at, as iOS does: what Hermes' speech-to-text wants. */
    const val MIC_RATE = 16_000
    private const val BYTES_PER_SAMPLE = 2

    /** A WAV file of [pcm] at [sampleRate], for uploading a recording whole. */
    fun wav(pcm: ByteArray, sampleRate: Int = MIC_RATE): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(WAV_HEADER - RIFF_PREFIX + pcm.size)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(FMT_SIZE)
            putShort(PCM_FORMAT)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(BITS.toShort())
            put("data".toByteArray())
            putInt(pcm.size)
        }
        return ByteArrayOutputStream(WAV_HEADER + pcm.size).apply {
            write(header.array())
            write(pcm)
        }.toByteArray()
    }

    /** The loudest sample of [pcm], 0 to 1: what speech detection works on. */
    fun peak(pcm: ByteArray, length: Int = pcm.size): Float {
        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var loudest = 0
        repeat(length / BYTES_PER_SAMPLE) { loudest = maxOf(loudest, kotlin.math.abs(buffer.short.toInt())) }
        return (loudest / FULL_SCALE).toFloat().coerceIn(0f, 1f)
    }

    /** How loud [pcm] is, 0 to 1 (root mean square of the samples). */
    fun level(pcm: ByteArray, length: Int = pcm.size): Float {
        val samples = length / BYTES_PER_SAMPLE
        if (samples == 0) return 0f
        val buffer = ByteBuffer.wrap(pcm, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(samples) {
            val sample = buffer.short / FULL_SCALE
            sum += sample * sample
        }
        return sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
    }

    private const val WAV_HEADER = 44
    private const val RIFF_PREFIX = 8
    private const val FMT_SIZE = 16
    private const val PCM_FORMAT: Short = 1
    private const val BITS = 16
    private const val FULL_SCALE = 32768.0
}

/**
 * The microphone, read while the flow is collected and released when it stops. The caller holds
 * the record-audio permission; recognition audio (no automatic gain), as speech-to-text wants it.
 */
object MicCapture {
    private const val FRAME_MS = 100

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(sampleRate: Int = Pcm.MIC_RATE): Flow<MicFrame> = flow {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val frameBytes = sampleRate / (MILLIS / FRAME_MS) * 2
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, frameBytes * 2),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicUnavailableException()
        }
        try {
            record.startRecording()
            val buffer = ByteArray(frameBytes)
            while (currentCoroutineContext().isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw MicUnavailableException()
                if (read > 0) emit(MicFrame(buffer.copyOf(read), Pcm.level(buffer, read), Pcm.peak(buffer, read)))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    private const val MILLIS = 1000
}

/** Another app holds the microphone, or the phone has none. */
class MicUnavailableException : IllegalStateException("The microphone isn't available")

/** Streams PCM to the speaker as it arrives; speed can change while it plays. */
class PcmPlayer(sampleRate: Int, channels: Int = 1, speed: Float = 1f) {
    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) * BUFFERS,
        )
        .build()

    private val bytesPerFrame = 2 * channels
    private var written = 0L

    init {
        setSpeed(speed)
        track.play()
    }

    /** Queues [pcm]; returns when the player took it all. */
    suspend fun write(pcm: ByteArray) = withContext(Dispatchers.IO) {
        var offset = 0
        while (offset < pcm.size) {
            val wrote = track.write(pcm, offset, pcm.size - offset)
            if (wrote <= 0) break
            offset += wrote
        }
        written += offset
    }

    fun setSpeed(speed: Float) {
        runCatching { track.playbackParams = PlaybackParams().setSpeed(speed) }
    }

    fun pause() = track.pause()

    fun resume() = track.play()

    /** Waits until what was written has been heard. */
    suspend fun drain() = withContext(Dispatchers.IO) {
        val frames = written / bytesPerFrame
        while (track.playState != AudioTrack.PLAYSTATE_STOPPED && track.playbackHeadPosition < frames) {
            kotlinx.coroutines.delay(DRAIN_POLL_MS)
            if (track.playState == AudioTrack.PLAYSTATE_PAUSED) continue
        }
    }

    fun release() {
        runCatching { track.stop() }
        track.release()
    }

    private companion object {
        const val BUFFERS = 4
        const val DRAIN_POLL_MS = 50L
    }
}
