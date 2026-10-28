package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.FakeCipher
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataFeature
import lab.dawnforge.hermes.core.data.phonedata.PhoneDataUploader
import lab.dawnforge.hermes.core.data.phonedata.Sealed
import lab.dawnforge.hermes.core.data.phonedata.SealedChannel
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

/** Hermes' changes through `/phone/exchange`, against a stand-in plugin that seals its answers. */
class PhoneExchangeTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val channel = SealedChannel(PhoneDataFeature.Phone)
    private val host = SealedChannel.newKeyPair()
    private var devicePublic: ByteArray? = null
    private var deviceId: String? = null

    /** What the host will hand out, and the results it heard. */
    private var waiting: List<JsonObject> = emptyList()
    private val reported = mutableListOf<JsonObject>()

    /** Applies changes by remembering them. */
    private class Recorder : PhoneChangeApplier {
        val applied = mutableListOf<String>()
        val undone = mutableListOf<String>()

        override suspend fun apply(change: PhoneChange): String {
            applied += change.id
            return "item-${change.id}"
        }

        override suspend fun undo(kind: PhoneChangeKind, itemId: String) {
            undone += itemId
        }
    }

    private val applier = Recorder()
    private lateinit var engine: PhoneSyncEngine
    private val id = DashboardId("dash")

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun key(purpose: String): ByteArray {
        val public = devicePublic!!
        return channel.key(channel.agree(host.first, public), deviceId!!, public, host.second, purpose)
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
                        if (kid == null) {
                            """{"generation":"g1","device":null}"""
                        } else {
                            """{"generation":"g1","device":{"kid":"$kid","pending":${waiting.size}}}"""
                        },
                    )

                    "phone/key" -> {
                        devicePublic = SealedChannel.unb64u(body!!.getValue("public_key").jsonPrimitive.content)
                        deviceId = body.getValue("device_id").jsonPrimitive.content
                        json(
                            """{"kid":"${channel.keyId(devicePublic!!, host.second)}",""" +
                                """"host_public_key":"${SealedChannel.b64u(host.second)}","generation":"g1"}""",
                        )
                    }

                    "phone/ingest" -> json("""{"ok":true,"pending":${waiting.size}}""")

                    "phone/exchange" -> {
                        val envelope = body!!
                        val callId = envelope.getValue("call_id").jsonPrimitive.content
                        val opened = channel.open(
                            key("exchange device-to-host"),
                            Sealed(
                                envelope.getValue("nonce").jsonPrimitive.content,
                                envelope.getValue("ct").jsonPrimitive.content,
                            ),
                            channel.exchangeAad("request", "changes", kid!!, deviceId!!, callId),
                        )
                        val sent = Json.parseToJsonElement(opened.decodeToString()).jsonObject
                        sent.getValue("results").jsonArray.forEach { result ->
                            val outcome = result.jsonObject
                            reported += outcome
                            waiting =
                                waiting.filterNot {
                                    it.getValue("id").jsonPrimitive.content ==
                                        outcome.getValue("id").jsonPrimitive.content
                                }
                        }
                        val answer = buildJsonObject {
                            put("call_id", callId)
                            put("changes", JsonArray(waiting))
                        }
                        val sealed = channel.seal(
                            key("exchange host-to-device"),
                            answer.toString().toByteArray(),
                            channel.exchangeAad("answer", "changes", kid, deviceId!!, callId),
                        )
                        json("""{"nonce":"${sealed.nonce}","ct":"${sealed.ciphertext}"}""")
                    }

                    else -> json("{}", 404)
                }
            }
        }
        server.start()
        val url = DashboardUrl("http", server.hostName, server.port, "")
        runBlocking {
            stores.repository.saveAndActivate(Dashboard(id, url, "me", addedAt = 1), "basic")
            stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
        }
        val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
        val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
        val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
        val uploader = PhoneDataUploader(requests, stores.secrets, FakeCipher(), "Test phone")
        engine = PhoneSyncEngine(uploader, NoCalendar, stores.store("phone_data"), applier = applier)
        runBlocking {
            engine.setSharing {
                PhoneSharing(destination = PhoneDataDestination(id.value, "default"), focus = false, alarms = true)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun change(id: String, kind: String, auto: Boolean) = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("auto", auto)
        put("target", buildJsonObject {})
        put(
            "fields",
            buildJsonObject {
                put("time", "07:00")
                put("repeats", buildJsonArray { })
            },
        )
    }

    @Test
    fun `a change that needs asking waits as a card, and the answer is reported`() = runTest {
        waiting = listOf(change("ch_ask00000001", "set_alarm", auto = false))
        engine.sync()
        val card = engine.prompts.value.single()
        assertEquals("ch_ask00000001", card.id)
        assertEquals(emptyList(), applier.applied)

        engine.answer(card, approve = true)
        assertEquals(listOf("ch_ask00000001"), applier.applied)
        assertEquals("applied", reported.single().getValue("outcome").jsonPrimitive.content)
        assertEquals("item-ch_ask00000001", reported.single().getValue("item_id").jsonPrimitive.content)
        assertEquals(emptyList(), engine.prompts.value)
    }

    @Test
    fun `a new item goes in without asking only when the user allows it`() = runTest {
        waiting = listOf(change("ch_auto0000001", "set_alarm", auto = true))
        engine.sync()
        // The user didn't choose "Add without asking": it waits.
        assertEquals(1, engine.prompts.value.size)

        engine.setSharing { it.copy(autoAdd = true) }
        engine.sync()
        assertEquals(listOf("ch_auto0000001"), applier.applied)
        assertEquals("applied", reported.single().getValue("outcome").jsonPrimitive.content)
        assertEquals("ch_auto0000001", engine.autoAdded.value?.change?.id)

        engine.undo(engine.autoAdded.value!!)
        assertEquals(listOf("item-ch_auto0000001"), applier.undone)
    }

    @Test
    fun `not now is reported as dismissed, and an answered change is never applied twice`() = runTest {
        waiting = listOf(change("ch_dismiss0001", "set_timer", auto = false))
        engine.sync()
        engine.answer(engine.prompts.value.single(), approve = false)
        assertEquals("dismissed", reported.single().getValue("outcome").jsonPrimitive.content)
        assertEquals(emptyList(), applier.applied)
        assertEquals(1, engine.state.first().answered.size)
    }
}

/** A phone without calendar access, whose status is never asked for (it isn't shared here). */
private object NoCalendar : PhoneSources {
    override val canRead = false
    override val canWrite = false

    override fun calendarsJson(hidden: Set<String>) = emptyList<JsonObject>()

    override fun events(fromMs: Long, toMs: Long, hidden: Set<String>, details: Boolean, emails: Boolean, limit: Int) =
        emptyList<JsonObject>()

    override fun status(nowMs: Long) = JsonObject(emptyMap())

    override fun focus(nowMs: Long) = JsonObject(emptyMap())
}
