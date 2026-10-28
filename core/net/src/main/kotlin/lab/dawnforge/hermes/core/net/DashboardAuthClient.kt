package lab.dawnforge.hermes.core.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

/** Builds the HTTP client for one dashboard on top of a shared base client (shared connection pool). */
class DashboardClientFactory(
    private val base: OkHttpClient,
    /** Extra headers a reverse proxy asks for, by origin. */
    private val proxyHeaders: ProxyHeaders = ProxyHeaders.NONE,
) {
    fun create(url: DashboardUrl, cookieJar: CookieJar, cloudflareToken: () -> CloudflareAccessToken?): OkHttpClient =
        base.newBuilder()
            // Redirects are followed by hand (same origin only), see exchange().
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(cookieJar)
            .addInterceptor(
                DashboardHeadersInterceptor(
                    url,
                    cloudflareToken,
                    { (cookieJar as? DashboardCookieJar)?.bearerToken },
                    proxyHeaders,
                ),
            )
            .build()

    /**
     * The client for the dashboard's chat WebSocket. A socket stays open for hours, so there is no
     * read or call timeout; OkHttp's protocol pings keep NAT and proxy mappings alive, and the
     * gateway client's own `gateway.ping` detects a dead socket. The ticket authenticates the
     * upgrade; [cookies] are only a sign-in proxy's ([UpgradeCookieJar]), which it needs to let
     * the upgrade through.
     */
    fun createWebSocketClient(
        url: DashboardUrl,
        cookies: CookieJar = CookieJar.NO_COOKIES,
        cloudflareToken: () -> CloudflareAccessToken?,
    ): OkHttpClient = create(url, cookies, cloudflareToken).newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(WEBSOCKET_PING_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val WEBSOCKET_PING_SECONDS = 20L
        private const val HTTP2_PING_SECONDS = 30L

        fun defaultBaseClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            // A pooled HTTP/2 connection that died with the network (Wi-Fi to mobile, Tailscale
            // reconnecting) otherwise keeps accepting calls that each time out; a failed ping
            // closes it.
            .pingInterval(HTTP2_PING_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

/** Public facts from `GET /api/status`. */
data class DashboardStatus(
    val version: String?,
    val authFlows: List<String>?,
    val gatewayRunning: Boolean?,
    /** False when the dashboard runs without sign-in (Hermes' loopback mode); null if not reported. */
    val authRequired: Boolean? = null,
)

/** A provider listed by `GET /api/auth/providers`. */
internal data class AuthProvider(val name: String?, val supportsPassword: Boolean?, val supportsSession: Boolean?)

/**
 * Hermes' dashboard sign-in endpoints: working out the sign-in method, password sign-in, and
 * minting the single-use WebSocket ticket that proves a session works.
 */
class DashboardAuthClient(private val clients: DashboardClientFactory) {

    /** Asks the dashboard how to sign in. Sends no cookies. */
    suspend fun discoverSignInMethod(
        url: DashboardUrl,
        cloudflareToken: CloudflareAccessToken?,
    ): NetResult<SignInMethod> = netResult {
        val client = clients.create(url, CookieJar.NO_COOKIES) { cloudflareToken }
        val reply = client.exchange(get(url, "/api/auth/providers"), AUTH_BODY_LIMIT)

        if (reply.isRedirect) {
            val location = reply.location ?: fail(ConnectionFailure.NotADashboard(reply.code))
            if (cloudflareToken?.isConfigured == true && location.isCloudflareAccessHost()) {
                fail(ConnectionFailure.CloudflareTokenRejected)
            }
            fail(ConnectionFailure.InteractiveSignInRequired)
        }
        // A dashboard with sign-in off (bound to 127.0.0.1 with no public_url) answers the
        // providers list with 401 and accepts only its own machine. /api/status says so.
        if (reply.code == 401 && status(url, cloudflareToken).authRequiredOrNull() == false) {
            fail(ConnectionFailure.SignInDisabled)
        }
        if (!reply.isSuccessful) fail(reply.unsuccessful())

        val json = reply.jsonObject()
        val providers = (json?.get("providers") as? JsonArray)
            ?: fail(
                if (reply.redirected) {
                    ConnectionFailure.InteractiveSignInRequired
                } else {
                    ConnectionFailure.NotADashboard(reply.code)
                },
            )
        val authFlows = json["auth_flows"].stringListOrNull()
        resolveMethod(providers.mapNotNull(::parseProvider), authFlows) {
            when (val status = status(url, cloudflareToken)) {
                is NetResult.Success -> status.value.authFlows.orEmpty()
                is NetResult.Failure -> fail(status.failure)
            }
        }
    }

    /** Reads `GET /api/status`, which needs no sign-in. */
    suspend fun status(url: DashboardUrl, cloudflareToken: CloudflareAccessToken?): NetResult<DashboardStatus> =
        netResult {
            val client = clients.create(url, CookieJar.NO_COOKIES) { cloudflareToken }
            val reply = client.exchange(get(url, "/api/status"), STATUS_BODY_LIMIT)
            if (reply.isRedirect) fail(ConnectionFailure.InteractiveSignInRequired)
            if (!reply.isSuccessful) fail(reply.unsuccessful())
            val json = reply.jsonObject() ?: fail(ConnectionFailure.NotADashboard(reply.code))
            DashboardStatus(
                version = (json["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                authFlows = json["auth_flows"].stringListOrNull(),
                gatewayRunning = (json["gateway_running"] as? JsonPrimitive)?.booleanOrNull,
                authRequired = (json["auth_required"] as? JsonPrimitive)?.booleanOrNull,
            )
        }

    /**
     * A cheap "is the dashboard up" probe (`HEAD /api/status`) used before reconnecting, so no
     * ticket is spent while Hermes or the proxy in front of it is down. Returns null when it is up
     * (any answer except the gateway errors a proxy gives for a dead backend), else why not.
     */
    suspend fun probe(url: DashboardUrl, cloudflareToken: CloudflareAccessToken?): ConnectionFailure? {
        val client = clients.create(url, CookieJar.NO_COOKIES) { cloudflareToken }.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url.resolve("/api/status")).head().build()
        return try {
            val response = client.newCall(request).executeAsync()
            // Closing a reply may read the network (an unread body): off the caller's thread.
            withContext(Dispatchers.IO) {
                response.use {
                    if (it.code in
                        unavailableCodes
                    ) {
                        ConnectionFailure.DashboardUnavailable(it.code)
                    } else {
                        null
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // The pooled connection may be the problem rather than the dashboard (a network path
            // that changed without Android noticing), so the next probe dials afresh.
            client.connectionPool.evictAll()
            e.toConnectionFailure()
        }
    }

    private fun NetResult<DashboardStatus>.authRequiredOrNull(): Boolean? =
        (this as? NetResult.Success)?.value?.authRequired

    /**
     * Signs in with a username and password. On success the session cookies are in [cookieJar];
     * pass a fresh jar so a failed attempt never disturbs a working session.
     */
    suspend fun passwordLogin(
        url: DashboardUrl,
        cookieJar: DashboardCookieJar,
        cloudflareToken: CloudflareAccessToken?,
        providerName: String,
        username: String,
        password: String,
    ): NetResult<Unit> = netResult {
        val client = clients.create(url, cookieJar) { cloudflareToken }
        val body = buildJsonObject {
            put("provider", providerName)
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(url.resolve("/auth/password-login")).post(body).build()
        val reply = client.exchange(request, AUTH_BODY_LIMIT)

        when {
            reply.isRedirect -> fail(ConnectionFailure.InteractiveSignInRequired)

            reply.code == 429 -> fail(ConnectionFailure.RateLimited(reply.retryAfter?.trim()?.toLongOrNull()))

            reply.code == 401 || reply.code == 403 -> fail(ConnectionFailure.AuthenticationRejected)

            !reply.isSuccessful -> fail(reply.unsuccessful())

            (reply.jsonObject()?.get("ok") as? JsonPrimitive)?.booleanOrNull == false ->
                fail(ConnectionFailure.AuthenticationRejected)
        }
        if (!cookieJar.hasCookiesFor(url.resolve(TICKET_PATH).toHttpUrl())) {
            fail(ConnectionFailure.SessionTicketFailure("Signed in, but the dashboard set no usable session cookie"))
        }
    }

    // ---- Native sign-in (RFC 8252: the system browser, a loopback redirect and PKCE) -------------

    /**
     * Where the browser starts a native sign-in (`GET /auth/native/authorize`). Hermes signs the
     * user in with [providerName] (or lets them choose, when null) and sends the browser to
     * [redirectUri] with a one-time code for [redeemNativeCode] and [state] echoed back.
     */
    fun nativeAuthorizeUrl(
        url: DashboardUrl,
        pkce: Pkce,
        redirectUri: String,
        state: String,
        providerName: String?,
    ): String = url.resolve("/auth/native/authorize").toHttpUrl().newBuilder()
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", state)
        .apply { if (providerName != null) addQueryParameter("provider", providerName) }
        .build()
        .toString()

    /** Redeems the code a native sign-in brought back (`POST /auth/native/token`). The code works once, for two minutes. */
    suspend fun redeemNativeCode(
        url: DashboardUrl,
        cloudflareToken: CloudflareAccessToken?,
        code: String,
        pkce: Pkce,
    ): NetResult<NativeTokens> = nativeTokens(
        url,
        cloudflareToken,
        "/auth/native/token",
        buildJsonObject {
            put("code", code)
            put("code_verifier", pkce.verifier)
        },
    )

    /**
     * Renews a native session (`POST /auth/native/refresh`). Hermes may rotate the refresh token,
     * so the new one always replaces the old. Sign-in required when the refresh token is refused.
     */
    suspend fun refreshNativeTokens(
        url: DashboardUrl,
        cloudflareToken: CloudflareAccessToken?,
        tokens: NativeTokens,
    ): NetResult<NativeTokens> {
        val refreshToken = tokens.refreshToken ?: return NetResult.Failure(ConnectionFailure.SignInRequired)
        return nativeTokens(
            url,
            cloudflareToken,
            "/auth/native/refresh",
            buildJsonObject {
                put("refresh_token", refreshToken)
                put("provider", tokens.provider)
            },
        )
    }

    private suspend fun nativeTokens(
        url: DashboardUrl,
        cloudflareToken: CloudflareAccessToken?,
        path: String,
        body: JsonObject,
    ): NetResult<NativeTokens> = netResult {
        val client = clients.create(url, CookieJar.NO_COOKIES) { cloudflareToken }
        val request = Request.Builder().url(url.resolve(path)).post(body.toString().toRequestBody(JSON)).build()
        val reply = client.exchange(request, AUTH_BODY_LIMIT)
        when {
            // An expired or reused code, or a refused refresh token: start a new sign-in.
            reply.code == 400 || reply.code == 401 -> fail(ConnectionFailure.SignInRequired)

            reply.code == 429 -> fail(ConnectionFailure.RateLimited(reply.retryAfter?.trim()?.toLongOrNull()))

            !reply.isSuccessful -> fail(reply.unsuccessful())
        }
        val obj = reply.jsonObject() ?: fail(ConnectionFailure.UnexpectedResponse(reply.code, "No tokens in the reply"))
        fun text(key: String) =
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)
        val accessToken = text("access_token")
            ?: fail(ConnectionFailure.UnexpectedResponse(reply.code, "No access token in the reply"))
        val expiresAt = (obj["expires_at"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        NativeTokens(
            accessToken = accessToken,
            refreshToken = text("refresh_token"),
            // Unix seconds; with none given, treat the token as short-lived so it is renewed early.
            expiresAtMs = expiresAt?.let { (it * 1000).toLong() } ?: (System.currentTimeMillis() + UNKNOWN_EXPIRY_MS),
            provider = text("provider").orEmpty(),
            userId = text("user_id"),
        )
    }

    /**
     * Mints a WebSocket ticket (`POST /api/auth/ws-ticket`). Tickets are single use and short
     * lived, so mint one right before each connection. The ticket is a secret: never log it.
     */
    suspend fun mintTicket(
        url: DashboardUrl,
        cookieJar: CookieJar,
        cloudflareToken: CloudflareAccessToken?,
    ): NetResult<String> = netResult {
        val client = clients.create(url, cookieJar) { cloudflareToken }
        val request = Request.Builder()
            .url(url.resolve(TICKET_PATH))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val reply = client.exchange(request, AUTH_BODY_LIMIT)

        when {
            reply.isRedirect -> fail(ConnectionFailure.SignInRequired)

            reply.code == 401 -> fail(ConnectionFailure.SignInRequired)

            // A Hermes 403 with a detail is a refusal; without one, the session is gone.
            reply.code == 403 -> fail(
                reply.errorDetail()?.let(ConnectionFailure::Refused) ?: ConnectionFailure.SignInRequired,
            )

            !reply.isSuccessful -> fail(reply.unsuccessful())
        }
        (reply.jsonObject()?.get("ticket") as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(String::isNotBlank)
            ?: fail(ConnectionFailure.SessionTicketFailure("No ticket in the response"))
    }

    private inline fun resolveMethod(
        providers: List<AuthProvider>,
        authFlows: List<String>?,
        statusAuthFlows: () -> List<String>,
    ): SignInMethod {
        val password = providers.filter { it.supportsPassword == true && it.name != null }
        val native = providers.filter { it.supportsPassword == false && it.supportsSession != false }
        val legacy = providers.filter { it.supportsPassword == null && it.supportsSession != false && it.name != null }

        if (password.isNotEmpty()) {
            // The browser is offered for the providers that aren't the password, as iOS's: one is
            // picked by name, several are left to Hermes' chooser. The password provider in the
            // browser would only show the form the app already has.
            val browser = native.takeIf { it.isNotEmpty() && NATIVE_PKCE in (authFlows ?: statusAuthFlows()) }
            return SignInMethod.Password(
                providerName = password.first().name!!,
                browser = browser?.let { SignInMethod.NativeOAuth(it.singleOrNull()?.name) },
            )
        }
        if (native.isNotEmpty()) {
            val flows = authFlows ?: statusAuthFlows()
            return if (NATIVE_PKCE in flows) {
                SignInMethod.NativeOAuth(native.singleOrNull()?.name)
            } else {
                SignInMethod.Browser
            }
        }
        if (legacy.isNotEmpty()) return SignInMethod.Browser
        fail(ConnectionFailure.NoSignInMethod)
    }

    private fun parseProvider(element: kotlinx.serialization.json.JsonElement): AuthProvider? {
        val obj = element as? JsonObject ?: return null
        return AuthProvider(
            name = (obj["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            supportsPassword = (obj["supports_password"] as? JsonPrimitive)?.booleanOrNull,
            supportsSession = (obj["supports_session"] as? JsonPrimitive)?.booleanOrNull,
        )
    }

    private fun get(url: DashboardUrl, path: String) = Request.Builder().url(url.resolve(path)).get().build()

    private companion object {
        const val TICKET_PATH = "/api/auth/ws-ticket"
        const val NATIVE_PKCE = "native_pkce"
        const val AUTH_BODY_LIMIT = 64L * 1024

        /** How long a native token is trusted when Hermes gives no expiry. */
        private const val UNKNOWN_EXPIRY_MS = 5 * 60 * 1000L
        const val STATUS_BODY_LIMIT = 1024L * 1024

        // Long enough for a fresh TLS handshake over a slow path (mobile data into a tailnet takes
        // several seconds); a shorter limit reports a live dashboard as down and stalls reconnects.
        const val PROBE_TIMEOUT_SECONDS = 10L
        val JSON = "application/json".toMediaType()
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringListOrNull(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

private fun HttpUrl.isCloudflareAccessHost(): Boolean =
    host.equals("cloudflareaccess.com", ignoreCase = true) || host.endsWith(".cloudflareaccess.com", ignoreCase = true)

private val unavailableCodes = setOf(502, 503, 504, 520, 521, 522, 523, 524, 530)

internal fun HttpReply.unsuccessful(): ConnectionFailure = when {
    code in unavailableCodes -> ConnectionFailure.DashboardUnavailable(code)
    code == 404 -> ConnectionFailure.NotADashboard(code)
    else -> ConnectionFailure.UnexpectedResponse(code, errorDetail())
}
