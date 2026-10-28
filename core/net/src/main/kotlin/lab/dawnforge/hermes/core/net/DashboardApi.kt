package lab.dawnforge.hermes.core.net

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardUrl
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.buffer
import okio.sink

/**
 * The dashboard's JSON REST API (`/api/...`) with a signed-in session's cookies.
 *
 * Hermes refreshes an expired access cookie on any request and rotates the cookies in the
 * response, so [cookieJar] must be the dashboard's one shared, persisted jar: replaying an old
 * refresh cookie can get the session revoked. Hermes needs no CSRF token or Origin header.
 */
class DashboardApi(private val clients: DashboardClientFactory) {

    /**
     * `GET` [path] (extra [segments] are percent-encoded) with [query]; null values are left out.
     * [repeated] adds parameters that may appear more than once, after [query].
     */
    suspend fun get(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        segments: List<String> = emptyList(),
        query: Map<String, String?> = emptyMap(),
        repeated: List<Pair<String, String>> = emptyList(),
    ): NetResult<JsonElement> =
        send(url, cookieJar, cloudflareToken, "GET", path, segments, query, body = null, repeated = repeated)

    /**
     * Streams the file `GET` [path] answers into [target], refusing more than [maxBytes]
     * ([ConnectionFailure.TooLarge]). Answers the bytes written; a failed download leaves no file.
     */
    suspend fun download(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        query: Map<String, String?>,
        target: File,
        maxBytes: Long,
    ): NetResult<Long> = netResult {
        val client = clients.create(url, cookieJar) { cloudflareToken }
        val request = Request.Builder().url(buildUrl(url, path, emptyList(), query)).get().build()
        val response = try {
            client.newCall(request).executeAsync()
        } catch (e: IOException) {
            fail(e.toConnectionFailure())
        }
        // Reading the reply, even an error's text, and closing it read the network: off the caller's
        // (perhaps main) thread.
        withContext(Dispatchers.IO) {
            response.use { reply ->
                if (!reply.isSuccessful) {
                    val detail = runCatching { reply.peekBody(ERROR_PEEK).string() }.getOrDefault("")
                    fail(errorReply(reply, detail).failure() ?: ConnectionFailure.UnexpectedResponse(reply.code, null))
                }
                val length = reply.body.contentLength()
                if (length > maxBytes) fail(ConnectionFailure.TooLarge(maxBytes))
                try {
                    var total = 0L
                    reply.body.source().use { source ->
                        target.sink().buffer().use { sink ->
                            while (true) {
                                val read = source.read(sink.buffer, CHUNK)
                                if (read == -1L) break
                                total += read
                                if (total > maxBytes) fail(ConnectionFailure.TooLarge(maxBytes))
                                sink.emitCompleteSegments()
                            }
                        }
                    }
                    total
                } catch (e: IOException) {
                    target.delete()
                    fail(e.toConnectionFailure())
                } catch (e: ConnectionFailureException) {
                    target.delete()
                    throw e
                }
            }
        }
    }

    /**
     * `POST` [body] to [path], then [segments] (each percent-encoded), with [query]. [timeoutMs]
     * replaces the usual 30 s for a call Hermes takes long over (transcribing a long recording).
     */
    suspend fun post(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        body: JsonObject,
        query: Map<String, String?> = emptyMap(),
        segments: List<String> = emptyList(),
        timeoutMs: Long? = null,
    ): NetResult<JsonElement> =
        send(url, cookieJar, cloudflareToken, "POST", path, segments, query, body, timeoutMs = timeoutMs)

    suspend fun put(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        segments: List<String>,
        body: JsonObject,
        query: Map<String, String?> = emptyMap(),
    ): NetResult<JsonElement> = send(url, cookieJar, cloudflareToken, "PUT", path, segments, query, body)

    suspend fun patch(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        segments: List<String>,
        body: JsonObject,
        query: Map<String, String?> = emptyMap(),
    ): NetResult<JsonElement> = send(url, cookieJar, cloudflareToken, "PATCH", path, segments, query, body)

    suspend fun delete(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        segments: List<String>,
        query: Map<String, String?> = emptyMap(),
    ): NetResult<JsonElement> = send(url, cookieJar, cloudflareToken, "DELETE", path, segments, query, body = null)

    private suspend fun send(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
        method: String,
        path: String,
        segments: List<String>,
        query: Map<String, String?>,
        body: JsonObject?,
        repeated: List<Pair<String, String>> = emptyList(),
        timeoutMs: Long? = null,
    ): NetResult<JsonElement> = netResult {
        val client = clients.create(url, cookieJar) { cloudflareToken }.let { base ->
            if (timeoutMs == null) {
                base
            } else {
                base.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
            }
        }
        val request = Request.Builder()
            .url(buildUrl(url, path, segments, query, repeated))
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        val reply = client.exchange(request, BODY_LIMIT)
        reply.failure()?.let { fail(it) }
        if (reply.body.isBlank()) {
            JsonNull
        } else {
            runCatching { hermesJson.parseToJsonElement(reply.body) }.getOrNull()
                ?: fail(
                    ConnectionFailure.UnexpectedResponse(reply.code, "The dashboard sent something other than JSON"),
                )
        }
    }

    /** What an unsuccessful [HttpReply] means; null when it succeeded. */
    private fun HttpReply.failure(): ConnectionFailure? = when {
        // Hermes answers an API call it cannot authorise with 401; a redirect means a proxy in
        // front of it wants a browser sign-in.
        isRedirect -> ConnectionFailure.SignInRequired

        code == 401 -> ConnectionFailure.SignInRequired

        code == 403 -> errorDetail()?.let(ConnectionFailure::Refused) ?: ConnectionFailure.SignInRequired

        code == 404 -> ConnectionFailure.NotFound(errorDetail())

        code == 400 || code == 422 -> ConnectionFailure.Invalid(errorDetail())

        code == 413 -> ConnectionFailure.TooLarge(null)

        !isSuccessful -> unsuccessful()

        else -> null
    }

    private fun errorReply(response: Response, body: String) = HttpReply(
        code = response.code,
        url = response.request.url,
        location = response.header("Location")?.let(response.request.url::resolve),
        retryAfter = response.header("Retry-After"),
        body = body,
        redirected = false,
    )

    private fun buildUrl(
        url: DashboardUrl,
        path: String,
        segments: List<String>,
        query: Map<String, String?>,
        repeated: List<Pair<String, String>> = emptyList(),
    ): HttpUrl = url.resolve(path).toHttpUrl().newBuilder().apply {
        segments.forEach(::addPathSegment)
        query.forEach { (name, value) -> if (value != null) addQueryParameter(name, value) }
        repeated.forEach { (name, value) -> addQueryParameter(name, value) }
    }.build()

    private companion object {
        /**
         * A file Hermes sends as a data URL (`/api/fs/read-data-url`, up to 16 MiB) is a third larger
         * in base64; session lists of 500 rows stay well under this.
         */
        const val BODY_LIMIT = 24L * 1024 * 1024
        const val ERROR_PEEK = 64L * 1024
        const val CHUNK = 64L * 1024
        val JSON = "application/json".toMediaType()
    }
}
