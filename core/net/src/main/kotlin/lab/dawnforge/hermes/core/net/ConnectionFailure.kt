package lab.dawnforge.hermes.core.net

import lab.dawnforge.hermes.core.model.SignInMethod

/** Why talking to a dashboard failed, classified so the UI can explain it. */
sealed interface ConnectionFailure {
    data class InvalidAddress(val problem: AddressProblem) : ConnectionFailure

    data object HostNotFound : ConnectionFailure

    data object ConnectionRefused : ConnectionFailure

    data object TimedOut : ConnectionFailure

    /** The TLS handshake failed: a bad or self-signed certificate. Never bypassed. */
    data class TlsFailure(val detail: String?) : ConnectionFailure

    data class NetworkError(val detail: String?) : ConnectionFailure

    /** The address answered, but not like a Hermes dashboard. */
    data class NotADashboard(val status: Int?) : ConnectionFailure

    /** 502, 503, 504 and the like: Hermes or a proxy in front of it is down or starting. */
    data class DashboardUnavailable(val status: Int) : ConnectionFailure

    /** An authentication proxy or browser-only sign-in stands in front of the dashboard. */
    data object InteractiveSignInRequired : ConnectionFailure

    /** Cloudflare Access sent the request to its sign-in page despite the service token. */
    data object CloudflareTokenRejected : ConnectionFailure

    /** The dashboard advertises no sign-in method this app understands. */
    data object NoSignInMethod : ConnectionFailure

    /**
     * The dashboard runs with sign-in off (Hermes' loopback mode: bound to 127.0.0.1 with no
     * `dashboard.public_url`), so it accepts only the machine it runs on, never a phone.
     */
    data object SignInDisabled : ConnectionFailure

    /**
     * A sign-in the app cannot do: one that only works on the dashboard's own page in a browser (an
     * authentication proxy in front of Hermes, or an older Hermes without the native flow).
     */
    data class SignInMethodNotSupported(val method: SignInMethod) : ConnectionFailure

    /** The sign-in in the browser was not finished: closed, left, or it failed on Hermes' page. */
    data object BrowserSignInUnfinished : ConnectionFailure

    /** The identity provider refused the sign-in (the browser came back with `error=`). */
    data class BrowserSignInRefused(val error: String) : ConnectionFailure

    data object AuthenticationRejected : ConnectionFailure

    /** Hermes allows 10 password attempts per minute per address. */
    data class RateLimited(val retryAfterSeconds: Long?) : ConnectionFailure

    /** The saved session has expired; sign in again. */
    data object SignInRequired : ConnectionFailure

    /** Hermes refused the request for a stated reason (a 403 with a `detail`). */
    data class Refused(val detail: String) : ConnectionFailure

    /** The password was accepted but no WebSocket ticket could be minted. */
    data class SessionTicketFailure(val detail: String?) : ConnectionFailure

    /** 404: the session (or profile) the request named does not exist, or no longer does. */
    data class NotFound(val detail: String?) : ConnectionFailure

    /** 400/422: Hermes rejected the request itself, e.g. "Title too long (120 chars, max 100)". */
    data class Invalid(val detail: String?) : ConnectionFailure

    data class UnexpectedResponse(val status: Int?, val detail: String?) : ConnectionFailure

    /** Hermes Desktop or a terminal has the chat open, so Hermes won't take a message for it here. */
    data class OwnedElsewhere(val detail: String) : ConnectionFailure

    /** The file is larger than Hermes sends or the phone takes ([limitBytes], when known). */
    data class TooLarge(val limitBytes: Long?) : ConnectionFailure
}

sealed interface NetResult<out T> {
    data class Success<out T>(val value: T) : NetResult<T>

    data class Failure(val failure: ConnectionFailure) : NetResult<Nothing>
}

/** Thrown inside `core:net` to unwind a request; converted to [NetResult.Failure] at the API edge. */
internal class ConnectionFailureException(val failure: ConnectionFailure) : Exception(failure.toString())

internal inline fun <T> netResult(block: () -> T): NetResult<T> = try {
    NetResult.Success(block())
} catch (e: ConnectionFailureException) {
    NetResult.Failure(e.failure)
}

internal fun fail(failure: ConnectionFailure): Nothing = throw ConnectionFailureException(failure)
