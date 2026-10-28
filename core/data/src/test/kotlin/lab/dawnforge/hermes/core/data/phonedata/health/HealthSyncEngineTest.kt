package lab.dawnforge.hermes.core.data.phonedata.health

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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

/** Health passes against a stand-in plugin that opens what the phone seals, as iOS's engine tests. */
class HealthSyncEngineTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val channel = SealedChannel(PhoneDataFeature.Health)
    private val host = SealedChannel.newKeyPair()
    private var devicePublic: ByteArray? = null
    private var deviceId: String? = null
    private var keysMade = 0
    private var wipes = 0
    private val opened = mutableListOf<JsonObject>()
    private val refusals = ArrayDeque<Pair<Int, String>>()

    private val utc: ZoneId = ZoneOffset.UTC
    private var now = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli()
    private val source = FakeSource()
    private lateinit var engine: HealthSyncEngine

    /** Steps per day, workouts as pages, and sleep stages per session. */
    private inner class FakeSource : HealthSource {
        var steps = 1_000.0
        val pages = ArrayDeque<HealthChanges>()

        override suspend fun buckets(
            metric: HealthMetric,
            grain: HealthGrain,
            from: Instant,
            to: Instant,
            zone: ZoneId,
        ) = if (metric == HealthMetric.Steps &&
            grain == HealthGrain.Day
        ) {
            listOf(HealthBucket(from, from.plusSeconds(DAY), sum = steps))
        } else {
            emptyList()
        }

        override suspend fun sleepSegments(from: Instant, to: Instant) = emptyList<HealthSleepSegment>()

        override suspend fun changes(
            stream: HealthStream,
            since: Instant,
            position: String?,
            limit: Int,
        ): HealthChanges =
            pages.removeFirstOrNull() ?: HealthChanges(emptyList(), emptyList(), position ?: "p0", more = false)
    }

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
                val kid = devicePublic?.let { channel.keyId(it, host.second) }
                return when (path) {
                    "health/status" -> json(
                        if (kid ==
                            null
                        ) {
                            """{"stored":false,"generation":"g1","device":null}"""
                        } else {
                            """{"stored":true,"generation":"g1","device":{"kid":"$kid"}}"""
                        },
                    )

                    "health/key" -> {
                        keysMade++
                        devicePublic = SealedChannel.unb64u(body!!.getValue("public_key").jsonPrimitive.content)
                        deviceId = body.getValue("device_id").jsonPrimitive.content
                        json(
                            """{"kid":"${channel.keyId(
                                devicePublic!!,
                                host.second,
                            )}","host_public_key":"${SealedChannel.b64u(host.second)}","generation":"g1"}""",
                        )
                    }

                    "health/ingest" -> {
                        refusals.removeFirstOrNull()?.let { (code, detail) ->
                            return json("""{"detail":"$detail","code":"x"}""", code)
                        }
                        val key = channel.key(
                            channel.agree(host.first, devicePublic!!),
                            deviceId!!,
                            devicePublic!!,
                            host.second,
                            "ingest device-to-host",
                        )
                        val batchId = body!!.getValue("batch_id").jsonPrimitive.content
                        val plain = channel.open(
                            key,
                            Sealed(
                                body.getValue("nonce").jsonPrimitive.content,
                                body.getValue("ct").jsonPrimitive.content,
                            ),
                            channel.ingestAad(kid!!, deviceId!!, batchId),
                        )
                        opened += Json.parseToJsonElement(plain.decodeToString()).jsonObject
                        json("""{"windows":1,"stale_windows":0,"generation":"g1"}""")
                    }

                    "health/wipe" -> {
                        wipes++
                        json("""{"deleted":true,"remaining_devices":0}""")
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
        engine = HealthSyncEngine(
            PhoneDataUploader(requests, stores.secrets, FakeCipher(), "Test phone"),
            source,
            stores.store("phone_data"),
            deviceName = "Test phone",
            clock = { now },
            zone = { utc },
        )
        runBlocking {
            engine.setPreferences {
                HealthPreferences(
                    PhoneDataDestination("dash", "default"),
                    categories = setOf(HealthCategory.Activity, HealthCategory.Workouts),
                )
            }
        }
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private suspend fun pass(options: HealthPassOptions = HealthPassOptions()) = engine.sync(now + 60_000, options)

    private fun JsonObject.windows() = this["windows"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `a pass sets up a key, sends sealed windows, and then only what changed`() = runTest {
        assertEquals(HealthOutcome.Synced, pass())
        assertEquals(1, keysMade)
        val first = opened.first()
        assertEquals("Test phone", first["device"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(
            first.windows().any {
                it["metric"]!!.jsonPrimitive.content == "steps" &&
                    it["grain"]!!.jsonPrimitive.content == "day"
            },
        )

        // Nothing changed a minute later: nothing to send, and no keepalive yet.
        opened.clear()
        now += 60_000
        assertEquals(HealthOutcome.Synced, pass())
        assertEquals(emptyList(), opened)

        // More steps: only the steps window that changed goes.
        source.steps = 2_000.0
        now += 60_000
        pass()
        val windows = opened.flatMap { it.windows() }
        assertEquals(
            listOf("steps|day"),
            windows.map {
                "${it["metric"]!!.jsonPrimitive.content}|${it["grain"]!!.jsonPrimitive.content}"
            },
        )

        // The user asks: every recent window goes again.
        opened.clear()
        now += 60_000
        pass(HealthPassOptions(resendRecent = true))
        assertTrue(opened.flatMap { it.windows() }.size > 1)
    }

    @Test
    fun `a quiet phone still posts every quarter of an hour`() = runTest {
        pass()
        opened.clear()
        now += HealthSyncEngine.KEEPALIVE_MS
        pass()
        assertEquals(1, opened.size)
        assertEquals(emptyList(), opened.single().windows().filter { it["buckets"]!!.jsonArray.isNotEmpty() })
    }

    @Test
    fun `events go with their stream's position, and a night's lost stages are taken back`() = runTest {
        val start = Instant.parse("2026-10-13T07:00:00Z")
        val run =
            HealthEvent(
                "w1",
                HealthEventKind.Workout,
                "workout",
                start,
                start.plusSeconds(1_800),
                data = mapOf("activity" to HealthValue.Text("running")),
            )
        source.pages += HealthChanges(listOf(run), emptyList(), "p1", more = false)
        pass()
        val events = opened.flatMap { it["events"]!!.jsonArray }.map { it.jsonObject }
        assertEquals(listOf("w1"), events.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("p1", engine.state.first().tokens["workouts"])

        // Sleep: a night that had three stages now has two, and another night was deleted.
        engine.setPreferences { it.copy(categories = it.categories + HealthCategory.Sleep) }
        opened.clear()
        now += 60_000
        source.pages += HealthChanges(emptyList(), emptyList(), "w2", more = false) // workouts
        source.pages +=
            HealthChanges(emptyList(), emptyList(), "s1", more = false, series = mapOf("n1" to 3, "n2" to 1))
        pass()
        opened.clear()
        now += 60_000
        source.pages += HealthChanges(emptyList(), emptyList(), "w3", more = false)
        source.pages +=
            HealthChanges(emptyList(), emptyList(), "s2", more = false, series = mapOf("n1" to 2, "n2" to null))
        pass()
        val deleted = opened.flatMap { it["deleted"]!!.jsonArray }.map { it.jsonPrimitive.content }
        assertEquals(setOf("n1:2", "n2:0"), deleted.toSet())
        assertEquals(mapOf("n1" to 2), engine.state.first().seriesCounts)
    }

    @Test
    fun `a refused batch pauses sharing, and a lost key is set up again with everything sent`() = runTest {
        refusals += 400 to "unknown_metric"
        assertEquals(HealthOutcome.Paused(HealthPause.Refused), pass())
        assertEquals("unknown_metric", engine.state.first().refusal)

        assertEquals(HealthOutcome.Synced, pass())
        assertNull(engine.state.first().pause)
        opened.clear()
        now += 60_000
        refusals += 409 to "This device's health key isn't on this profile; set it up again"
        source.steps = 3_000.0
        pass()
        assertEquals(2, keysMade)
        // The host has only that batch now: the next pass sends every window again.
        opened.clear()
        now += 60_000
        pass()
        assertTrue(opened.flatMap { it.windows() }.size > 1)
    }

    @Test
    fun `turning off deletes this phone's data on the host and starts over`() = runTest {
        pass()
        assertTrue(engine.turnOff(deleteFromHost = true))
        assertEquals(1, wipes)
        assertEquals(HealthSyncState(), engine.state.first())
        assertNull(engine.preferences.first().destination)
        assertIs<HealthOutcome.Off>(pass())
    }

    private companion object {
        const val DAY = 86_400L
    }
}
