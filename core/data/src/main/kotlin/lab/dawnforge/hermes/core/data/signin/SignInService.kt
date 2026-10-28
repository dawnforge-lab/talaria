package lab.dawnforge.hermes.core.data.signin

import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.session.SessionCookies
import lab.dawnforge.hermes.core.gateway.TicketResult
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardCookieJar
import lab.dawnforge.hermes.core.net.LoopbackReceiver
import lab.dawnforge.hermes.core.net.NativeTokens
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.core.net.Pkce
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.core.net.SessionMark
import lab.dawnforge.hermes.core.net.UpgradeCookieJar
import lab.dawnforge.hermes.core.net.randomToken
import okhttp3.CookieJar

sealed interface AddressInspection {
    /** The dashboard answered; [existing] is set when it was added before. */
    data class Ready(val url: DashboardUrl, val method: SignInMethod, val existing: Dashboard?) : AddressInspection

    /** [url] is the last address tried, or null when the input itself was invalid. */
    data class Failed(val failure: ConnectionFailure, val url: DashboardUrl?) : AddressInspection
}

sealed interface SignInResult {
    data class SignedIn(val dashboard: Dashboard, val hermesVersion: String?) : SignInResult

    data class Failed(val failure: ConnectionFailure) : SignInResult
}

sealed interface SessionState {
    val dashboard: Dashboard

    data class Ready(override val dashboard: Dashboard, val hermesVersion: String?) : SessionState

    /** The session is gone and no saved password could renew it. [reason] says why, if known. */
    data class NeedsSignIn(override val dashboard: Dashboard, val reason: ConnectionFailure?) : SessionState

    /** The dashboard could not be reached; the saved session may still be fine. */
    data class Unreachable(override val dashboard: Dashboard, val failure: ConnectionFailure) : SessionState
}

/**
 * Adding a dashboard and keeping its session alive: checks an address, signs in with a
 * password, and on launch confirms the saved session by minting a WebSocket ticket, signing in
 * again with the saved password when the session has expired.
 */
