package lab.dawnforge.hermes.core.data.cron

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.BlueprintFieldType
import lab.dawnforge.hermes.core.model.CronBlueprint
import lab.dawnforge.hermes.core.model.CronDraft
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronRun
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.DeliveryTarget
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class CronRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val cron = CronRepository(requests)
    private val id = DashboardId("d1")

    /** A scripted Hermes: [routes] answers by "METHOD path". */
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    @BeforeTest
    fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded += request
                if (request.url.encodedPath == "/auth/password-login") {
                    return json("""{"ok":true}""", cookie = "hermes_session_at=s; Path=/")
                }
                val route = routes["${request.method} ${request.url.encodedPath}"] ?: return MockResponse(code = 404)
                return route(request)
            }
        }
        server.start()
        val url = DashboardUrl("http", server.hostName, server.port, "")
        stores.repository.saveAndActivate(Dashboard(id, url, "me", 1), "basic")
        stores.repository.saveCredentials(id, DashboardCredentials("me", "pw"))
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun last(method: String, path: String) = recorded.last { it.method == method && it.url.encodedPath == path }

    private val jobJson = """
        {"id":"a1b2c3d4e5f6","name":"Morning brief","prompt":"Summarise my inbox",
         "schedule":{"kind":"cron","expr":"0 9 * * *","display":"0 9 * * *"},"schedule_display":"0 9 * * *",
         "enabled":true,"state":"scheduled","next_run_at":"2026-10-23T09:00:00+01:00",
         "last_run_at":"2026-10-22T09:00:04.512+01:00","last_status":"ok","last_error":null,
         "deliver":"local","model":null,"provider":null,"no_agent":false,"script":null}
    """.trimIndent()

    @Test
    fun `jobs are listed for the profile and read like the desktop shows them`() = runTest {
        routes["GET /api/cron/jobs"] = { json("[$jobJson]") }

        val jobs = assertIs<NetResult.Success<List<CronJob>>>(cron.jobs(id, "work")).value
        assertEquals("work", last("GET", "/api/cron/jobs").url.queryParameter("profile"))
        val job = jobs.single()
        assertEquals("a1b2c3d4e5f6", job.id)
        assertEquals("Morning brief", job.name)
        assertEquals("0 9 * * *", job.schedule)
        assertEquals(CronState.Scheduled, job.state)
        assertEquals(1_792_742_400_000L, job.nextRunAtMs)
        assertEquals(1_792_656_004_512L, job.lastRunAtMs)
        assertEquals("ok", job.lastStatus)
        assertNull(job.lastError)
        assertNull(job.model)
        assertFalse(job.isScript)
    }

    @Test
    fun `a job's state falls back to enabled, and its name to its id`() {
        val paused = CronJson.job(Json.parseToJsonElement("""{"id":"x","enabled":false}""").jsonObject)
        assertEquals(CronState.Paused, paused?.state)
        assertEquals("x", paused?.name)
        assertEquals("", paused?.schedule)
        val failed = CronJson.job(
            Json.parseToJsonElement("""{"id":"y","state":"error","schedule":{"expr":"every 120m"}}""").jsonObject,
        )
        assertEquals(CronState.Error, failed?.state)
        assertEquals("every 120m", failed?.schedule)
        assertNull(CronJson.job(Json.parseToJsonElement("""{"name":"no id"}""").jsonObject))
    }

    @Test
    fun `a schedule is edited in the form Hermes reads back unchanged`() {
        fun input(schedule: String) =
            CronJson.job(Json.parseToJsonElement("""{"id":"x","schedule":$schedule}""").jsonObject)?.scheduleInput
        assertEquals("every 120m", input("""{"kind":"interval","minutes":120,"display":"every 120m"}"""))
        assertEquals(
            "2026-10-22T15:30:00+01:00",
            input("""{"kind":"once","run_at":"2026-10-22T15:30:00+01:00","display":"once in 30m"}"""),
        )
        assertEquals("30 9 * * 1-5", input("""{"kind":"cron","expr":"30 9 * * 1-5","display":"30 9 * * 1-5"}"""))
    }

    @Test
    fun `a new job delivers to Hermes itself`() = runTest {
        routes["POST /api/cron/jobs"] = { json(jobJson) }

        val job = cron.create(id, "work", CronDraft("Morning brief", "Summarise my inbox", "0 9 * * *"))
        assertEquals("a1b2c3d4e5f6", assertIs<NetResult.Success<CronJob?>>(job).value?.id)
        val request = last("POST", "/api/cron/jobs")
        assertEquals("work", request.url.queryParameter("profile"))
        val body = request.bodyJson()
        assertEquals("Morning brief", body["name"]?.jsonPrimitive?.content)
        assertEquals("Summarise my inbox", body["prompt"]?.jsonPrimitive?.content)
        assertEquals("0 9 * * *", body["schedule"]?.jsonPrimitive?.content)
        assertEquals("local", body["deliver"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a schedule Hermes can't read comes back with its reason`() = runTest {
        routes["POST /api/cron/jobs"] = { json("""{"detail":"Invalid schedule 'sometimes'"}""", code = 400) }

        val result = cron.create(id, "work", CronDraft("n", "p", "sometimes"))
        val failure = assertIs<NetResult.Failure>(result).failure
        assertEquals(ConnectionFailure.Invalid("Invalid schedule 'sometimes'"), failure)
    }

    @Test
    fun `an edit sends only the fields it changes`() = runTest {
        routes["PUT /api/cron/jobs/a1b2c3d4e5f6"] = { json(jobJson) }

        cron.update(id, "work", "a1b2c3d4e5f6", CronDraft(name = "Brief", schedule = "every 2h"))
        val request = last("PUT", "/api/cron/jobs/a1b2c3d4e5f6")
        assertEquals("work", request.url.queryParameter("profile"))
        val updates = request.bodyJson()["updates"]!!.jsonObject
        assertEquals(setOf("name", "schedule"), updates.keys)
        assertEquals("every 2h", updates["schedule"]?.jsonPrimitive?.content)
    }

    @Test
    fun `pause, resume and delete call the job's own routes`() = runTest {
        routes["POST /api/cron/jobs/j1/pause"] = { json("""{"id":"j1","state":"paused","enabled":false}""") }
        routes["POST /api/cron/jobs/j1/resume"] = { json("""{"id":"j1","state":"scheduled","enabled":true}""") }
        routes["DELETE /api/cron/jobs/j1"] = { json("""{"ok":true}""") }

        assertEquals(
            CronState.Paused,
            assertIs<NetResult.Success<CronJob?>>(cron.pause(id, "work", "j1")).value?.state,
        )
        assertEquals(
            CronState.Scheduled,
            assertIs<NetResult.Success<CronJob?>>(cron.resume(id, "work", "j1")).value?.state,
        )
        assertIs<NetResult.Success<Unit>>(cron.delete(id, "work", "j1"))
        assertEquals("work", last("DELETE", "/api/cron/jobs/j1").url.queryParameter("profile"))
    }

    @Test
    fun `run now reads a finished run, and one already going`() = runTest {
        routes["POST /api/cron/jobs/j1/trigger"] = { json("""{"ok":true}""") }
        assertEquals(CronTrigger.Finished, assertIs<NetResult.Success<CronTrigger>>(cron.trigger(id, "w", "j1")).value)

        routes["POST /api/cron/jobs/j1/trigger"] = { json("""{"detail":"Job is already running"}""", code = 409) }
        assertEquals(
            CronTrigger.AlreadyRunning,
            assertIs<NetResult.Success<CronTrigger>>(cron.trigger(id, "w", "j1")).value,
        )

        routes["POST /api/cron/jobs/j1/trigger"] = { json("""{"detail":"Job not found"}""", code = 404) }
        assertIs<NetResult.Failure>(cron.trigger(id, "w", "j1"))
    }

    @Test
    fun `runs open as chats unless they are a script's output`() = runTest {
        routes["GET /api/cron/jobs/j1/runs"] = {
            json(
                """
                {"runs":[
                  {"id":"cron_j1_20261022_090000","title":"Morning brief","source":"cron","profile":"work",
                   "started_at":1761120000.25,"ended_at":1761120042.0},
                  {"id":"cron_output:j1:2026-10-21_09-00-00","preview":"42 files","source":"cron_output",
                   "started_at":1761033600}
                ]}
                """,
            )
        }

        val runs = assertIs<NetResult.Success<List<CronRun>>>(cron.runs(id, "work", "j1")).value
        assertEquals("20", last("GET", "/api/cron/jobs/j1/runs").url.queryParameter("limit"))
        assertEquals(2, runs.size)
        assertTrue(runs[0].opensAsChat)
        assertEquals(1_761_120_000_250L, runs[0].startedAtMs)
        assertEquals(1_761_120_042_000L, runs[0].endedAtMs)
        assertEquals("work", runs[0].profile)
        assertFalse(runs[1].opensAsChat)
        assertEquals("42 files", runs[1].title)
        assertNull(runs[1].endedAtMs)
    }

    @Test
    fun `a job delivers where it's told, and an edit can move it`() = runTest {
        routes["POST /api/cron/jobs"] = { json(jobJson) }
        routes["PUT /api/cron/jobs/a1b2c3d4e5f6"] = { json(jobJson) }

        cron.create(id, "work", CronDraft(null, "Ping", "every 2h", deliver = "telegram"))
        assertEquals("telegram", last("POST", "/api/cron/jobs").bodyJson()["deliver"]?.jsonPrimitive?.content)

        cron.update(id, "work", "a1b2c3d4e5f6", CronDraft(deliver = "origin"))
        val updates = last("PUT", "/api/cron/jobs/a1b2c3d4e5f6").bodyJson()["updates"]!!.jsonObject
        assertEquals(setOf("deliver"), updates.keys)
    }

    @Test
    fun `delivery targets start with saving only`() = runTest {
        routes["GET /api/cron/delivery-targets"] = {
            json(
                """{"targets":[{"id":"telegram","name":"Telegram","home_target_set":false},
                   {"id":"local","name":"Local (save only)","home_target_set":true},{"name":"no id"}]}""",
            )
        }

        val targets = assertIs<NetResult.Success<List<DeliveryTarget>>>(cron.deliveryTargets(id, "work")).value

        assertEquals(listOf("local", "telegram"), targets.map { it.id })
        assertFalse(targets[1].hasHome)
        assertEquals("work", last("GET", "/api/cron/delivery-targets").url.queryParameter("profile"))
    }

    @Test
    fun `blueprints are read with their blanks, and a job is made from one`() = runTest {
        routes["GET /api/cron/blueprints"] = {
            json(
                """{"blueprints":[{"key":"morning-brief","title":"Morning briefing","description":"A short briefing.",
                   "category":"daily","scheduleHuman":"daily at 08:00","fields":[
                   {"name":"time","type":"time","label":"What time?","default":"08:00","options":[],"optional":false},
                   {"name":"deliver","type":"enum","label":"Where?","default":"origin","options":["origin","local"]},
                   {"name":"time","type":"time","label":"Again"},
                   {"name":"odd","type":"colour","label":"Skipped"}]},{"title":"no key"}]}""",
            )
        }
        routes["POST /api/cron/blueprints/instantiate"] = { json(jobJson) }

        val blueprints = assertIs<NetResult.Success<List<CronBlueprint>>>(cron.blueprints(id, "work")).value
        val brief = blueprints.single()
        assertEquals("Morning briefing", brief.title)
        assertEquals("daily at 08:00", brief.scheduleHuman)
        assertEquals(listOf("time", "deliver"), brief.fields.map { it.name })
        assertEquals(BlueprintFieldType.Time, brief.fields[0].type)
        assertEquals("08:00", brief.fields[0].default)
        assertTrue(brief.fields[1].isDelivery)
        assertEquals(listOf("origin", "local"), brief.fields[1].options)

        val made = cron.instantiate(id, "work", "morning-brief", mapOf("time" to "07:30"))
        assertEquals("a1b2c3d4e5f6", assertIs<NetResult.Success<CronJob?>>(made).value?.id)
        val request = last("POST", "/api/cron/blueprints/instantiate")
        assertEquals("work", request.url.queryParameter("profile"))
        val body = request.bodyJson()
        assertEquals("morning-brief", body["blueprint"]?.jsonPrimitive?.content)
        assertEquals("07:30", body["values"]!!.jsonObject["time"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a value a blueprint won't take comes back with Hermes' reason`() = runTest {
        routes["POST /api/cron/blueprints/instantiate"] = {
            json("""{"detail":"time must be HH:MM"}""", code = 422)
        }

        val made = cron.instantiate(id, "work", "morning-brief", mapOf("time" to "soon"))

        val failure = assertIs<NetResult.Failure>(made).failure
        assertEquals(ConnectionFailure.Invalid("time must be HH:MM"), failure)
    }
}
