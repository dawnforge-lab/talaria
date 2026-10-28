package lab.dawnforge.hermes.core.data.phonedata

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.FakeCipher
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** The uploader against a stand-in for the plugin's phone routes, which opens what it receives. */
class PhoneDataUploaderTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private lateinit var uploader: PhoneDataUploader
    private val id = DashboardId("dash")
    private val channel = SealedChannel(PhoneDataFeature.Phone)
    private val host = SealedChannel.newKeyPair()

    /** The host's state: the device's registered public key, and what it opened. */
    private var devicePublic: ByteArray? = null
    private var deviceId: String? = null
    private var forgetKeyOnce = false
    private val opened = mutableListOf<JsonObject>()
    private var keyRequests = 0

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun hostKey(purpose: String): ByteArray {
        val public = checkNotNull(devicePublic)
        return channel.key(channel.agree(host.first, public), checkNotNull(deviceId), public, host.second, purpose)
    }

    @BeforeTest
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/plugins/talaria_push/")
                val body = request.body?.utf8()?.takeIf {
                    it.isNotEmpty()
                }?.let { Json.parseToJsonElement(it).jsonObject }
                val kid = devicePublic?.let { channel.keyId(it, host.second) }
                return when (path) {
                    "phone/status" -> json(
                        """{"stored":true,"generation":1,"device":${if (kid == null) "null" else "{\"kid\":\"$kid\"}"}}""",
                    )

                    "phone/key" -> {
                        keyRequests++
                        devicePublic = SealedChannel.unb64u(body!!.getValue("public_key").jsonPrimitive.content)
                        deviceId = body.getValue("device_id").jsonPrimitive.content
                        val newKid = channel.keyId(devicePublic!!, host.second)
                        json(
                            """{"kid":"$newKid","host_public_key":"${SealedChannel.b64u(
                                host.second,
                            )}","generation":1}""",
                        )
                    }

                    "phone/ingest" -> {
                        if (forgetKeyOnce) {
                            forgetKeyOnce = false
                            devicePublic = null
                            return json("""{"detail":"Unknown key","code":"unknown_key"}""", 409)
                        }
                        val envelope = body!!
                        val batch = envelope.getValue("batch_id").jsonPrimitive.content
                        val plain = channel.open(
                            hostKey("ingest device-to-host"),
                            Sealed(
                                envelope.getValue("nonce").jsonPrimitive.content,
                                envelope.getValue("ct").jsonPrimitive.content,
                            ),
                            channel.ingestAad(envelope.getValue("kid").jsonPrimitive.content, deviceId!!, batch),
                        )
                        opened += Json.parseToJsonElement(plain.decodeToString()).jsonObject
                        json("""{"ok":true}""")
                    }

                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        val stores = stores
        val url = DashboardUrl("http", server.hostName, server.port, "")
        kotlinx.coroutines.runBlocking {
            stores.repository.saveAndActivate(Dashboard(id, url, "me", addedAt = 1), "basic")
            stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
        }
        val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
        val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
        uploader = PhoneDataUploader(requests, stores.secrets, FakeCipher(), "Test phone")
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun batch(n: Int) = buildJsonObject {
        put("batch_id", "b$n")
        put("sent_at", "2026-10-23T20:00:00+01:00")
    }

    @Test
    fun `a batch goes sealed with a key set up once`() = runTest {
        assertIs<NetResult.Success<JsonObject>>(uploader.ingest(id, "default", PhoneDataFeature.Phone, "b1", batch(1)))
        assertIs<NetResult.Success<JsonObject>>(uploader.ingest(id, "default", PhoneDataFeature.Phone, "b2", batch(2)))
        assertEquals(listOf("b1", "b2"), opened.map { it.getValue("batch_id").jsonPrimitive.content })
        assertEquals(1, keyRequests)
    }

    @Test
    fun `a host that lost the key gets a new one, and the batch again`() = runTest {
        uploader.ingest(id, "default", PhoneDataFeature.Phone, "b1", batch(1))
        forgetKeyOnce = true
        assertIs<NetResult.Success<JsonObject>>(uploader.ingest(id, "default", PhoneDataFeature.Phone, "b2", batch(2)))
        assertEquals(2, keyRequests)
        assertEquals(listOf("b1", "b2"), opened.map { it.getValue("batch_id").jsonPrimitive.content })
    }
}
