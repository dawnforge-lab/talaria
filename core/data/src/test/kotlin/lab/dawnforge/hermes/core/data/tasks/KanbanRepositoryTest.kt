package lab.dawnforge.hermes.core.data.tasks

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class KanbanRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val kanban = KanbanRepository(requests)
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

    private val api = "/api/plugins/kanban"

    @Test
    fun `a new task carries its workspace, skills, goal and model`() = runTest {
        routes["POST $api/tasks"] = { json("""{"task":{"id":"t_1","title":"Ship it","status":"ready"}}""") }

        val task = NewTask(
            title = " Ship it ",
            body = "",
            assignee = "coder",
            priority = 2,
            lane = TaskStatus.Ready,
            workspaceKind = "dir",
            workspacePath = " D:/work ",
            skills = listOf("github", " ", "github", "review"),
            goalMode = true,
            goalMaxTurns = 20,
            model = ModelOverride("some-model", "openrouter", "high"),
        )
        assertIs<NetResult.Success<KanbanTask>>(kanban.create(id, "main", task))

        val request = last("POST", "$api/tasks")
        assertEquals("main", request.url.queryParameter("board"))
        val body = request.bodyJson()
        assertEquals("Ship it", body["title"]!!.jsonPrimitive.content)
        assertNull(body["body"])
        assertEquals("dir", body["workspace_kind"]!!.jsonPrimitive.content)
        assertEquals("D:/work", body["workspace_path"]!!.jsonPrimitive.content)
        assertEquals(listOf("github", "review"), body["skills"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(body["goal_mode"]!!.jsonPrimitive.boolean)
        assertEquals(20, body["goal_max_turns"]!!.jsonPrimitive.int)
        assertEquals("some-model", body["model_override"]!!.jsonPrimitive.content)
        assertEquals("openrouter", body["provider_override"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a plain new task leaves the workspace and model to Hermes`() = runTest {
        routes["POST $api/tasks"] = { json("""{"task":{"id":"t_1","title":"x","status":"todo"}}""") }

        val task = NewTask("x", "", null, 0, TaskStatus.Todo, workspaceKind = "scratch", workspacePath = "D:/ignored")
        kanban.create(id, "main", task)

        val body = last("POST", "$api/tasks").bodyJson()
        assertEquals("scratch", body["workspace_kind"]!!.jsonPrimitive.content)
        assertNull(body["workspace_path"])
        assertNull(body["skills"])
        assertFalse(body["goal_mode"]!!.jsonPrimitive.boolean)
        assertNull(body["goal_max_turns"])
        assertNull(body["model_override"])
        assertNull(body["assignee"])
    }

    @Test
    fun `a task's model is set, or cleared back to its profile's`() = runTest {
        routes["PATCH $api/tasks/t_1"] = { json("""{"task":{"id":"t_1"}}""") }

        kanban.setModel(id, "main", "t_1", ModelOverride("m", null, null))
        val set = last("PATCH", "$api/tasks/t_1").bodyJson()
        assertEquals("m", set["model_override"]!!.jsonPrimitive.content)
        assertNull(set["provider_override"])
        assertTrue(set["clear_reasoning_effort"]!!.jsonPrimitive.boolean)

        kanban.setModel(id, "main", "t_1", null)
        val cleared = last("PATCH", "$api/tasks/t_1").bodyJson()
        assertTrue(cleared["clear_model_override"]!!.jsonPrimitive.boolean)
        assertTrue(cleared["clear_reasoning_effort"]!!.jsonPrimitive.boolean)
        assertNull(cleared["model_override"])
    }

    @Test
    fun `a bulk change reports each task, and one Hermes skipped as not changed`() = runTest {
        routes["POST $api/tasks/bulk"] = {
            json("""{"results":[{"id":"t_1","ok":true},{"id":"t_2","ok":false,"error":"running"}]}""")
        }

        val change = BulkChange(status = TaskStatus.Blocked, assignee = "", priority = 3, archive = true)
        val results = assertIs<NetResult.Success<List<BulkResult>>>(
            kanban.bulk(id, "main", listOf("t_1", "t_2", "t_3"), change),
        ).value

        val body = last("POST", "$api/tasks/bulk").bodyJson()
        assertEquals(listOf("t_1", "t_2", "t_3"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("blocked", body["status"]!!.jsonPrimitive.content)
        assertEquals("", body["assignee"]!!.jsonPrimitive.content)
        assertTrue(body["reclaim_first"]!!.jsonPrimitive.boolean)
        assertEquals(3, body["priority"]!!.jsonPrimitive.int)
        assertTrue(body["archive"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(true, false, false), results.map { it.ok })
        assertEquals("running", results[1].error)
        assertNull(results[2].error)
    }

    @Test
    fun `deleting several tasks reports the ones that failed with why`() = runTest {
        routes["DELETE $api/tasks/t_1"] = { json("""{"ok":true}""") }
        routes["DELETE $api/tasks/t_2"] = { json("""{"detail":"nope"}""", code = 500) }

        val results = kanban.deleteAll(id, "main", listOf("t_1", "t_2"))

        assertTrue(results.single { it.id == "t_1" }.ok)
        val failed = results.single { it.id == "t_2" }
        assertFalse(failed.ok)
        assertNotNull(failed.failure)
    }

    @Test
    fun `a new board is made without switching Hermes' current one`() = runTest {
        routes["POST $api/boards"] = { json("""{"board":{"slug":"side-work"}}""") }

        assertIs<NetResult.Success<Unit>>(kanban.createBoard(id, "side-work", "Side work", " "))

        val body = last("POST", "$api/boards").bodyJson()
        assertEquals("side-work", body["slug"]!!.jsonPrimitive.content)
        assertEquals("Side work", body["name"]!!.jsonPrimitive.content)
        assertNull(body["description"])
        assertFalse(body["switch"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `routing changes send only what changed`() = runTest {
        routes["PUT $api/orchestration"] = {
            json(
                """{"orchestrator_profile":"","default_assignee":"coder","auto_decompose":true,
                   "auto_promote_children":false,"resolved_orchestrator_profile":"default",
                   "resolved_default_assignee":"coder"}""",
            )
        }

        val updated = assertIs<NetResult.Success<Orchestration>>(
            kanban.setOrchestration(id, defaultAssignee = "coder"),
        ).value

        assertEquals(setOf("default_assignee"), last("PUT", "$api/orchestration").bodyJson().keys)
        assertEquals("coder", updated.resolvedDefaultAssignee)
    }

    @Test
    fun `Hermes writes a profile's description, or says why it couldn't`() = runTest {
        routes["POST $api/profiles/coder/describe-auto"] = { json("""{"ok":true,"description":"Writes code."}""") }
        routes["POST $api/profiles/empty/describe-auto"] = { json("""{"ok":false,"reason":"no model"}""") }

        val written = assertIs<NetResult.Success<TriageResult>>(kanban.describeProfileAuto(id, "coder")).value
        assertTrue(written.ok)
        assertEquals("Writes code.", written.newTitle)
        assertTrue(last("POST", "$api/profiles/coder/describe-auto").bodyJson()["overwrite"]!!.jsonPrimitive.boolean)

        val refused = assertIs<NetResult.Success<TriageResult>>(kanban.describeProfileAuto(id, "empty")).value
        assertFalse(refused.ok)
        assertEquals("no model", refused.reason)
    }

    @Test
    fun `the models offered for tasks skip providers without any`() = runTest {
        routes["GET $api/model-options"] = {
            json(
                """{"providers":[{"slug":"openrouter","label":"OpenRouter","models":["a","b"]},
                   {"slug":"empty","models":[]},{"slug":"bare","models":["c"]}]}""",
            )
        }

        val options = assertIs<NetResult.Success<List<ModelOption>>>(kanban.modelOptions(id)).value

        assertEquals(
            listOf(ModelOption("openrouter", "OpenRouter", listOf("a", "b")), ModelOption("bare", "bare", listOf("c"))),
            options,
        )
    }
}
