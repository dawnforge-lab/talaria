package lab.dawnforge.hermes.core.push

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E
import lab.dawnforge.hermes.core.push.relay.RelayClient
import lab.dawnforge.hermes.core.push.relay.RelayInstallation
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class PushAnswersTest {
    private val server = MockWebServer().apply { start() }
    private val store = testPushStore()
    private val dashboard = DashboardId("3f1c3c1e-5b2a-4c1e-9f1a-0a1b2c3d4e5f")
    private val secret = ByteArray(NotificationE2E.SECRET_BYTES) { it.toByte() }
    private val kid = "0123456789abcdef0123456789abcdef"
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var gatewayReply: () -> Any? = { buildJsonObject { put("resolved", 1) } }
    private val answers = PushAnswers(
        store = store,
        relay = RelayClient(OkHttpClient()),
        gateway = GatewayCall { _, _, method, params ->
            calls += method to params
            gatewayReply()
        },
        relayUrl = { server.url("/") },
    )

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `an approval goes to the gateway by the chat's stored id, and says when nothing waited`() = runBlocking {
        assertEquals(AnswerOutcome.Answered, answers.approve(dashboard, "default", "20261022_005315_89efbf", "once"))
        assertEquals("approval.respond", calls.single().first)
        assertEquals("20261022_005315_89efbf", calls.single().second["session_id"]!!.jsonPrimitive.content)
        assertEquals("once", calls.single().second["choice"]!!.jsonPrimitive.content)

        gatewayReply = { buildJsonObject { put("resolved", 0) } }
        assertEquals(AnswerOutcome.AlreadySettled, answers.approve(dashboard, "default", "s", "deny"))

        gatewayReply = { throw IllegalStateException("Sign in to the dashboard again") }
        assertIs<AnswerOutcome.Failed>(answers.approve(dashboard, "default", "s", "deny"))
        Unit
    }

    @Test
    fun `a question's answer is sealed for the plugin and handed to the relay`() = runBlocking {
        pair()
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"answered"}""").build())
        assertEquals(AnswerOutcome.Answered, answers.answer("gw-1", "talaria-push-abc123def456", "q0", "Yes, ship it"))

        val request = server.takeRequest()
        assertEquals("/v1/decisions/talaria-push-abc123def456/respond", request.url.encodedPath)
        assertEquals("Bearer inst-1.secret", request.headers["Authorization"])
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("gw-1", body["gateway_id"]!!.jsonPrimitive.content)
        assertEquals("q0", body["question_id"]!!.jsonPrimitive.content)
        assertEquals("Yes, ship it", open(body["answer"]!!.jsonPrimitive.content, "talaria-push-abc123def456", "q0"))
    }

    @Test
    fun `an answer to a question settled elsewhere says so, and other refusals can be retried`() = runBlocking {
        pair()
        for (code in listOf(404, 409, 410)) {
            server.enqueue(MockResponse.Builder().code(code).body("""{"error":"x"}""").build())
            assertEquals(
                AnswerOutcome.AlreadySettled,
                answers.answer("gw-1", "talaria-push-x", "", "No"),
                "status $code",
            )
        }
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"down"}""").build())
        assertIs<AnswerOutcome.Failed>(answers.answer("gw-1", "talaria-push-x", "", "No"))
        // A forgotten pairing can't answer at all.
        assertIs<AnswerOutcome.Failed>(answers.answer("gw-unknown", "talaria-push-x", "", "No"))
        Unit
    }

    @Test
    fun `an answer survives the trip through work data`() {
        val answer = NotificationAnswer(
            tag = "gw:gw-1:s1",
            id = 1,
            title = "Deploy?",
            target = HermesNotification.Target(dashboard, "work", null),
            kind = NotificationAnswer.Kind.Question,
            subject = "talaria-push-abc",
            gatewayId = "gw-1",
            questionId = "",
            answer = "Yes",
        )
        assertEquals(answer, NotificationAnswer.from(answer.toData()))
    }

    private suspend fun pair() {
        store.saveInstallation(RelayInstallation("inst-1", "inst-1.secret"), "fid")
        store.savePairing(PushStore.Pairing(dashboard, "work", "gw-1", null, kid), secret)
    }

    /** The plugin's side of `seal_answer`: what it opens with the pairing's answer key. */
    private fun open(sealed: String, requestId: String, questionId: String): String {
        val (prefix, sealedKid, nonce, ciphertext) = sealed.split('.')
        assertEquals("e2e1", prefix)
        assertEquals(kid, sealedKid)
        val keys = NotificationE2E.deriveKeys(kid, secret)
        val aad = listOf(
            "talaria-e2e/1",
            "answer",
            "kid=$kid",
            "inst=inst-1",
            "gw=gw-1",
            "req=$requestId",
            "qid=$questionId",
        )
            .joinToString("\n").toByteArray()
        val plain = InsecureNonceChaCha20Poly1305(keys.answer)
            .decrypt(NotificationE2E.unb64u(nonce), NotificationE2E.unb64u(ciphertext), aad)
        return plain.decodeToString()
    }
}
