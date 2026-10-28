package lab.dawnforge.hermes.core.push.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Checked against vectors the plugin's own `e2e.py` wrote (`e2e_vectors.py` beside them, with
 * fixed nonces), so the two sides agree byte for byte.
 */
class NotificationE2ETest {
    private val vectors = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/e2e-vectors.json")).readText(),
    ).jsonObject
    private val installation = vectors.text("installation_id")
    private val gateway = vectors.text("gateway_id")
    private val keys = NotificationE2E.deriveKeys(vectors.text("kid"), NotificationE2E.unb64u(vectors.text("secret")))
    private val events = vectors["events"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `keys derive as the plugin derives them`() {
        val expected = vectors["keys"]!!.jsonObject
        assertEquals(expected.text("push"), keys.push.hex())
        assertEquals(expected.text("answer"), keys.answer.hex())
        assertEquals(expected.text("meta"), keys.meta.hex())
        val thread = vectors["thread_token"]!!.jsonObject
        assertEquals(thread.text("token"), NotificationE2E.threadToken(keys, thread.text("seed")))
    }

    @Test
    fun `every sealed event opens to what the plugin sealed, compressed or not`() {
        assertEquals(setOf(0, 1), events.map { it.envelope().z }.toSet())
        for (event in events) {
            val opened = NotificationE2E.open(event.envelope(), event.text("type"), keys, installation, gateway)
            assertEquals(event["inner"], opened, event.text("type"))
        }
    }

    @Test
    fun `a push moved to another pairing, retyped or altered does not open`() {
        val event = events.first()
        val envelope = event.envelope()
        val type = event.text("type")
        val tries = listOf(
            { NotificationE2E.open(envelope, type, keys, "99999999-2222-3333-4444-555555555555", gateway) },
            { NotificationE2E.open(envelope, type, keys, installation, "another-gateway") },
            { NotificationE2E.open(envelope, "approval.needed", keys, installation, gateway) },
            { NotificationE2E.open(envelope.copy(iat = envelope.iat + 1), type, keys, installation, gateway) },
            { NotificationE2E.open(envelope.copy(tok = "0000000000000000"), type, keys, installation, gateway) },
            { NotificationE2E.open(envelope.copy(req = "talaria-push-x"), type, keys, installation, gateway) },
            { NotificationE2E.open(envelope.copy(z = 1 - envelope.z), type, keys, installation, gateway) },
            {
                val ct = envelope.ciphertext
                val flipped = ct.dropLast(1) + (if (ct.last() == 'A') 'B' else 'A')
                NotificationE2E.open(envelope.copy(ciphertext = flipped), type, keys, installation, gateway)
            },
        )
        for (attempt in tries) assertFailsWith<E2EException> { attempt() }
    }

    @Test
    fun `another pairing's key, a wrong key id and malformed envelopes are refused`() {
        val event = events.first()
        val other = NotificationE2E.deriveKeys(vectors.text("kid"), ByteArray(NotificationE2E.SECRET_BYTES) { 7 })
        assertFailsWith<E2EException> {
            NotificationE2E.open(event.envelope(), event.text("type"), other, installation, gateway)
        }
        val foreignKid = NotificationE2E.deriveKeys("f".repeat(32), NotificationE2E.unb64u(vectors.text("secret")))
        assertFailsWith<E2EException> {
            NotificationE2E.open(event.envelope(), event.text("type"), foreignKid, installation, gateway)
        }
        assertFailsWith<E2EException> { NotificationE2E.parseEnvelope("{\"v\":1}") }
        assertFailsWith<E2EException> { NotificationE2E.parseEnvelope("not json") }
        assertFailsWith<E2EException> { NotificationE2E.deriveKeys("XYZ", ByteArray(32)) }
        assertFailsWith<E2EException> { NotificationE2E.deriveKeys(vectors.text("kid"), ByteArray(16)) }
        assertFailsWith<E2EException> { NotificationE2E.unb64u("a") }
        assertFailsWith<E2EException> { NotificationE2E.unb64u("a+b/") }
    }

    @Test
    fun `a clarify answer seals as the plugin seals it`() {
        val answer = vectors["answer"]!!.jsonObject
        val expected = answer.text("sealed")
        // The vector's nonce is the one in the sealed answer.
        val nonce = NotificationE2E.unb64u(expected.split('.')[2])
        val sealed = NotificationE2E.sealAnswer(
            answer = answer.text("text"),
            keys = keys,
            installationId = installation,
            gatewayId = gateway,
            requestId = answer.text("request_id"),
            questionId = answer.text("question_id"),
            nonce = nonce,
        )
        assertEquals(expected, sealed)
    }

    @Test
    fun `the relay's envelope JSON parses as sent`() {
        val raw = events.first()["envelope"].toString()
        val parsed = NotificationE2E.parseEnvelope(raw)
        assertEquals(events.first()["envelope"]!!.jsonObject["iat"]!!.jsonPrimitive.long, parsed.iat)
        assertEquals(events.first().envelope(), parsed)
    }

    private fun JsonObject.envelope() = NotificationE2E.parseEnvelope(this["envelope"].toString())

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
