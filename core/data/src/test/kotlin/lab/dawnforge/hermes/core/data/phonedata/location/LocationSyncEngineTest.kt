package lab.dawnforge.hermes.core.data.phonedata.location

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.FakeCipher
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataFeature
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.data.phonedata.Sealed
import lab.dawnforge.hermes.core.data.phonedata.SealedChannel
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneDataDestination
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** Sending location to a stand-in plugin that opens what the phone seals. */
class LocationSyncEngineTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val channel = SealedChannel(PhoneDataFeature.Location)
    private val host = SealedChannel.newKeyPair()
    private var devicePublic: ByteArray? = null
    private var deviceId: String? = null

    /** How the stand-in answers the next ingests, first to last; empty is "accepted". */
    private val refusals = ArrayDeque<Pair<Int, String>>()
    private val opened = mutableListOf<JsonObject>()
    private var keysMade = 0

    private lateinit var engine: LocationSyncEngine
    private val destination = PhoneDataDestination("dash", "default")
    private val home = SavedPlace("p_home", "Home", PlaceKind.Home, latitude = 51.5321, longitude = -0.0755)
    private val config = LocationConfig(destination, LocationSettings(talking = true), listOf(home))

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @BeforeTest
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath.removePrefix("/api/plugins/talaria_push/")
                val body = request.body?.utf8()?.takeIf {
                    it.isNotEmpty()
                }?.let { Json.parseToJsonElement(it).jsonObject }
                return when (path) {
                    "location/key" -> {
                        keysMade++
                        devicePublic = SealedChannel.unb64u(body!!.getValue("public_key").jsonPrimitive.content)
                        deviceId = body.getValue("device_id").jsonPrimitive.content
                        json(
                            """{"kid":"${channel.keyId(devicePublic!!, host.second)}",""" +
                                """"host_public_key":"${SealedChannel.b64u(host.second)}","generation":"g1"}""",
                        )
                    }

                    "location/status" -> json("""{"stored":true,"generation":"g1","device":null}""")

                    "location/ingest" -> {
                        refusals.removeFirstOrNull()?.let { (code, detail) ->
                            return json("""{"detail":"$detail","code":"x"}""", code)
                        }
                        val envelope = body!!
                        val kid = channel.keyId(devicePublic!!, host.second)
                        val key = channel.key(
                            channel.agree(host.first, devicePublic!!),
                            deviceId!!,
                            devicePublic!!,
                            host.second,
                            "ingest device-to-host",
                        )
                        val batchId = envelope.getValue("batch_id").jsonPrimitive.content
                        val plain = channel.open(
                            key,
                            Sealed(
                                envelope.getValue("nonce").jsonPrimitive.content,
                                envelope.getValue("ct").jsonPrimitive.content,
                            ),
                            channel.ingestAad(kid, deviceId!!, batchId),
                        )
                        opened += Json.parseToJsonElement(plain.decodeToString()).jsonObject
                        json("""{"accepted":true,"generation":"g1"}""")
                    }

                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        val url = DashboardUrl("http", server.hostName, server.port, "")
        val id = DashboardId("dash")
        runBlocking {
            stores.repository.saveAndActivate(Dashboard(id, url, "me", addedAt = 1), "basic")
            stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
        }
        val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
        val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
        val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
        engine =
            LocationSyncEngine(
                PhoneDataUploader(requests, stores.secrets, FakeCipher(), "Test phone"),
                stores.store("phone_data"),
            )
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun batch() =
        LocationBatch(sentAtMs = 1_760_430_000_000L, settings = config.settings, knownPlaces = config.places)

    @Test
    fun `the places go once, then again only when they change`() = runTest {
        assertEquals(LocationSyncEngine.SendOutcome.Sent, engine.send(batch(), config))
        assertNotNull(opened.last()["places"])
        assertEquals(LocationSyncEngine.SendOutcome.Sent, engine.send(batch(), config))
        assertNull(opened.last()["places"])

        engine.send(batch(), config.copy(places = listOf(home.copy(name = "House"))))
        assertEquals(
            "House",
            opened.last()["places"].toString().let {
                Regex("\"name\":\"(\\w+)\"").find(it)!!.groupValues[1]
            },
        )
        assertNotNull(engine.state.first().lastSent)
    }

    @Test
    fun `a host that lost the key gets a new one, and the places again`() = runTest {
        engine.send(batch(), config)
        refusals += 409 to "This device's location key isn't on this profile; set it up again"
        assertEquals(LocationSyncEngine.SendOutcome.Sent, engine.send(batch(), config))
        assertEquals(2, keysMade)
        assertNotNull(opened.last()["places"])
    }

    @Test
    fun `a layer that's off is refused without a new key`() = runTest {
        engine.send(batch(), config)
        refusals += 409 to "Background sharing is off"
        assertEquals(LocationSyncEngine.SendOutcome.Refused, engine.send(batch(), config))
        assertEquals(1, keysMade)
        assertNull(engine.state.first().pause)
    }

    @Test
    fun `a batch the host refuses pauses sending`() = runTest {
        refusals += 400 to "malformed"
        assertEquals(LocationSyncEngine.SendOutcome.Refused, engine.send(batch(), config))
        assertEquals(LocationPause.Refused, engine.state.first().pause)

        // The next one that goes through clears it.
        assertEquals(LocationSyncEngine.SendOutcome.Sent, engine.send(batch(), config))
        assertNull(engine.state.first().pause)
    }
}
