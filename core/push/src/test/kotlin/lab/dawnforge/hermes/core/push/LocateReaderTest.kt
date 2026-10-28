package lab.dawnforge.hermes.core.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E

/** Hermes asking for the location now, sealed by the plugin's own e2e.py (locate-vector.json). */
class LocateReaderTest {
    private fun resource(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource(name)).readText()).jsonObject

    private val vectors = resource("/e2e-vectors.json")
    private val locate = resource("/locate-vector.json")
    private val installation = vectors["installation_id"]!!.jsonPrimitive.content
    private val gateway = vectors["gateway_id"]!!.jsonPrimitive.content
    private val keys = NotificationE2E.deriveKeys(
        vectors["kid"]!!.jsonPrimitive.content,
        NotificationE2E.unb64u(vectors["secret"]!!.jsonPrimitive.content),
    )
    private val dashboard = DashboardId("3f1c3c1e-5b2a-4c1e-9f1a-0a1b2c3d4e5f")
    private val envelope = locate["envelope"]!!.jsonObject
    private val issued = envelope["iat"]!!.jsonPrimitive.long

    private fun data(type: String = "location.requested", sealed: JsonObject? = envelope) = buildMap {
        put(
            "talaria",
            buildJsonObject {
                put("type", type)
                put("e2e", 1)
                put("gateway_id", gateway)
            }.toString(),
        )
        sealed?.let { put("talaria_e2e", it.toString()) }
    }

    private suspend fun read(data: Map<String, String>, nowSeconds: Long = issued + 2, known: Boolean = true) =
        IncomingPushReader.readLocate(data, installation, nowSeconds) { id ->
            if (known && id == gateway) PairingKeys(dashboard, "default", keys) else null
        }

    @Test
    fun `a sealed request from a paired host is read`() = runTest {
        assertEquals(LocateRequest(dashboard, "default", "l_vectorLocate0123456789"), read(data()))
    }

    @Test
    fun `a stale, tampered, plaintext or unknown host's request isn't answered`() = runTest {
        assertNull(read(data(), nowSeconds = issued + 120), "older than a minute")
        val tampered = JsonObject(envelope + ("tok" to JsonPrimitive("0000000000000000")))
        assertNull(read(data(sealed = tampered)), "a field bound to the seal changed")
        assertNull(read(data(sealed = null)), "plaintext")
        assertNull(read(data(type = "response.ready")), "another type")
        assertNull(read(data(), known = false), "a host this phone isn't paired with")
    }
}
