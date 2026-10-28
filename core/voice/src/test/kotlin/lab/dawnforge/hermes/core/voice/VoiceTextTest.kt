package lab.dawnforge.hermes.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTextTest {
    @Test
    fun `a recording becomes a WAV file Hermes can read`() {
        val pcm = ByteArray(3200) { (it % 7).toByte() }
        val wav = Pcm.wav(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, header.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16_000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, header.getInt(40))
        assertEquals(44 + pcm.size, wav.size)
    }

    @Test
    fun `silence is quiet and a full-scale tone is loud`() {
        assertEquals(0f, Pcm.level(ByteArray(320)))
        val loud = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        repeat(160) { loud.putShort(if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE) }
        assertTrue(Pcm.level(loud.array()) > 0.99f)
    }

    @Test
    fun `speech is cut into sentences`() {
        assertEquals(
            listOf("Hello there.", "How are you?", "Fine!", "A new line"),
            Sentences.of("Hello there. How are you?  Fine!\nA new line"),
        )
    }

    @Test
    fun `a reply is read without Markdown, code or media`() {
        val reply = "# Plan\n\n- Buy **milk**\n- See [the doc](https://x.y)\n\n" +
            "```kotlin\nval x = 1\n```\nMEDIA:/tmp/a.png\n> Quoted"
        val spoken = ReadAloud.forSpeech(reply)

        assertTrue("Plan" in spoken && "Buy milk" in spoken && "See the doc" in spoken && "Quoted" in spoken, spoken)
        assertTrue("```" !in spoken && "val x" !in spoken && "MEDIA" !in spoken && "#" !in spoken, spoken)
    }
}
