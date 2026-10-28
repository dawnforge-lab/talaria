package lab.dawnforge.hermes.core.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync

internal val hermesJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val redirectCodes = setOf(301, 302, 303, 307, 308)
private const val MAX_REDIRECTS = 5

/** Path segments that mean a redirect landed on a sign-in page (as the iOS app's `DashboardSignInRedirect`). */
private val signInSegments = setOf("login", "signin", "sign_in", "sign-in")

internal fun HttpUrl.isSignInPath(): Boolean {
    val segments = pathSegments.map(String::lowercase)
    if (segments.any(signInSegments::contains)) return true
    return segments.zipWithNext().any { (a, b) -> a == "oauth2" && b == "start" }
}

internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host.equals(other.host, ignoreCase = true) && port == other.port

/** A finished exchange, with the body already read (bounded) so the response can be closed. */
internal class HttpReply(
    val code: Int,
    val url: HttpUrl,
    val location: HttpUrl?,
    val retryAfter: String?,
    val body: String,
    /** True when at least one same-origin redirect was followed to get here. */
    val redirected: Boolean,
) {
    val isSuccessful: Boolean get() = code in 200..299
    val isRedirect: Boolean get() = code in redirectCodes

    fun jsonObject(): JsonObject? = runCatching { hermesJson.parseToJsonElement(body) as? JsonObject }.getOrNull()

    /** Hermes' error text: `detail`, then `error`, then `message`. */
    fun errorDetail(): String? {
        val json = jsonObject() ?: return null
        return listOf("detail", "error", "message").firstNotNullOfOrNull { key ->
            (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
        }
    }
}

/**
 * Sends [request] on a client that does not follow redirects itself, following only same-origin
 * redirects that do not lead to a sign-in page. A redirect elsewhere comes back as the reply so
 * the caller can treat it as "sign-in required". Cookies set along the way land in the client's
 * cookie jar.
 */
internal suspend fun OkHttpClient.exchange(request: Request, maxBodyBytes: Long): HttpReply {
    var current = request
    var redirected = false
    repeat(MAX_REDIRECTS + 1) {
        val reply = newCall(current).awaitReply(maxBodyBytes, redirected)
        val location = reply.location
        if (!reply.isRedirect || location == null) return reply
        if (!location.sameOrigin(current.url) || location.isSignInPath()) return reply
        current = current.redirectTo(location, reply.code)
        redirected = true
    }
    fail(ConnectionFailure.UnexpectedResponse(status = null, detail = "Too many redirects"))
}

private fun Request.redirectTo(location: HttpUrl, code: Int): Request {
    val keepsMethod = code == 307 || code == 308 || method == "GET" || method == "HEAD"
    return newBuilder()
        .url(location)
        .apply { if (!keepsMethod) method("GET", null) }
        .build()
}

private suspend fun Call.awaitReply(maxBodyBytes: Long, redirected: Boolean): HttpReply {
    val response: Response = try {
        executeAsync()
    } catch (e: IOException) {
        fail(e.toConnectionFailure())
    }
    // The body is read, and the reply closed, on an I/O thread: the body may still be on its way
    // after the headers (no length, a slow proxy or link), and reading or closing it then reads the
    // network, which Android refuses on the main thread a screen calls from, stopping the app.
    return withContext(Dispatchers.IO) {
        response.use {
            val body = try {
                it.body.source().let { source ->
                    if (source.request(maxBodyBytes + 1)) {
                        fail(ConnectionFailure.UnexpectedResponse(it.code, "Response too large"))
                    }
                    source.buffer.readUtf8()
                }
            } catch (e: IOException) {
                fail(e.toConnectionFailure())
            }
            HttpReply(
                code = it.code,
                url = it.request.url,
                location = it.header("Location")?.let { value -> it.request.url.resolve(value) },
                retryAfter = it.header("Retry-After"),
                body = body,
                redirected = redirected,
            )
        }
    }
}

/** Classifies a network exception, for callers that talk to the dashboard outside this module. */
fun IOException.toConnectionFailure(): ConnectionFailure = when (this) {
    is UnknownHostException -> ConnectionFailure.HostNotFound
    is ConnectException, is NoRouteToHostException -> ConnectionFailure.ConnectionRefused
    is SSLException -> ConnectionFailure.TlsFailure(message)
    is InterruptedIOException -> ConnectionFailure.TimedOut
    else -> ConnectionFailure.NetworkError(message)
}
