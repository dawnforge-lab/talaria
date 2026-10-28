package lab.dawnforge.hermes.core.net

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import lab.dawnforge.hermes.core.model.DashboardUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.CookieJar
import okhttp3.Request

/**
 * A reply's body is read off the caller's thread. Called from a screen, that thread is Android's
 * main thread, where reading the network throws `NetworkOnMainThreadException` and the app stops:
 * a reply with no length, or one that streams slowly (a proxy, a weak link), did just that. Here a
 * single "main" thread runs the call while a ticker shares it; a body read on that thread would
 * stop the ticker for as long as the server holds the body back.
 */
class ReplyOffCallerThreadTest {
    private val server = MockWebServer().apply { start() }
    private val main = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }.asCoroutineDispatcher()

    @AfterTest
    fun tearDown() {
        server.close()
        main.close()
    }

    /** A reply whose headers come at once and whose body comes [BODY_DELAY_MS] later. */
    private fun slowBody(code: Int, body: String = "not found") =
        MockResponse.Builder().code(code).body(body).bodyDelay(BODY_DELAY_MS, TimeUnit.MILLISECONDS).build()

    /** Runs [call] on the pretend main thread; answers what it returned and how often the ticker ran meanwhile. */
    private fun <T> onMain(call: suspend () -> T): Pair<T, Int> = runBlocking(main) {
        var ticks = 0
        val ticker = launch {
            while (isActive) {
                ticks++
                delay(TICK_MS)
            }
        }
        val result = async { call() }.await()
        ticker.cancel()
        result to ticks
    }

    private fun assertMainStayedFree(ticks: Int) = assertTrue(
        ticks >= MIN_TICKS,
        "The caller's thread was blocked while the body came: the ticker ran $ticks times",
    )

    @Test
    fun `a dashboard reply's body is read off the caller's thread`() {
        server.enqueue(slowBody(404))
        val client = DashboardClientFactory.defaultBaseClient()
        val (reply, ticks) = onMain { client.exchange(Request.Builder().url(server.url("/api/status")).build(), 1024) }
        assertEquals(404, reply.code)
        assertEquals("not found", reply.body)
        assertMainStayedFree(ticks)
    }

    @Test
    fun `a failed download's error text is read off the caller's thread`() {
        server.enqueue(slowBody(500, """{"detail":"disk full"}"""))
        val api = DashboardApi(DashboardClientFactory(DashboardClientFactory.defaultBaseClient()))
        val url = DashboardUrl("http", server.hostName, server.port, "")
        val target = File.createTempFile("download", ".bin").apply { deleteOnExit() }
        val (result, ticks) = onMain {
            api.download(url, CookieJar.NO_COOKIES, null, "/api/files", emptyMap(), target, 1024)
        }
        assertIs<NetResult.Failure>(result)
        assertMainStayedFree(ticks)
    }

    private companion object {
        const val BODY_DELAY_MS = 600L
        const val TICK_MS = 20L

        /** Well under the 30 ticks of an unblocked thread, well over the one or two of a blocked one. */
        const val MIN_TICKS = 12
    }
}