class SignInService(
    private val auth: DashboardAuthClient,
    private val repository: DashboardRepository,
    private val cookies: SessionCookies,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Clears what the app keeps of a dashboard's chats, on signing out of it or removing it. */
    private val forgetLocalData: suspend (DashboardId) -> Unit = {},
    /** Holds the app awake while a sign-in is in the browser; nothing to hold on the JVM. */
    private val keepAlive: BrowserSignInKeepAlive = BrowserSignInKeepAlive(null),
    /** Forgets a server's extra headers once no saved dashboard is on its origin. */
    private val forgetProxyHeaders: suspend (origin: String) -> Unit = {},
) {
    private val renewal = Mutex()

    /** Validates [input] and asks each candidate address how to sign in, stopping at the first answer. */
    suspend fun inspectAddress(input: String, cloudflareToken: CloudflareAccessToken?): AddressInspection {
        val candidates = when (val check = ConnectionUrlPolicy.check(input)) {
            is AddressCheck.Invalid -> return AddressInspection.Failed(
                ConnectionFailure.InvalidAddress(check.problem),
                null,
            )

            is AddressCheck.Valid -> check.candidates
        }
        var last: AddressInspection.Failed? = null
        for (url in candidates) {
            when (val result = auth.discoverSignInMethod(url, cloudflareToken)) {
                is NetResult.Success -> return AddressInspection.Ready(url, result.value, repository.dashboardFor(url))

                is NetResult.Failure -> {
                    // A sign-in proxy in front of Hermes: its page signs in, in the app's browser.
                    if (result.failure == ConnectionFailure.InteractiveSignInRequired) {
                        return AddressInspection.Ready(url, SignInMethod.Browser, repository.dashboardFor(url))
                    }
                    last = AddressInspection.Failed(result.failure, url)
                    if (!result.failure.worthTryingAnotherScheme()) break
                }
            }
        }
        return checkNotNull(last)
    }

    /** Signs in and, on success, saves the dashboard and makes it the active one. */
    suspend fun signInWithPassword(
        url: DashboardUrl,
        method: SignInMethod.Password,
        username: String,
        password: String,
        savePassword: Boolean,
        cloudflareToken: CloudflareAccessToken?,
    ): SignInResult {
        val name = username.trim()
        val jar = when (val verified = verifyPassword(url, method, name, password, cloudflareToken)) {
            is NetResult.Success -> verified.value
            is NetResult.Failure -> return SignInResult.Failed(verified.failure)
        }

        val existing = repository.dashboardFor(url)
        val dashboard = Dashboard(
            id = existing?.id ?: DashboardId(newId()),
            url = url,
            username = name,
            addedAt = existing?.addedAt ?: clock(),
        )
        // Secrets first, so anything watching the active dashboard sees it complete.
        cookies.replace(dashboard.id, jar.snapshot())
        repository.saveCloudflareToken(dashboard.id, cloudflareToken)
        repository.saveCredentials(dashboard.id, if (savePassword) DashboardCredentials(name, password) else null)
        repository.saveAndActivate(dashboard, method.providerName)

        return SignInResult.SignedIn(dashboard, hermesVersion(url, cloudflareToken))
    }

    /**
     * Signs in and opens a live-connection ticket without saving anything, as the connection
     * test does. The session it proves stays in memory, for a repair to use once.
     */
    suspend fun testPassword(
        url: DashboardUrl,
        method: SignInMethod.Password,
        username: String,
        password: String,
        cloudflareToken: CloudflareAccessToken?,
    ): NetResult<VerifiedSession> =
        when (val verified = verifyPassword(url, method, username.trim(), password, cloudflareToken)) {
            is NetResult.Success -> NetResult.Success(VerifiedSession(method.providerName, verified.value.snapshot()))
            is NetResult.Failure -> verified
        }

    /**
     * Saves what a repair tested, as iOS applies it: the address, the tested [session] (used
     * once), and the password only where one was saved before, never a new one. A move to another
     * server drops the saved password when none was tested, and the Cloudflare token, which
     * belongs to the old server. Returns the dashboard as it was, so a reconnect that fails can
     * put the address back with [restoreAddress].
     */
    suspend fun applyRepair(
        id: DashboardId,
        url: DashboardUrl,
        session: VerifiedSession?,
        username: String,
        password: String,
    ): RepairSave {
        val previous = repository.dashboard(id) ?: return RepairSave.Gone
        val moved = url != previous.url
        if (moved && repository.dashboardFor(url).let { it != null && it.id != id }) return RepairSave.AddressInUse
        val name = username.trim()
        if (session != null) {
            cookies.replace(id, session.cookies)
            val saved = repository.credentials(id)
            if (saved != null && saved != DashboardCredentials(name, password)) {
                repository.saveCredentials(id, DashboardCredentials(name, password))
            }
        } else if (moved) {
            repository.saveCredentials(id, null)
        }
        if (moved && !url.sameServerAs(previous.url)) repository.saveCloudflareToken(id, null)
        val updated = previous.copy(url = url, username = if (session != null) name else previous.username)
        if (updated != previous) {
            repository.saveAndActivate(updated, session?.providerName ?: repository.passwordProvider(id))
        }
        return RepairSave.Saved(previous, updated)
    }

    /** Puts a repaired dashboard's address back after its reconnect failed, as iOS does. */
    suspend fun restoreAddress(previous: Dashboard) {
        val current = repository.dashboard(previous.id) ?: return
        if (current.url != previous.url) {
            repository.saveAndActivate(current.copy(url = previous.url), repository.passwordProvider(previous.id))
        }
    }

    private fun DashboardUrl.sameServerAs(other: DashboardUrl): Boolean =
        scheme == other.scheme && host.equals(other.host, ignoreCase = true) && port == other.port

    /** The password sign-in and a ticket, on a fresh jar, so a failed attempt never disturbs a working session. */
    private suspend fun verifyPassword(
        url: DashboardUrl,
        method: SignInMethod.Password,
        username: String,
        password: String,
        cloudflareToken: CloudflareAccessToken?,
    ): NetResult<DashboardCookieJar> {
        val jar = DashboardCookieJar()
        auth.passwordLogin(url, jar, cloudflareToken, method.providerName, username, password)
            .onFailure { return NetResult.Failure(it) }
        auth.mintTicket(url, jar, cloudflareToken)
            .onFailure { return NetResult.Failure(it.afterAcceptedPassword()) }
        return NetResult.Success(jar)
    }

    /** The dashboard a sign-in through its own page is for: the saved one at [url], or a new one. */
    suspend fun browserTarget(url: DashboardUrl): DashboardId = repository.dashboardFor(url)?.id ?: DashboardId(newId())

    /**
     * The sign-in page for [id] at [url] was given up on. A dashboard it would have added leaves
     * nothing in the browser: its cookies (the sign-in service's too) would have no owner. A saved
     * dashboard's page keeps them, as they may be its session.
     */
    suspend fun abandonBrowserSignIn(id: DashboardId, url: DashboardUrl) {
        if (repository.dashboard(id) == null) cookies.discardBrowser(id, url.toString())
    }

    /** Forgets the browser data of sign-in pages that saved no dashboard, before a new one opens. */
    suspend fun sweepBrowserSignIns() {
        cookies.sweepBrowsers(repository.dashboards.first().mapTo(HashSet()) { it.id })
    }

    /**
     * Finishes a sign-in through the dashboard's own page in the app's browser, as iOS's: the
     * browser's cookies for [id] must open a live-connection ticket from the app too. Then the
     * browser keeps the session ([SessionCookies.useBrowser]) and the dashboard is saved. Fails
     * with [ConnectionFailure.SignInRequired] while the page hasn't signed in yet.
     */
    suspend fun completeBrowserSignIn(
        id: DashboardId,
        url: DashboardUrl,
        cloudflareToken: CloudflareAccessToken?,
    ): SignInResult {
        val store =
            cookies.browserStore(id)
                ?: return SignInResult.Failed(ConnectionFailure.SignInMethodNotSupported(SignInMethod.Browser))
        val jar = DashboardCookieJar().apply { browser = store }
        auth.mintTicket(url, jar, cloudflareToken).onFailure { return SignInResult.Failed(it) }

        val existing = repository.dashboard(id) ?: repository.dashboardFor(url)
        val dashboard = Dashboard(
            id = existing?.id ?: id,
            url = url,
            username = null,
            addedAt = existing?.addedAt ?: clock(),
        )
        cookies.useBrowser(dashboard.id)
        repository.saveCloudflareToken(dashboard.id, cloudflareToken)
        // The browser keeps this session; a saved password would only renew a different one.
        repository.saveCredentials(dashboard.id, null)
        repository.saveAndActivate(dashboard, passwordProvider = null)
        return SignInResult.SignedIn(dashboard, hermesVersion(url, cloudflareToken))
    }

    /**
     * The cookies a WebSocket upgrade to [id] carries: a browser session's, which a sign-in proxy
     * needs to let it through, as iOS sends them. The ticket authenticates the socket itself.
     */
    suspend fun upgradeCookies(id: DashboardId): CookieJar = UpgradeCookieJar(cookies.jar(id))

    /**
     * Signs in through the system browser with Hermes' native flow (RFC 8252): a loopback
     * listener on the phone receives the one-time code, and the PKCE verifier redeems it for a
     * bearer session. [openBrowser] shows the sign-in page (a Custom Tab); it returns at once, and
     * the user may take up to [BrowserSignInService.WAIT_MS] to finish there, with the app held
     * awake meanwhile.
     *
     * With [providerName] null, Hermes lets the user choose when it has more than one provider.
     */
    suspend fun signInWithBrowser(
        url: DashboardUrl,
        providerName: String?,
        cloudflareToken: CloudflareAccessToken?,
        openBrowser: suspend (String) -> Unit,
    ): SignInResult {
        val pkce = Pkce.create()
        val state = randomToken()
        val callback = keepAlive.whileWaiting {
            LoopbackReceiver.open().use { receiver ->
                coroutineScope {
                    // Listening before the browser opens: a quick sign-in must not find nobody there.
                    val waiting = async { receiver.await(state, BrowserSignInService.WAIT_MS) }
                    openBrowser(auth.nativeAuthorizeUrl(url, pkce, receiver.redirectUri, state, providerName))
                    waiting.await()
                }
            }
        }
        val code = when (callback) {
            is LoopbackReceiver.Callback.Code -> callback.code

            is LoopbackReceiver.Callback.Refused ->
                return SignInResult.Failed(ConnectionFailure.BrowserSignInRefused(callback.error))

            null -> return SignInResult.Failed(ConnectionFailure.BrowserSignInUnfinished)
        }
        val tokens = when (val redeemed = auth.redeemNativeCode(url, cloudflareToken, code, pkce)) {
            is NetResult.Success -> redeemed.value
            is NetResult.Failure -> return SignInResult.Failed(redeemed.failure)
        }
        // The session must open the chat socket too, before it is saved as working.
        val jar = DashboardCookieJar().apply { bearerToken = tokens.accessToken }
        auth.mintTicket(url, jar, cloudflareToken)
            .onFailure { return SignInResult.Failed(it.afterAcceptedPassword()) }

        val existing = repository.dashboardFor(url)
        val dashboard = Dashboard(
            id = existing?.id ?: DashboardId(newId()),
            url = url,
            username = null,
            addedAt = existing?.addedAt ?: clock(),
        )
        cookies.useTokens(dashboard.id, tokens)
        repository.saveCloudflareToken(dashboard.id, cloudflareToken)
        // A browser session renews with its refresh token, never a saved password.
        repository.saveCredentials(dashboard.id, null)
        repository.saveAndActivate(dashboard, passwordProvider = null)
        return SignInResult.SignedIn(dashboard, hermesVersion(url, cloudflareToken))
    }

    /** Confirms the saved session of [id] still works, renewing it with the saved password if needed. */
    suspend fun checkSession(id: DashboardId): SessionState? {
        val dashboard = repository.dashboard(id) ?: return null
        val cloudflareToken = repository.cloudflareToken(id)
        val jar = cookies.jar(id)
        // No stored session (signed out, or never saved): nothing to confirm, so ask nothing of the network.
        if (jar.isEmpty) return renew(dashboard, cloudflareToken, rejected = null)

        val tried = jar.mark()
        return when (val ticket = auth.mintTicket(dashboard.url, jar, cloudflareToken)) {
            is NetResult.Success -> SessionState.Ready(dashboard, hermesVersion(dashboard.url, cloudflareToken))

            is NetResult.Failure -> if (ticket.failure == ConnectionFailure.SignInRequired) {
                renew(dashboard, cloudflareToken, rejected = tried)
            } else {
                SessionState.Unreachable(dashboard, ticket.failure)
            }
        }
    }

    /**
     * Renews [id]'s session after a request was refused with sign-in required while sending
     * [rejected]. Returns [SessionState.Ready] when the session works again, e.g. because another
     * caller already renewed it.
     */
    suspend fun renewAfterRejection(id: DashboardId, rejected: SessionMark): SessionState? {
        val dashboard = repository.dashboard(id) ?: return null
        return renew(dashboard, repository.cloudflareToken(id), rejected)
    }

    /**
     * Mints a WebSocket ticket for [id]'s chat socket with the saved session. When the session has
     * expired it is renewed with the saved password first, exactly as on launch.
     */
    suspend fun mintGatewayTicket(id: DashboardId): TicketResult {
        val dashboard = repository.dashboard(id) ?: return TicketResult.SignInRequired(null)
        val cloudflareToken = repository.cloudflareToken(id)
        val jar = cookies.jar(id)
        val tried = jar.mark()
        if (!jar.isEmpty) {
            when (val ticket = auth.mintTicket(dashboard.url, jar, cloudflareToken)) {
                is NetResult.Success -> return TicketResult.Ticket(ticket.value)

                is NetResult.Failure -> if (ticket.failure != ConnectionFailure.SignInRequired) {
                    return TicketResult.Unavailable(ticket.failure)
                }
            }
        }
        val session = renew(
            dashboard,
            cloudflareToken,
            rejected = tried.takeIf {
                it.cookies.isNotEmpty() ||
                    it.bearerToken != null
            },
        )
        return when (session) {
            is SessionState.Ready -> when (
                val ticket = auth.mintTicket(
                    dashboard.url,
                    cookies.jar(id),
                    cloudflareToken,
                )
            ) {
                is NetResult.Success -> TicketResult.Ticket(ticket.value)
                is NetResult.Failure -> TicketResult.Unavailable(ticket.failure)
            }

            is SessionState.NeedsSignIn -> TicketResult.SignInRequired(session.reason)

            is SessionState.Unreachable -> TicketResult.Unavailable(session.failure)
        }
    }

    /**
     * Renews the session: a browser session with its refresh token, a password one by signing in
     * again with the saved password. One renewal at a time: a caller that waited while another
     * renewed finds the session changed since its own [rejected] request and uses it as is.
     */
    private suspend fun renew(
        dashboard: Dashboard,
        cloudflareToken: CloudflareAccessToken?,
        rejected: SessionMark?,
    ): SessionState = renewal.withLock {
        val id = dashboard.id
        val jar = cookies.jar(id)
        if (rejected != null && !jar.isEmpty && jar.mark() != rejected) {
            return@withLock SessionState.Ready(dashboard, hermesVersion(dashboard.url, cloudflareToken))
        }
        repository.nativeTokens(id)?.let { tokens ->
            return@withLock renewNative(dashboard, cloudflareToken, tokens, rejected)
        }
        // A sign-in through the dashboard's own page renews only there. The browser keeps its
        // cookies, so a sign-in proxy that still knows the user may sign them straight back in.
        if (cookies.isBrowserSession(id)) {
            return@withLock SessionState.NeedsSignIn(dashboard, ConnectionFailure.SignInRequired)
        }

        val credentials = repository.credentials(id)
        val provider = repository.passwordProvider(id)
        if (credentials == null || provider == null) {
            cookies.clear(id)
            return@withLock SessionState.NeedsSignIn(dashboard, rejected?.let { ConnectionFailure.SignInRequired })
        }

        val fresh = DashboardCookieJar()
        val login = auth.passwordLogin(
            dashboard.url,
            fresh,
            cloudflareToken,
            provider,
            credentials.username,
            credentials.password,
        )
        if (login is NetResult.Failure) {
            // A rejected password goes to the sign-in screen but keeps the saved one, as the iOS app does.
            return@withLock when (login.failure) {
                ConnectionFailure.AuthenticationRejected, ConnectionFailure.InteractiveSignInRequired ->
                    SessionState.NeedsSignIn(dashboard, login.failure)

                else -> SessionState.Unreachable(dashboard, login.failure)
            }
        }
        when (val ticket = auth.mintTicket(dashboard.url, fresh, cloudflareToken)) {
            is NetResult.Failure ->
                return@withLock SessionState.Unreachable(dashboard, ticket.failure.afterAcceptedPassword())

            is NetResult.Success -> Unit
        }
        cookies.replace(id, fresh.snapshot())
        SessionState.Ready(dashboard, hermesVersion(dashboard.url, cloudflareToken))
    }

    /** A browser session renewed with its refresh token; a refused one needs a new sign-in. */
    private suspend fun renewNative(
        dashboard: Dashboard,
        cloudflareToken: CloudflareAccessToken?,
        tokens: NativeTokens,
        rejected: SessionMark?,
    ): SessionState {
        // Not refused and not near its end: it is good as it is.
        if (rejected == null && tokens.expiresAtMs - clock() > NATIVE_RENEW_EARLY_MS) {
            return SessionState.Ready(dashboard, hermesVersion(dashboard.url, cloudflareToken))
        }
        return when (val refreshed = auth.refreshNativeTokens(dashboard.url, cloudflareToken, tokens)) {
            is NetResult.Success -> {
                cookies.useTokens(dashboard.id, refreshed.value)
                SessionState.Ready(dashboard, hermesVersion(dashboard.url, cloudflareToken))
            }

            is NetResult.Failure -> if (refreshed.failure == ConnectionFailure.SignInRequired) {
                cookies.clear(dashboard.id)
                SessionState.NeedsSignIn(dashboard, ConnectionFailure.SignInRequired)
            } else {
                SessionState.Unreachable(dashboard, refreshed.failure)
            }
        }
    }

    /** Why the dashboard does not answer, or null when it does: the gateway's reconnect probe. */
    suspend fun probe(id: DashboardId): ConnectionFailure? {
        // A removed dashboard is not probed; minting its ticket then reports sign-in required.
        val dashboard = repository.dashboard(id) ?: return null
        return auth.probe(dashboard.url, repository.cloudflareToken(id))
    }

    suspend fun signOut(id: DashboardId) {
        cookies.clear(id)
        forgetLocalData(id)
        repository.signOut(id)
    }

    suspend fun remove(id: DashboardId) {
        val origin = repository.dashboard(id)?.url?.let(ProxyHeaderRules::origin)
        cookies.forget(id)
        forgetLocalData(id)
        repository.remove(id)
        // Another dashboard on the same server keeps its headers, as on iOS.
        if (origin != null && repository.dashboards.first().none { ProxyHeaderRules.origin(it.url) == origin }) {
            forgetProxyHeaders(origin)
        }
    }

    private suspend fun hermesVersion(url: DashboardUrl, cloudflareToken: CloudflareAccessToken?): String? =
        (auth.status(url, cloudflareToken) as? NetResult.Success)?.value?.version

    /** After the password worked, a session problem is never "wrong password". */
    private fun ConnectionFailure.afterAcceptedPassword(): ConnectionFailure = when (this) {
        ConnectionFailure.SignInRequired -> ConnectionFailure.SessionTicketFailure(
            "The dashboard accepted the password but not the session",
        )

        else -> this
    }

    /**
     * Whether the other scheme might still work. A missing host, a timeout or a proxy that wants
     * a browser will not change with the scheme; a refused connection or a TLS error might.
     */
    private fun ConnectionFailure.worthTryingAnotherScheme(): Boolean = when (this) {
        is ConnectionFailure.ConnectionRefused,
        is ConnectionFailure.TlsFailure,
        is ConnectionFailure.NetworkError,
        is ConnectionFailure.NotADashboard,
        is ConnectionFailure.UnexpectedResponse,
        -> true

        else -> false
    }
}

private inline fun <T> NetResult<T>.onFailure(action: (ConnectionFailure) -> Unit): NetResult<T> {
    if (this is NetResult.Failure) action(failure)
    return this
}

/** A browser session is renewed this long before its token expires. */
private const val NATIVE_RENEW_EARLY_MS = 60_000L
