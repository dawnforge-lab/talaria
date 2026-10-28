package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ResponseHapticsTest {
    private fun signal(type: String, payload: String = "{}") =
        HapticSignal.of(type, Json.parseToJsonElement(payload).jsonObject)

    @Test
    fun `events map to signals as on iOS`() {
        assertEquals(HapticSignal.Activity(playsStart = false), signal("message.start"))
        assertEquals(HapticSignal.Activity(playsStart = true), signal("message.delta", """{"text": "Hi"}"""))
        assertNull(signal("message.delta", """{"text": ""}"""))
        assertEquals(HapticSignal.Tool, signal("tool.start", """{"name": "terminal"}"""))
        assertNull(signal("tool.start", """{"name": "clarify"}"""))
        assertEquals(HapticSignal.Ended, signal("message.complete", """{"status": "complete"}"""))
        assertEquals(HapticSignal.Failure, signal("message.complete", """{"status": "error"}"""))
        assertEquals(HapticSignal.Ended, signal("message.complete", """{"status": "error", "partial": true}"""))
        assertEquals(HapticSignal.Reset, signal("message.complete", """{"status": "interrupted"}"""))
        assertEquals(HapticSignal.Failure, signal("error"))
        assertNull(signal("session.info"))
    }

    @Test
    fun `a reply starts once, ticks per tool, and ends after a pause`() {
        val haptics = ResponseHaptics()
        assertEquals(emptyList(), haptics.on(HapticSignal.Activity(false), 0).first)
        assertEquals(listOf(HapticEffect.ResponseStarted), haptics.on(HapticSignal.Activity(true), 10).first)
        assertEquals(emptyList(), haptics.on(HapticSignal.Activity(true), 20).first)
        assertEquals(listOf(HapticEffect.ToolStarted), haptics.on(HapticSignal.Tool, 100).first)
        // Tools closer together than the gap tick once.
        assertEquals(emptyList(), haptics.on(HapticSignal.Tool, 200).first)
        assertEquals(listOf(HapticEffect.ToolStarted), haptics.on(HapticSignal.Tool, 400).first)
        val token = haptics.on(HapticSignal.Ended, 500).second!!
        assertEquals(HapticEffect.ResponseConcluded, haptics.conclude(token))
        // Once only.
        assertNull(haptics.conclude(token))
    }

    @Test
    fun `activity after the end cancels it, and a failure or stop is felt only mid-turn`() {
        val haptics = ResponseHaptics()
        haptics.on(HapticSignal.Activity(true), 0)
        val token = haptics.on(HapticSignal.Ended, 10).second!!
        haptics.on(HapticSignal.Activity(false), 20)
        assertNull(haptics.conclude(token))

        assertEquals(listOf(HapticEffect.Error), haptics.on(HapticSignal.Failure, 30).first)
        assertEquals(emptyList(), haptics.on(HapticSignal.Failure, 40).first)
        assertNull(haptics.on(HapticSignal.Ended, 50).second)

        haptics.on(HapticSignal.Activity(true), 60)
        assertEquals(emptyList(), haptics.on(HapticSignal.Reset, 70).first)
        assertNull(haptics.on(HapticSignal.Ended, 80).second)
    }
}
