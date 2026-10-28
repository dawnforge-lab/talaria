package lab.dawnforge.hermes.core.data

import java.io.File
import java.util.Properties
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import lab.dawnforge.hermes.core.data.chat.ChatRepository
import lab.dawnforge.hermes.core.data.cron.CronRepository
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.ProfileRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.signin.SignInResult
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayPolicy
import lab.dawnforge.hermes.core.gateway.GatewayState
import lab.dawnforge.hermes.core.gateway.OkHttpGatewayConnector
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.NetResult

/**
 * A real Hermes dashboard for the live tests, signed in through the app's own repositories. Only
 * set up when `-Phermes.live.file=<path>` names a properties file with `url`, `username`, `password`.
 */
internal object LiveHermes {
    val config: Properties? = System.getProperty("hermes.live.file")
        ?.let(::File)
        ?.takeIf(File::exists)
        ?.let { file -> Properties().apply { file.reader(Charsets.UTF_8).use(::load) } }

    class Live(
        val id: DashboardId,
        val gateway: GatewayClient,
        val sessions: SessionRepository,
        val profiles: ProfileRepository,
        val chats: ChatRepository,
        val cron: CronRepository,
    )

    /** Signs in and connects the chat socket; the caller stops [Live.gateway] when done. */
    suspend fun connect(scope: CoroutineScope): Live {
        val config = checkNotNull(config)
        val url = (ConnectionUrlPolicy.check(config.getProperty("url")) as AddressCheck.Valid).candidates.first()
        val stores = TestStores()
        val factory = DashboardClientFactory(DashboardClientFactory.defaultBaseClient())
        val auth = DashboardAuthClient(factory)
        val signIn = SignInService(auth, stores.repository, stores.cookies)
        val method = assertIs<SignInMethod.Password>((auth.discoverSignInMethod(url, null) as NetResult.Success).value)
        val signedIn = signIn.signInWithPassword(
            url,
            method,
            config.getProperty("username"),
            config.getProperty("password"),
            savePassword = true,
            cloudflareToken = null,
        )
        val id = assertIs<SignInResult.SignedIn>(signedIn).dashboard.id

        val policy = GatewayPolicy()
        val gateway = GatewayClient(
            scope = scope,
            connector = OkHttpGatewayConnector(factory.createWebSocketClient(url) { null }, url, policy),
            tickets = { signIn.mintGatewayTicket(id) },
            readiness = { signIn.probe(id) },
            policy = policy,
        )
        gateway.start()
        withTimeout(30_000) { gateway.state.first { it is GatewayState.Connected } }
        val requests = DashboardRequests(DashboardApi(factory), stores.repository, stores.cookies, signIn)
        return Live(
            id = id,
            gateway = gateway,
            sessions = SessionRepository(requests) { gateway },
            profiles = ProfileRepository(requests, stores.repository),
            chats = ChatRepository(requests) { gateway },
            cron = CronRepository(requests),
        )
    }

    /** A chat stored at once, because it is created with messages: two short ones saying what it is. */
    suspend fun seed(gateway: GatewayClient, title: String, marker: String): JsonObject = seed(
        gateway,
        title,
        listOf(
            "user" to "Test message from the Android client ($marker). Safe to delete.",
            "assistant" to "Noted.",
        ),
    )

    /**
     * A chat stored at once with [messages] (role to text). Hermes accepts only text rows from a
     * client, so no model is called; a role of `hidden` stores a hidden user row.
     */
    suspend fun seed(gateway: GatewayClient, title: String, messages: List<Pair<String, String>>): JsonObject =
        gateway.call(
            "session.create",
            buildJsonObject {
                put("profile", Profile.DEFAULT)
                put("title", title)
                putJsonArray("messages") {
                    messages.forEach { (role, text) ->
                        addJsonObject {
                            put("role", if (role == "hidden") "user" else role)
                            put("content", text)
                            if (role == "hidden") put("display_kind", "hidden")
                        }
                    }
                }
            },
            timeoutMs = 60_000,
        ) as JsonObject

    /** The rows' JSON, for a test to print when it fails. */
    fun describe(rows: List<JsonObject>): String = JsonArray(rows).toString()
}
