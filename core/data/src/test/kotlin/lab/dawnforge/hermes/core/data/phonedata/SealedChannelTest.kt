package lab.dawnforge.hermes.core.data.phonedata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.phonedata.SealedChannel.Companion.b64u
import lab.dawnforge.hermes.core.data.phonedata.SealedChannel.Companion.unb64u

/** The plugin's own test vectors (the `_vector.json` files in its tests): Android must seal exactly as iOS does. */
class SealedChannelTest {
    private fun vector(name: String): JsonObject {
        val text = checkNotNull(javaClass.getResource("/phonedata/$name")).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private class Keys(
        val channel: SealedChannel,
        val deviceId: String,
        val shared: ByteArray,
        val devicePublic: ByteArray,
        val hostPublic: ByteArray,
    ) {
        fun key(purpose: String) = channel.key(shared, deviceId, devicePublic, hostPublic, purpose)
    }

    private fun keys(feature: PhoneDataFeature, v: JsonObject): Keys {
        val channel = SealedChannel(feature)
        val devicePrivate = unb64u(v.text("device_private"))
        val devicePublic = unb64u(v.text("device_public"))
        val hostPublic = unb64u(v.text("host_public"))
        assertEquals(v.text("device_public"), b64u(SealedChannel.publicKey(devicePrivate)))
        assertEquals(v.text("kid"), channel.keyId(devicePublic, hostPublic))
        return Keys(channel, v.text("device_id"), channel.agree(devicePrivate, hostPublic), devicePublic, hostPublic)
    }

    private fun assertIngest(keys: Keys, v: JsonObject, batchId: String) {
        val key = keys.key("ingest device-to-host")
        assertEquals(v.text("key"), hex(key))
        val aad = keys.channel.ingestAad(v.text("kid").ifEmpty { "" }, keys.deviceId, batchId)
        assertEquals(v.text("aad"), aad.decodeToString())
        val sealed = keys.channel.seal(key, v.text("plaintext").toByteArray(), aad, unb64u(v.text("nonce")))
        assertEquals(v.text("ct"), sealed.ciphertext)
        assertEquals(v.text("plaintext"), keys.channel.open(key, sealed, aad).decodeToString())
    }

    @Test
    fun `phone ingest and exchanges match the plugin`() {
        val v = vector("phone_vector.json")
        val keys = keys(PhoneDataFeature.Phone, v)
        assertEquals(v.text("shared"), hex(keys.shared))
        val ingest = v.obj("ingest")
        assertIngest(keys, JsonObject(ingest + ("kid" to v.getValue("kid"))), ingest.text("batch_id"))

        val request = v.obj("exchange_request")
        val requestKey = keys.key("exchange device-to-host")
        assertEquals(request.text("key"), hex(requestKey))
        val requestAad = keys.channel.exchangeAad(
            "request",
            "changes",
            v.text("kid"),
            keys.deviceId,
            request.text("call_id"),
        )
        assertEquals(request.text("aad"), requestAad.decodeToString())
        val sealed = keys.channel.seal(
            requestKey,
            request.text("plaintext").toByteArray(),
            requestAad,
            unb64u(request.text("nonce")),
        )
        assertEquals(request.text("ct"), sealed.ciphertext)

        val answer = v.obj("exchange_answer")
        val answerKey = keys.key("exchange host-to-device")
        assertEquals(answer.text("key"), hex(answerKey))
        val answerAad = keys.channel.exchangeAad(
            "answer",
            "changes",
            v.text("kid"),
            keys.deviceId,
            answer.text("call_id"),
        )
        val opened = keys.channel.open(answerKey, Sealed(answer.text("nonce"), answer.text("ct")), answerAad)
        assertEquals(answer.text("plaintext"), opened.decodeToString())
    }

    @Test
    fun `health ingest matches the plugin`() {
        val v = vector("health_vector.json")
        assertIngest(keys(PhoneDataFeature.Health, v), v, v.text("batch_id"))
    }

    @Test
    fun `location ingest matches the plugin`() {
        val v = vector("location_vector.json")
        assertIngest(keys(PhoneDataFeature.Location, v), v, v.text("batch_id"))
    }

    @Test
    fun `a body sealed for one purpose doesn't open as another`() {
        val v = vector("phone_vector.json")
        val keys = keys(PhoneDataFeature.Phone, v)
        val key = keys.key("ingest device-to-host")
        val sealed = keys.channel.seal(key, "x".toByteArray(), keys.channel.ingestAad("k", "d", "b1"))
        assertFailsWith<java.security.GeneralSecurityException> {
            keys.channel.open(key, sealed, keys.channel.ingestAad("k", "d", "b2"))
        }
    }
}
