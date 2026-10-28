package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolResultFilesTest {
    @Test
    fun `a voice message's audio is the file the row plays`() {
        // As Hermes stored it on the Pixel test, 2026-10-25.
        val output =
            """{"success": true, "file_path": "C:\\Users\\me\\AppData\\Local\\hermes\\cache\\audio\\tts_1.ogg", """ +
                """"file_paths": ["C:\\Users\\me\\AppData\\Local\\hermes\\cache\\audio\\tts_1.ogg"], """ +
                """"media_tag": "[[audio_as_voice]]\nMEDIA:C:\\Users\\me\\tts_1.ogg", "provider": "fish_audio"}"""

        assertEquals(
            listOf("C:\\Users\\me\\AppData\\Local\\hermes\\cache\\audio\\tts_1.ogg"),
            toolResultFiles("text_to_speech", output),
        )
    }

    @Test
    fun `a long reading plays each part in order`() {
        val output = """{"success": true, "file_path": "/a/1.mp3", "file_paths": ["/a/1.mp3", "/a/2.mp3"]}"""

        assertEquals(listOf("/a/1.mp3", "/a/2.mp3"), toolResultFiles("text_to_speech", output))
    }

    @Test
    fun `the first part alone when the parts aren't all files`() {
        assertEquals(
            listOf("/home/me/.hermes/audio/reply.mp3"),
            toolResultFiles("text_to_speech", """{"success": true, "file_path": "/home/me/.hermes/audio/reply.mp3"}"""),
        )
        assertEquals(
            listOf("~/voice-memos/a.ogg"),
            toolResultFiles(
                "text_to_speech",
                """{"file_path": "~/voice-memos/a.ogg", "file_paths": ["~/voice-memos/a.ogg", 3]}""",
            ),
        )
    }

    @Test
    fun `only paths on the host are drawn`() {
        listOf(
            "audio/reply.mp3",
            "https://example.com/a.mp3",
            "/a\n/b.mp3",
            "/a/`b`.mp3",
            "",
            "data:audio/mpeg;base64,AA",
        )
            .forEach { path ->
                val output = """{"file_path": ${kotlinx.serialization.json.JsonPrimitive(path)}}"""
                assertNull(toolResultFiles("text_to_speech", output), path)
            }
    }

    @Test
    fun `other tools, failures and other shapes draw nothing`() {
        assertNull(toolResultFiles("terminal", """{"file_path": "/a/1.mp3"}"""))
        assertNull(toolResultFiles("text_to_speech", """{"success": false, "error": "Text is required"}"""))
        assertNull(toolResultFiles("text_to_speech", "Saved to /a/1.mp3"))
        assertNull(toolResultFiles("text_to_speech", null))
        assertNull(toolResultFiles("text_to_speech", "{not json"))
    }

    @Test
    fun `the tool's name is matched as Hermes may write it`() {
        assertEquals(listOf("/a/1.mp3"), toolResultFiles(" Text_To_Speech ", """{"file_path": "/a/1.mp3"}"""))
    }

    @Test
    fun `a very large result is left raw`() {
        val output = """{"file_path": "/a/1.mp3", "pad": "${"x".repeat(70_000)}"}"""

        assertNull(toolResultFiles("text_to_speech", output))
    }
}
