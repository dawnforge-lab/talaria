package lab.dawnforge.hermes.core.data.signin

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

class ConnectionTestTest {
    private val server = MockWebServer()
    private val stores = TestStores()
    private val service = SignInService(
        auth = DashboardAuthClient(DashboardClientFactory(DashboardClientFactory.defaultBaseClient())),
        repository = stores.repository,
        cookies = stores.cookies,
    )
    private val test = ConnectionTest(service)
    private lateinit var address: String

    @BeforeTest
    fun setUp() {
        server.start()
        address = "http://${server.hostName}:${server.port}"
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun json(body: String, code: Int = 200, cookie: String? = null) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }
        .body(body)
        .build()

    private fun passwordProviders(browserToo: Boolean = false) = json(
        """{"providers":[{"name":"basic","supports_password":true}""" +
            (if (browserToo) """,{"name":"github","supports_password":false}""" else "") +
            """],"auth_flows":["cookie"""" + (if (browserToo) ""","native_pkce"""" else "") + "]}",
    )

    @Test
    fun `a working password passes every stage and saves nothing`() = runTest {
        server.enqueue(passwordProviders())
        server.enqueue(json("""{"ok":true}""", cookie = "hermes_session_at=s1; Path=/; HttpOnly"))
        server.enqueue(json("""{"ticket":"t"}"""))

        val states = test.run(address, null, " me ", "pw").toList()
        val last = states.last()
        assertIs<TestOutcome.Ready>(last.outcome)
        TestStage.entries.forEach { assertEquals(StageState.Passed, last.stage(it), it.name) }
        assertEquals(StageState.Running, states.first().stage(TestStage.Server))
        assertEquals(emptyList(), stores.repository.dashboards.first())
        assertEquals("", stores.secretsFileText())
    }

    @Test
    fun `no password typed stops before signing in`() = runTest {
        server.enqueue(passwordProviders())
        val last = test.run(address, null, "", "").last()
        assertIs<TestOutcome.CredentialsRequired>(last.outcome)
        assertEquals(StageState.Partial, last.stage(TestStage.Authentication))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a dashboard with a browser sign-in too offers the choice`() = runTest {
        server.enqueue(passwordProviders(browserToo = true))
        assertIs<TestOutcome.SignInChoice>(test.run(address, null, "", "").last().outcome)
    }

    @Test
    fun `a rejected password fails the sign-in stage`() = runTest {
        server.enqueue(passwordProviders())
        server.enqueue(json("""{"detail":"bad"}""", code = 401))
        val last = test.run(address, null, "me", "wrong").last()
        assertEquals(
            TestOutcome.Failed(TestStage.Authentication, ConnectionFailure.AuthenticationRejected),
            last.outcome,
        )
        assertEquals(StageState.Passed, last.stage(TestStage.Dashboard))
    }

    @Test
    fun `an answer that isn't Hermes fails the dashboard stage`() = runTest {
        server.enqueue(json("""{"hello":"world"}"""))
        val last = test.run(address, null, "me", "pw").last()
        assertEquals(StageState.Passed, last.stage(TestStage.Server))
        assertEquals(TestStage.Dashboard, assertIs<TestOutcome.Failed>(last.outcome).stage)
    }

    @Test
    fun `nobody listening fails the server stage`() = runTest {
        server.close()
        val last = test.run(address, null, "me", "pw").last()
        assertEquals(TestStage.Server, assertIs<TestOutcome.Failed>(last.outcome).stage)
        assertEquals(StageState.Pending, last.stage(TestStage.Dashboard))
    }

    @Test
    fun `a proxy's sign-in page means browser sign-in`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://login.example.com/").build())
        val last = test.run(address, null, "me", "pw").last()
        assertIs<TestOutcome.BrowserSignIn>(last.outcome)
        assertEquals(StageState.Passed, last.stage(TestStage.Dashboard))
    }
}
