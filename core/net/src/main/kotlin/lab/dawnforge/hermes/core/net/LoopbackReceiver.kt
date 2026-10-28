package lab.dawnforge.hermes.core.net

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The redirect target of a native sign-in (RFC 8252): a one-shot HTTP listener on 127.0.0.1, on
 * a port the system picks. The browser lands on [redirectUri] with `?code=…&state=…` once the user
 * has signed in, and the listener answers with a short page saying to return to the app.
 *
 * Hermes accepts only `http://127.0.0.1:<port>` or `http://[::1]:<port>` as a native redirect,
 * not a custom scheme or an App Link, which is why the app listens itself.
 */
class LoopbackReceiver private constructor(private val server: ServerSocket) : Closeable {
    val redirectUri: String = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"

    /** What the browser brought back. */
    sealed interface Callback {
        data class Code(val code: String) : Callback

        /** The provider refused or the user cancelled: `?error=…` instead of a code. */
        data class Refused(val error: String) : Callback
    }

    /**
     * Waits for the callback whose `state` is [expectedState], answering anything else (a favicon,
     * a stale tab) and waiting on. Null after [timeoutMs]: Hermes shows sign-in errors in the
     * browser tab and never redirects them here, so a timeout is the only sign of a failed sign-in.
     */
    suspend fun await(expectedState: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Callback? =
        withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) {
                // accept() ignores interrupts, so it wakes up regularly to see whether the wait was cancelled.
                server.soTimeout = ACCEPT_POLL_MS
                var callback: Callback? = null
                while (callback == null) {
                    if (Thread.interrupted()) throw InterruptedException("cancelled")
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (e: SocketException) {
                        if (server.isClosed) throw InterruptedException("closed") else throw e
                    }
                    callback = socket.use { answer(it, expectedState) }
                }
                callback
            }
        }

    override fun close() {
        try {
            server.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    /** Reads one request; a callback for this sign-in is returned, anything else is turned away. */
    private fun answer(socket: Socket, expectedState: String): Callback? {
        socket.soTimeout = READ_TIMEOUT_MS
        val requestLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
        // "GET /callback?code=…&state=… HTTP/1.1"
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val callback = when {
            path != CALLBACK_PATH || query["state"] != expectedState -> null
            query["code"] != null -> Callback.Code(query.getValue("code"))
            query["error"] != null -> Callback.Refused(query.getValue("error"))
            else -> null
        }
        val (status, page) = if (callback != null) "200 OK" to DONE_PAGE else "404 Not Found" to NOT_FOUND_PAGE
        val body = page.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                (
                    "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            write(body)
            flush()
        }
        return callback
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&')
        .filter(String::isNotEmpty)
        .associate { pair ->
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    companion object {
        const val CALLBACK_PATH = "/callback"

        /** How long a sign-in in the browser may take; Hermes' desktop app waits as long. */
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

        private const val READ_TIMEOUT_MS = 5_000
        private const val ACCEPT_POLL_MS = 250

        /** Listens on 127.0.0.1 only: nothing off the phone can reach it. */
        fun open(): LoopbackReceiver = LoopbackReceiver(ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")))

        private const val DONE_PAGE = "<!doctype html><meta name=viewport content=\"width=device-width\">" +
            "<title>Signed in</title><body style=\"font-family:sans-serif;padding:2em\">" +
            "<h2>Signed in</h2><p>You can go back to the app.</p></body>"

        private const val NOT_FOUND_PAGE = "<!doctype html><title>Not found</title>"
    }
}
