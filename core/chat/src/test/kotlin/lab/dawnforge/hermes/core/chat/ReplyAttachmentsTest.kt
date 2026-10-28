package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.ToolStatus

class ReplyAttachmentsTest {
    private fun user(key: String, steer: Boolean = false) = ChatItem.User(key, "hi", isSteer = steer)

    private fun reply(key: String, text: String = "Here it is.", streaming: Boolean = false) =
        ChatItem.Assistant(key, text, streaming = streaming)

    private fun speech(key: String, vararg paths: String, status: ToolStatus = ToolStatus.Complete) = ChatItem.Tool(
        key = key,
        callId = key,
        name = "text_to_speech",
        summary = null,
        input = null,
        output = """{"success": true, "file_path": ${JsonPrimitive(paths.first())}, """ +
            """"file_paths": [${paths.joinToString { JsonPrimitive(it).toString() }}]}""",
        status = status,
    )

    private fun tool(key: String, name: String, output: String) =
        ChatItem.Tool(key, key, name, null, null, output, ToolStatus.Complete)

    @Test
    fun `a voice message comes with the reply, as on Telegram`() {
        // The Pixel test, 2026-10-25: skill_view, text_to_speech, then a reply that names no file.
        val items = listOf(
            user("u1"),
            tool("t1", "skill_view", """{"success": true, "name": "fish_audio:voice-messages"}"""),
            speech("t2", "C:\\Users\\me\\hermes\\cache\\audio\\tts_1.ogg"),
            reply("a1", "Voice message is on its way to you as a voice bubble."),
        )

        assertEquals(mapOf("a1" to listOf("C:\\Users\\me\\hermes\\cache\\audio\\tts_1.ogg")), replyAttachments(items))
    }

    @Test
    fun `the turn's last reply carries all its audio, in order`() {
        val items = listOf(
            user("u1"),
            reply("a1", "Making two."),
            speech("t1", "/a/1.ogg"),
            speech("t2", "/a/2.ogg", "/a/3.ogg"),
            reply("a2", "Both sent."),
        )

        assertEquals(mapOf("a2" to listOf("/a/1.ogg", "/a/2.ogg", "/a/3.ogg")), replyAttachments(items))
    }

    @Test
    fun `audio stays with its own turn`() {
        val items = listOf(
            user("u1"),
            speech("t1", "/a/1.ogg"),
            reply("a1"),
            user("u2"),
            reply("a2", "Just text this time."),
        )

        assertEquals(mapOf("a1" to listOf("/a/1.ogg")), replyAttachments(items))
    }

    @Test
    fun `a steer mid-turn doesn't end the turn`() {
        val items = listOf(user("u1"), speech("t1", "/a/1.ogg"), user("s1", steer = true), reply("a1"))

        assertEquals(mapOf("a1" to listOf("/a/1.ogg")), replyAttachments(items))
    }

    @Test
    fun `audio the reply already names isn't attached twice`() {
        val items = listOf(
            user("u1"),
            speech("t1", "/a/1.ogg", "/a/2.ogg"),
            reply("a1", "Here:\n[[audio_as_voice]]\nMEDIA:/a/1.ogg"),
        )

        assertEquals(mapOf("a1" to listOf("/a/2.ogg")), replyAttachments(items))
    }

    @Test
    fun `nothing until the reply after the audio exists`() {
        assertEquals(
            emptyMap(),
            replyAttachments(listOf(user("u1"), reply("a1", "Making it."), speech("t1", "/a/1.ogg"))),
        )
        assertEquals(
            emptyMap(),
            replyAttachments(listOf(user("u1"), speech("t1", "/a/1.ogg", status = ToolStatus.Running), reply("a1"))),
        )
    }

    @Test
    fun `a reply still being written gets its audio when it's done`() {
        val items = listOf(user("u1"), speech("t1", "/a/1.ogg"), reply("a1", "Sending", streaming = true))

        assertEquals(emptyMap(), replyAttachments(items))
        assertEquals(mapOf("a1" to listOf("/a/1.ogg")), replyAttachments(items.dropLast(1) + reply("a1", "Sent")))
    }

    @Test
    fun `other tools' files and failed calls attach nothing`() {
        val items = listOf(
            user("u1"),
            tool("t1", "terminal", """{"output": "/a/1.ogg"}"""),
            tool("t2", "text_to_speech", """{"success": false, "error": "Fish Audio answered 402"}"""),
            reply("a1"),
        )

        assertEquals(emptyMap(), replyAttachments(items))
    }

    @Test
    fun `a chat loaded from its middle attaches what it can see`() {
        val items = listOf(speech("t1", "/a/1.ogg"), reply("a1"), user("u2"), reply("a2"))

        assertEquals(mapOf("a1" to listOf("/a/1.ogg")), replyAttachments(items))
    }
}
