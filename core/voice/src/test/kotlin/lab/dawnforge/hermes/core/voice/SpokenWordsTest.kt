package lab.dawnforge.hermes.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpokenWordsTest {
    @Test
    fun `a command is the whole utterance, with courtesies and repeats`() {
        assertTrue(SpokenCommands.matches("Goodbye.", SpokenCommands.END))
        assertTrue(SpokenCommands.matches("Okay, goodbye!", SpokenCommands.END))
        assertTrue(SpokenCommands.matches("Bye bye", SpokenCommands.END))
        assertTrue(SpokenCommands.matches("That’s all, thanks", SpokenCommands.END))
        assertTrue(SpokenCommands.matches("Stop talking", SpokenCommands.STOP))
        assertFalse(SpokenCommands.matches("goodbye to the old server", SpokenCommands.END))
        assertFalse(SpokenCommands.matches("stop the build", SpokenCommands.STOP))
        assertFalse(SpokenCommands.matches("", SpokenCommands.STOP))
        assertFalse(SpokenCommands.matches("anything", emptyList()))
    }

    @Test
    fun `only a plain yes or no answers`() {
        assertEquals(SpokenAnswer.Answer.Yes, SpokenAnswer.of("Yes"))
        assertEquals(SpokenAnswer.Answer.Yes, SpokenAnswer.of("Yeah, go ahead please."))
        assertEquals(SpokenAnswer.Answer.Yes, SpokenAnswer.of("Sure, thanks"))
        assertEquals(SpokenAnswer.Answer.No, SpokenAnswer.of("No thanks"))
        assertEquals(SpokenAnswer.Answer.No, SpokenAnswer.of("Never mind"))
        assertEquals(SpokenAnswer.Answer.Other, SpokenAnswer.of("No, make it five"))
        assertEquals(SpokenAnswer.Answer.Other, SpokenAnswer.of("Yes, but don't send it"))
        assertEquals(SpokenAnswer.Answer.Other, SpokenAnswer.of("Yes?"))
        assertEquals(SpokenAnswer.Answer.Other, SpokenAnswer.of("Go to the store"))
    }

    @Test
    fun `a confirmation's own phrases confirm, never with a refusal`() {
        val phrases = listOf("send it")
        assertTrue(SpokenAnswer.confirms("Send it", phrases))
        assertTrue(SpokenAnswer.confirms("Yes, send it please", phrases))
        assertTrue(SpokenAnswer.confirms("yes", phrases))
        assertFalse(SpokenAnswer.confirms("Don't send it", phrases))
        assertFalse(SpokenAnswer.confirms("send it to Ana instead", phrases))
    }

    @Test
    fun `the detector learns the room and hears speech above it`() {
        val detector = SpeechDetector()
        // A quiet room through cold start.
        repeat(20) { assertEquals(SpeechDetector.Detection.None, detector.observe(0.004f)) }
        // A modest rise needs two samples in a row.
        assertEquals(SpeechDetector.Detection.None, detector.observe(0.03f))
        assertEquals(SpeechDetector.Detection.Started, detector.observe(0.03f))
        assertEquals(SpeechDetector.Detection.Continued, detector.observe(0.02f))
        assertEquals(SpeechDetector.Detection.None, detector.observe(0.003f))
        detector.endUtterance()
        // A loud sample is speech at once.
        assertEquals(SpeechDetector.Detection.Started, detector.observe(0.2f))
    }

    @Test
    fun `steady noise at cold start becomes the floor, not speech`() {
        val detector = SpeechDetector()
        repeat(SpeechDetector.COLD_START_EVENTS) {
            assertEquals(SpeechDetector.Detection.None, detector.observe(0.03f))
        }
        assertTrue(detector.noiseFloor > 0.02f)
        assertEquals(SpeechDetector.Detection.None, detector.observe(0.035f))
    }
}
