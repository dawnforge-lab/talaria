package lab.dawnforge.hermes.feature.connect

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.AppUnlock
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.dashboard.ProxyHeadersStore
import lab.dawnforge.hermes.core.data.session.WebViewCookieStores
import lab.dawnforge.hermes.core.data.signin.AddressInspection
import lab.dawnforge.hermes.core.data.signin.SignInResult
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.AddressCheck
import lab.dawnforge.hermes.core.net.ConnectLink
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.ConnectionUrlPolicy
import lab.dawnforge.hermes.core.net.ProxyHeader
import lab.dawnforge.hermes.core.net.ProxyHeaderRules
import lab.dawnforge.hermes.core.net.ScannedCode
import lab.dawnforge.hermes.feature.connect.navigation.ConnectGraph
import lab.dawnforge.hermes.feature.connect.web.WebSignInPolicy

/**
 * The dashboard's own sign-in page, shown in the app for a sign-in service in front of Hermes: the
 * dashboard [id] it signs in (its WebView [profile] keeps the cookies), and the Cloudflare and
 * extra [headers] the service may ask for. In memory only: the headers can be secrets.
 */
data class WebSignIn(
    val id: DashboardId,
    val url: DashboardUrl,
    val profile: String,
    val headers: Map<String, String>,
) {
    /** The dashboard's sign-in route, which the service in front of it sends to its own page. */
    val startUrl: String get() = url.resolve("/login")

    val origin: String get() = "${url.scheme}://${url.host}" + (url.port?.let { ":$it" } ?: "")

    override fun toString(): String = "WebSignIn(id=$id, url=$url, headers=${headers.keys})"
}

/** The dashboard that answered, waiting for the user to sign in: with a password, in the browser, or either. */
data class ConnectTarget(val url: DashboardUrl, val method: SignInMethod) {
    val passwordProvider: String? get() = (method as? SignInMethod.Password)?.providerName

    /** Hermes' native flow is there: the sign-in can happen in the browser. */
    val browserAvailable: Boolean
        get() = method is SignInMethod.NativeOAuth || (method as? SignInMethod.Password)?.browserAlsoAvailable == true

    /** The provider the browser sign-in starts with; null lets Hermes offer its choice. */
    val browserProvider: String?
        get() = when (method) {
            is SignInMethod.NativeOAuth -> method.providerName

            // The browser's own provider: the password one would only show the form again.
            is SignInMethod.Password -> method.browser?.providerName

            SignInMethod.Browser -> null
        }
}

data class ConnectUiState(
    val address: String = "",
    val cloudflareShown: Boolean = false,
    val cloudflareClientId: String = "",
    val cloudflareClientSecret: String = "",
    /** Extra headers a reverse proxy asks for, sent to this server over HTTPS. */
    val headersShown: Boolean = false,
    val headers: List<ProxyHeader> = emptyList(),
    /** Headers were typed for an address that isn't HTTPS, where they're never sent. */
    val headersNeedHttps: Boolean = false,
    /** A setup link waiting for the user to confirm it. */
    val pendingLink: ConnectLink? = null,
    /** The dashboard's own sign-in page, while it shows in the app. */
    val webSignIn: WebSignIn? = null,
    /** Checking whether the page has signed in. */
    val completingWeb: Boolean = false,
    /** Why the page's sign-in didn't work. */
    val webError: ConnectionFailure? = null,
    val checkingAddress: Boolean = false,
    val addressError: ConnectionFailure? = null,
    val target: ConnectTarget? = null,
    val username: String = "",
    val password: String = "",
    val savePassword: Boolean = true,
    /** The saved password is opened with the phone's fingerprint, face or screen lock (iOS's Face ID). */
    val unlockWithBiometrics: Boolean = true,
    /** The phone has a fingerprint, face or screen lock to open it with. */
    val biometricsAvailable: Boolean = false,
    val signingIn: Boolean = false,
    /** A sign-in is open in the browser; the app waits for it to come back. */
    val signingInWithBrowser: Boolean = false,
    val signInError: ConnectionFailure? = null,
    /** Set when a scanned code was not a dashboard address or setup link. */
    val unreadableScan: Boolean = false,
) {
    val busy: Boolean get() = checkingAddress || signingIn || signingInWithBrowser

    /** The link points at an address only the Hermes machine itself can reach. */
    val linkIsLoopbackOnly: Boolean
        get() {
            val link = pendingLink ?: return false
            val check = ConnectionUrlPolicy.check(link.address) as? AddressCheck.Valid ?: return false
            return ConnectionUrlPolicy.isLoopbackOnly(check.candidates.first().host)
        }
}

