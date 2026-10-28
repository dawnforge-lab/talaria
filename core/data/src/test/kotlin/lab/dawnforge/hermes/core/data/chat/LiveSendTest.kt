package lab.dawnforge.hermes.core.data.chat

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.normalizeHistory
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * Sends a real message with an image and a PDF attached, through the app's ChatRepository, and
 * checks that both reached the agent: the stored message refers to them, and the agent answered.
 * Runs only with `-Phermes.live.turn=<dir>` (it calls the model) and deletes its chat afterwards.
 */
class LiveSendTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `an image and a PDF reach the agent`(): Unit = runBlocking {
        assumeTrue(
            "set -Phermes.live.turn to run a real turn",
            LiveHermes.config != null && System.getProperty("hermes.live.turn") != null,
        )
        val live = LiveHermes.connect(scope)
        val created = live.gateway.call("session.create", buildJsonObject { put("profile", Profile.DEFAULT) })
            as JsonObject
        val runtimeId = assertNotNull((created["session_id"] as? JsonPrimitive)?.contentOrNull)
        var storedId = (created["stored_session_id"] as? JsonPrimitive)?.contentOrNull
        live.gateway.trackSession(runtimeId, storedId)
        // Approvals are not this test's business: decline, so the turn never waits on one.
        val declining = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            live.gateway.serverRequests.collect { live.gateway.decline(it) }
        }
        try {
            assertIs<NetResult.Success<Unit>>(live.chats.attachImage(runtimeId, redSquarePng(), "red-square.png"))
            val reference = assertIs<NetResult.Success<String>>(
                live.chats.attachFile(runtimeId, onePagePdf(PDF_TEXT), "android-check.pdf", "application/pdf"),
            ).value
            println("live: attached an image, and the PDF as $reference")
            assertTrue(reference.startsWith("@file:"), reference)

            val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
                live.gateway.events.first { event ->
                    event.sessionId == runtimeId && event.type == "session.info" &&
                        (event.payload?.get("running") as? JsonPrimitive)?.booleanOrNull == false
                }
            }
            val sent = assertIs<NetResult.Success<Sent>>(live.chats.send(runtimeId, "$reference\n\n$PROMPT")).value
            println("live: sent, $sent")
            assertEquals(SendStatus.Streaming, sent.status)
            val info = withTimeout(TURN_TIMEOUT_MS) { ended.await() }
            storedId = storedId ?: (info.payload?.get("stored_session_id") as? JsonPrimitive)?.contentOrNull
            val stored = assertNotNull(storedId)

            val page = assertIs<NetResult.Success<HistoryPage>>(
                live.chats.history(live.id, Profile.DEFAULT, stored),
            ).value
            val items = normalizeHistory(page.rows)
            val question = items.filterIsInstance<ChatItem.User>().last()
            println("live: stored message ${question.text.take(160)} with ${question.attachments.map { it.name }}")
            assertTrue(question.attachments.any { !it.isImage && it.name.endsWith(".pdf") }, "the PDF is attached")
            assertTrue(question.attachments.any { it.isImage }, "the image is attached")
            sent.userRowId?.let { assertTrue(items.any { item -> item.key == "row-$it" }, "user_row_id is that row") }
            val reply = items.filterIsInstance<ChatItem.Assistant>().lastOrNull()?.text
            println("live: the agent answered: ${reply?.take(300)}")
            assertNotNull(reply, "the agent answered")
        } finally {
            declining.cancel()
            storedId?.let { assertIs<NetResult.Success<Unit>>(live.sessions.delete(live.id, Profile.DEFAULT, it)) }
            println("live: deleted the test chat")
            live.gateway.stop()
        }
    }

    private fun redSquarePng(): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.RED
            fillRect(0, 0, 64, 64)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** The smallest PDF a reader accepts: one page with one line of text. */
    private fun onePagePdf(text: String): ByteArray {
        val content = "BT /F1 18 Tf 72 720 Td ($text) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            out.length.also { out.append("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val PDF_TEXT = "hermes-android-pdf-check"
        const val PROMPT = "This is an automated test of a mobile client. In one short line each: what colour is " +
            "the attached image, and what text is written in the attached PDF?"
        const val TURN_TIMEOUT_MS = 240_000L
    }
}
