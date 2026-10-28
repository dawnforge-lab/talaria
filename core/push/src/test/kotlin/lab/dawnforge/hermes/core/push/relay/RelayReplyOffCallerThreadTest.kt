package lab.dawnforge.hermes.core.push.relay

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * The relay's reply body is read off the caller's thread, which from a screen is Android's main
 * thread: reading the network there stops the app. A ticker shares a single "main" thread with
 * the call; a body read on it would stop the ticker while the relay holds the body back.
 */
class RelayReplyOffCallerThreadTest {
    private val server = MockWebServer().apply { start() }
    private val main = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }.asCoroutineDispatcher()
    private val client = RelayClient(OkHttpClient())

    @AfterTest
    fun tearDown() {
        server.close()
        main.close()
    }

    @Test
    fun `a relay reply's body is read off the caller's thread`() {
        server.enqueue(MockResponse.Builder().code(202).body("{}").bodyDelay(600, TimeUnit.MILLISECONDS).build())
        val ticks = runBlocking(main) {
            var ticks = 0
            val ticker = launch {
                while (isActive) {
                    ticks++
                    delay(20)
                }
            }
            async { client.requestChallenge(server.url("/"), "t".repeat(40), "lab.dawnforge.hermes") }.await()
            ticker.cancel()
            ticks
        }
        assertTrue(ticks >= 12, "The caller's thread was blocked while the body came: the ticker ran $ticks times")
    }
}