sealed interface ConnectEvent {
    data object ShowSignIn : ConnectEvent

    /** Open the dashboard's own sign-in page in the app ([ConnectUiState.webSignIn]). */
    data object ShowWebSignIn : ConnectEvent

    data object SignedIn : ConnectEvent

    /** Show Hermes' sign-in page in a Custom Tab. */
    data class OpenBrowser(val url: String) : ConnectEvent
}

/**
 * Drives the add-a-dashboard flow across its screens (address, scan, sign-in). Scoped to the
 * flow's navigation graph, so the screens share one instance and it is cleared when the flow ends.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val signIn: SignInService,
    private val proxyHeaders: ProxyHeadersStore,
    private val browserStores: WebViewCookieStores,
    private val dashboards: DashboardRepository,
    private val unlock: AppUnlock,
    @ApplicationContext context: Context,
    linkInbox: ConnectLinkInbox,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ConnectGraph>()
    private val _state = MutableStateFlow(
        ConnectUiState(
            address = route.address.orEmpty(),
            username = route.username.orEmpty(),
            biometricsAvailable = PhoneUnlock.available(context),
        ),
    )
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val _events = Channel<ConnectEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var browserJob: Job? = null

    /** Checking whether the sign-in page has signed in. */
    private var webJob: Job? = null

    /** Sign-in pages that saved no dashboard leave nothing in the browser; a new page waits for it. */
    private val sweep: Job = viewModelScope.launch { signIn.sweepBrowserSignIns() }

    /** The headers were changed here, so they're saved before the address is checked. */
    private var headersEdited = false

    private val _returnFromBrowser = Channel<Unit>(Channel.CONFLATED)

    /**
     * The browser brought the sign-in back: time to leave the Custom Tab. Apart from [events],
     * which wait for the screen to be in front, as here the Custom Tab is in front of it.
     */
    val returnFromBrowser = _returnFromBrowser.receiveAsFlow()

    init {
        if (route.fromLink) linkInbox.take()?.let(::showLink)
    }

    fun onAddressChange(value: String) = _state.update { it.copy(address = value, addressError = null) }

    fun onToggleCloudflare() = _state.update { it.copy(cloudflareShown = !it.cloudflareShown) }

    /** Shows the extra headers, with what's saved for this server when nothing was typed yet. */
    fun onToggleHeaders() {
        val opening = !_state.value.headersShown
        _state.update { it.copy(headersShown = opening) }
        if (!opening || headersEdited) return
        viewModelScope.launch {
            val origin = httpsOrigins(_state.value.address).firstOrNull() ?: return@launch
            val saved = proxyHeaders.rows(origin)
            if (!headersEdited) _state.update { it.copy(headers = saved) }
        }
    }

    fun onHeadersChange(rows: List<ProxyHeader>) {
        headersEdited = true
        _state.update { it.copy(headers = rows, headersNeedHttps = false) }
    }

    /**
     * Saves the headers typed here for the address's server before anything is sent to it, as the
     * address check itself must carry them. False when they can't be: an invalid row, or an
     * address that isn't HTTPS.
     */
    private suspend fun saveHeaders(address: String): Boolean {
        if (!headersEdited) return true
        val rows = ProxyHeaderRules.tidy(_state.value.headers)
        if (!rows.allValid()) {
            _state.update { it.copy(headersShown = true) }
            return false
        }
        val origins = httpsOrigins(address)
        if (rows.isNotEmpty() && origins.isEmpty()) {
            _state.update { it.copy(headersShown = true, headersNeedHttps = true) }
            return false
        }
        origins.forEach { proxyHeaders.save(it, rows) }
        return true
    }

    private fun httpsOrigins(address: String): List<String> =
        (ConnectionUrlPolicy.check(address) as? AddressCheck.Valid)?.candidates.orEmpty()
            .mapNotNull(ProxyHeaderRules::origin)
            .distinct()

    fun onCloudflareClientIdChange(value: String) = _state.update { it.copy(cloudflareClientId = value) }

    fun onCloudflareClientSecretChange(value: String) = _state.update { it.copy(cloudflareClientSecret = value) }

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, signInError = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, signInError = null) }

    fun onSavePasswordChange(value: Boolean) = _state.update { it.copy(savePassword = value) }

    fun onUnlockChange(value: Boolean) = _state.update { it.copy(unlockWithBiometrics = value) }

    fun onUnreadableScanShown() = _state.update { it.copy(unreadableScan = false) }

    fun dismissLink() = _state.update { it.copy(pendingLink = null, password = "") }

    /** A QR code was read. Returns true when it was usable and the scanner can close. */
    fun onScanned(text: String): Boolean = when (val code = ScannedCode.classify(text)) {
        is ScannedCode.Link -> {
            showLink(code.link)
            true
        }

        is ScannedCode.Address -> {
            _state.update { it.copy(address = code.input, pendingLink = null, addressError = null) }
            checkAddress()
            true
        }

        ScannedCode.Unreadable -> {
            _state.update { it.copy(unreadableScan = true) }
            false
        }
    }

    /** Checks the typed address (or the confirmed link) and moves on to signing in. */
    fun checkAddress() {
        val current = _state.value
        if (current.busy) return
        val link = current.pendingLink
        _state.update { it.copy(checkingAddress = true, addressError = null, signInError = null) }
        viewModelScope.launch {
            if (!saveHeaders(link?.address ?: current.address)) {
                _state.update { it.copy(checkingAddress = false) }
                return@launch
            }
            val inspection = signIn.inspectAddress(link?.address ?: current.address, cloudflareToken())
            _state.update { it.copy(checkingAddress = false) }
            when (inspection) {
                is AddressInspection.Failed -> _state.update { it.copy(addressError = inspection.failure) }
                is AddressInspection.Ready -> onDashboardAnswered(inspection, link)
            }
        }
    }

    /**
     * Signs in through the browser (Hermes' native flow). It runs in the view model, so turning
     * the phone while the Custom Tab is open does not lose it; if Android ends the app meanwhile,
     * the sign-in is lost and is started again.
     */
    fun signInWithBrowser() {
        val current = _state.value
        val target = current.target ?: return
        if (current.busy || !target.browserAvailable) return
        _state.update { it.copy(signingInWithBrowser = true, signInError = null) }
        browserJob = viewModelScope.launch {
            val result = signIn.signInWithBrowser(
                url = target.url,
                providerName = target.browserProvider,
                cloudflareToken = cloudflareToken(),
                openBrowser = { url -> _events.send(ConnectEvent.OpenBrowser(url)) },
            )
            _returnFromBrowser.trySend(Unit)
            when (result) {
                is SignInResult.SignedIn -> {
                    _state.update { it.copy(signingInWithBrowser = false) }
                    _events.send(ConnectEvent.SignedIn)
                }

                is SignInResult.Failed -> _state.update {
                    it.copy(signingInWithBrowser = false, signInError = result.failure)
                }
            }
        }
    }

    /** Opens the dashboard's own sign-in page in the app, for a sign-in service in front of Hermes. */
    fun openWebSignIn() {
        val target = _state.value.target ?: return
        if (target.method != SignInMethod.Browser || _state.value.busy) return
        viewModelScope.launch {
            sweep.join()
            val id = signIn.browserTarget(target.url)
            val headers = buildMap {
                cloudflareToken()?.let { token ->
                    put("CF-Access-Client-Id", token.clientId.trim())
                    put("CF-Access-Client-Secret", token.clientSecret)
                }
                ProxyHeaderRules.origin(target.url)?.let { origin ->
                    proxyHeaders.forOrigin(origin).forEach { put(it.name, it.value) }
                }
            }
            _state.update {
                it.copy(
                    webSignIn = WebSignIn(id, target.url, browserStores.profileName(id), headers),
                    webError = null,
                    signInError = null,
                )
            }
            _events.send(ConnectEvent.ShowWebSignIn)
        }
    }

    /**
     * A dashboard page past its sign-in has loaded: if the page's session opens the live
     * connection, the dashboard is saved. Until then (Hermes' own sign-in, say) it keeps waiting.
     */
    fun onWebPage(url: String) {
        val web = _state.value.webSignIn ?: return
        if (_state.value.completingWeb || !WebSignInPolicy.isSignedInDashboardPage(url, web.url)) return
        _state.update { it.copy(completingWeb = true, webError = null) }
        webJob = viewModelScope.launch {
            when (val result = signIn.completeBrowserSignIn(web.id, web.url, cloudflareToken())) {
                is SignInResult.SignedIn -> {
                    // No saved password, so nothing for the phone's unlock to guard.
                    dashboards.setUnlockRequired(result.dashboard.id, false)
                    _state.update { it.copy(completingWeb = false, webSignIn = null) }
                    _events.send(ConnectEvent.SignedIn)
                }

                is SignInResult.Failed -> _state.update {
                    it.copy(
                        completingWeb = false,
                        webError = result.failure.takeUnless { failure -> failure == ConnectionFailure.SignInRequired },
                    )
                }
            }
        }
    }

    /** The user gave up on the sign-in page: what it left for a dashboard it didn't save goes. */
    fun cancelWebSignIn() {
        val web = _state.value.webSignIn
        val completing = webJob
        webJob = null
        _state.update { it.copy(webSignIn = null, completingWeb = false, webError = null) }
        if (web == null) return
        viewModelScope.launch {
            // A check under way could still save the dashboard; it stops before anything is decided.
            completing?.cancelAndJoin()
            signIn.abandonBrowserSignIn(web.id, web.url)
        }
    }

    /** Stops waiting for the browser (the user gave up on it). */
    fun cancelBrowserSignIn() {
        browserJob?.cancel()
        browserJob = null
        _state.update { it.copy(signingInWithBrowser = false) }
    }

    fun signIn() {
        val current = _state.value
        val target = current.target ?: return
        val method = target.method as? SignInMethod.Password ?: return
        if (current.busy || current.username.isBlank() || current.password.isEmpty()) return
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            val result = signIn.signInWithPassword(
                url = target.url,
                method = method,
                username = current.username,
                password = current.password,
                savePassword = current.savePassword,
                cloudflareToken = cloudflareToken(),
            )
            when (result) {
                is SignInResult.SignedIn -> {
                    val id = result.dashboard.id
                    val locks = current.savePassword && current.unlockWithBiometrics && current.biometricsAvailable
                    dashboards.setUnlockRequired(id, locks)
                    // Signed in just now: this run needs no unlock.
                    unlock.unlocked(id)
                    _state.update { it.copy(signingIn = false, password = "") }
                    _events.send(ConnectEvent.SignedIn)
                }

                is SignInResult.Failed -> _state.update { it.copy(signingIn = false, signInError = result.failure) }
            }
        }
    }

    private suspend fun onDashboardAnswered(inspection: AddressInspection.Ready, link: ConnectLink?) {
        val method = inspection.method
        _state.update {
            it.copy(
                target = ConnectTarget(inspection.url, method),
                address = inspection.url.toString(),
                username = link?.username ?: it.username.ifEmpty { inspection.existing?.username.orEmpty() },
                password = link?.password ?: it.password,
                pendingLink = null,
            )
        }
        _events.send(ConnectEvent.ShowSignIn)
        // A link with both username and password signs in straight away; a rejected password
        // leaves the sign-in form showing the error.
        if (link?.username != null && link.password != null) signIn()
    }

    private fun showLink(link: ConnectLink) = _state.update {
        it.copy(pendingLink = link, address = link.address, addressError = null, target = null)
    }

    /**
     * The setup wizard's address and sign-in ("Use these settings"): the address is checked as if
     * typed, then the sign-in screen opens with them filled in, as iOS fills its login form.
     */
    fun useSetup(address: String, username: String, password: String) {
        _state.update {
            it.copy(
                address = address,
                username = username,
                password = password,
                pendingLink = null,
                addressError = null,
                signInError = null,
            )
        }
        checkAddress()
    }

    /** The Cloudflare token typed on the address step, which the wizard's test sends too. */
    internal fun cloudflareToken(): CloudflareAccessToken? = _state.value.let {
        CloudflareAccessToken(
            it.cloudflareClientId,
            it.cloudflareClientSecret,
        ).takeIf(CloudflareAccessToken::isConfigured)
    }
}
