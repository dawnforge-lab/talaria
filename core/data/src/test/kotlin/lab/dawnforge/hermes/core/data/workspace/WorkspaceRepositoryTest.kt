package lab.dawnforge.hermes.core.data.workspace

import java.io.File
import java.util.Base64
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
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

class WorkspaceRepositoryTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
    private val signIn = SignInService(DashboardAuthClient(factory), stores.repository, stores.cookies)
    private val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
    private val workspace = WorkspaceRepository(requests)
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
                val route = routes["${request.method} ${request.url.encodedPath}"]
                    ?: return json("""{"detail":"Not Found"}""", code = 404)
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

    private fun last(path: String) = recorded.last { it.url.encodedPath == path }

    @Test
    fun `a folder lists folders first, by name, for the profile`() = runTest {
        routes["GET /api/fs/list"] = {
            json(
                """{"entries":[{"name":"b.txt","path":"C:\\w\\b.txt","isDirectory":false},
                   {"name":"src","path":"C:\\w\\src","isDirectory":true},
                   {"name":"A.md","path":"C:\\w\\A.md"},{"name":"","path":"x"}]}""",
            )
        }

        val entries = assertIs<NetResult.Success<List<WorkspaceEntry>>>(workspace.list(id, "work", "C:\\w")).value

        assertEquals(listOf("src", "A.md", "b.txt"), entries.map { it.name })
        assertTrue(entries.first().isDirectory)
        assertEquals("C:\\w", last("/api/fs/list").url.queryParameter("path"))
        assertEquals("work", last("/api/fs/list").url.queryParameter("profile"))
    }

    @Test
    fun `a folder Hermes can't read fails with why, though Hermes answers 200`() = runTest {
        routes["GET /api/fs/list"] = { request ->
            when (request.url.queryParameter("path")) {
                "gone" -> json("""{"entries":[],"error":"ENOENT"}""")
                "locked" -> json("""{"entries":[],"error":"EACCES"}""")
                else -> json("""{"entries":[],"error":"read-error"}""")
            }
        }

        assertIs<ConnectionFailure.NotFound>(assertIs<NetResult.Failure>(workspace.list(id, "default", "gone")).failure)
        assertIs<ConnectionFailure.Refused>(
            assertIs<NetResult.Failure>(workspace.list(id, "default", "locked")).failure,
        )
        assertIs<ConnectionFailure.UnexpectedResponse>(
            assertIs<NetResult.Failure>(workspace.list(id, "default", "x")).failure,
        )
    }

    @Test
    fun `a preview says whether the file is text and how much of it came`() = runTest {
        routes["GET /api/fs/read-text"] = {
            json(
                """{"binary":false,"byteSize":600000,"language":"python","mimeType":"text/x-python",
                   "text":"print(1)","truncated":true}""",
            )
        }

        val preview = assertIs<NetResult.Success<FilePreview>>(workspace.preview(id, "default", "a.py")).value

        assertEquals(FilePreview(false, 600_000, "python", "text/x-python", "print(1)", true), preview)
    }

    @Test
    fun `a file is streamed to disk, and one too large is refused and left out`() = runTest {
        val body = "hello workspace"
        routes["GET /api/fs/download"] = { MockResponse.Builder().code(200).body(body).build() }
        val target = File.createTempFile("workspace", ".txt").apply { deleteOnExit() }

        val written = assertIs<NetResult.Success<Long>>(workspace.download(id, "default", "a.txt", target)).value
        assertEquals(body.length.toLong(), written)
        assertEquals(body, target.readText())

        target.delete()
        val refused = assertIs<NetResult.Failure>(workspace.download(id, "default", "a.txt", target, maxBytes = 5))
        assertIs<ConnectionFailure.TooLarge>(refused.failure)
        assertFalse(target.exists())
    }

    @Test
    fun `a Hermes without the download route sends the file as a data URL instead`() = runTest {
        val encoded = Base64.getEncoder().encodeToString("small".toByteArray())
        routes["GET /api/fs/read-data-url"] = { json("""{"dataUrl":"data:text/plain;base64,$encoded"}""") }
        val target = File.createTempFile("workspace", ".txt").apply { deleteOnExit() }

        assertIs<NetResult.Success<Long>>(workspace.download(id, "default", "a.txt", target))
        assertEquals("small", target.readText())
    }

    @Test
    fun `a file that is gone is not looked for again as a data URL`() = runTest {
        routes["GET /api/fs/download"] = { json("""{"detail":"File not found"}""", code = 404) }
        val target = File.createTempFile("workspace", ".txt").apply { deleteOnExit() }

        val failed = assertIs<NetResult.Failure>(workspace.download(id, "default", "a.txt", target))

        assertEquals(ConnectionFailure.NotFound("File not found"), failed.failure)
        assertTrue(recorded.none { it.url.encodedPath == "/api/fs/read-data-url" })
    }

    @Test
    fun `artifacts are asked for by every id of a chat, filtered, without missing files`() = runTest {
        routes["GET /api/plugins/talaria_push/artifacts"] = {
            json(
                """{"ok":true,"next_before":40,"items":[
                  {"kind":"image","source":"delivered","path":"C:\\out\\road.png","name":"","exists":true,"size":2048,
                   "session_id":"tip","session_title":"Trip","message_id":51,"timestamp":1792689000},
                  {"kind":"file","source":"written","path":"/tmp/gone.pdf","name":"gone.pdf","exists":false,
                   "session_id":"tip","message_id":50,"timestamp":1792688000},
                  {"kind":"link","source":"link","url":"https://example.com/a","host":"example.com","name":"",
                   "session_id":"root","message_id":49,"timestamp":1792687000},
                  {"kind":"link","url":"ftp://example.com","session_id":"root","message_id":48,"timestamp":1},
                  {"kind":"sticker","path":"x","session_id":"root","message_id":47,"timestamp":1}]}""",
            )
        }

        val page = assertIs<NetResult.Success<ArtifactPage>>(
            workspace.artifacts(
                id,
                "work",
                listOf("tip", "root", "tip"),
                ArtifactFilter.Images,
                " road trip ",
                before = 60,
            ),
        ).value

        val url = last("/api/plugins/talaria_push/artifacts").url
        assertEquals(listOf("root", "tip"), url.queryParameterValues("session_id"))
        assertEquals("images", url.queryParameter("kind"))
        assertEquals("road trip", url.queryParameter("q"))
        assertEquals("hide", url.queryParameter("missing"))
        assertEquals("60", url.queryParameter("before"))
        assertEquals("60", url.queryParameter("limit"))
        assertEquals("work", url.queryParameter("profile"))
        assertEquals(40L, page.nextBefore)
        assertEquals(listOf("road.png", "example.com"), page.items.map { it.name })
        val image = page.items.first()
        assertEquals(ArtifactKind.Image, image.kind)
        assertEquals("C:\\out\\road.png", image.id)
        assertEquals(2048L, image.size)
        assertEquals(51L, image.messageId)
        assertEquals("https://example.com/a", page.items[1].id)
        assertNull(page.items[1].path)
    }

    @Test
    fun `missing files are kept when asked for, and the whole profile asks for no chat`() = runTest {
        routes["GET /api/plugins/talaria_push/artifacts"] = {
            json(
                """{"ok":true,"items":[{"kind":"file","path":"/tmp/gone.pdf","exists":false,
                   "session_id":"s","message_id":1,"timestamp":1}]}""",
            )
        }

        val page = assertIs<NetResult.Success<ArtifactPage>>(
            workspace.artifacts(id, "default", showMissing = true),
        ).value

        val url = last("/api/plugins/talaria_push/artifacts").url
        assertNull(url.queryParameter("missing"))
        assertNull(url.queryParameter("kind"))
        assertTrue(url.queryParameterValues("session_id").isEmpty())
        assertEquals("gone.pdf", page.items.single().name)
        assertFalse(page.items.single().exists)
        assertNull(page.nextBefore)
    }

    @Test
    fun `artifacts need the plugin's capability`() = runTest {
        routes["GET /api/plugins/talaria_push/capabilities"] =
            { json("""{"ok":true,"capabilities":["memory","artifacts"]}""") }
        assertEquals(true, assertIs<NetResult.Success<Boolean>>(workspace.artifactsSupported(id)).value)

        routes["GET /api/plugins/talaria_push/capabilities"] = { json("""{"ok":true,"capabilities":["memory"]}""") }
        assertEquals(false, assertIs<NetResult.Success<Boolean>>(workspace.artifactsSupported(id)).value)
    }

    @Test
    fun `a reply that isn't a page of artifacts fails`() = runTest {
        routes["GET /api/plugins/talaria_push/artifacts"] = { json("""{"ok":false,"error":"busy"}""") }

        assertIs<NetResult.Failure>(workspace.artifacts(id, "default"))
    }
}
