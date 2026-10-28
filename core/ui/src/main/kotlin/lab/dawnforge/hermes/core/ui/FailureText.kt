package lab.dawnforge.hermes.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import lab.dawnforge.hermes.core.net.AddressProblem
import lab.dawnforge.hermes.core.net.ConnectionFailure

/** A sentence that tells the user what went wrong and, where possible, what to do. */
@Composable
fun failureText(failure: ConnectionFailure): String = when (failure) {
    is ConnectionFailure.InvalidAddress -> addressProblemText(failure.problem)

    ConnectionFailure.HostNotFound -> stringResource(R.string.core_ui_error_host_not_found)

    ConnectionFailure.ConnectionRefused -> stringResource(R.string.core_ui_error_refused)

    ConnectionFailure.TimedOut -> stringResource(R.string.core_ui_error_timeout)

    is ConnectionFailure.TlsFailure -> stringResource(R.string.core_ui_error_tls)

    is ConnectionFailure.NetworkError -> withDetail(
        failure.detail,
        R.string.core_ui_error_network,
        R.string.core_ui_error_network_unknown,
    )

    is ConnectionFailure.NotADashboard -> stringResource(R.string.core_ui_error_not_dashboard)

    is ConnectionFailure.DashboardUnavailable ->
        stringResource(R.string.core_ui_error_unavailable, failure.status)

    ConnectionFailure.InteractiveSignInRequired -> stringResource(R.string.core_ui_error_interactive)

    ConnectionFailure.CloudflareTokenRejected -> stringResource(R.string.core_ui_error_cloudflare)

    ConnectionFailure.NoSignInMethod -> stringResource(R.string.core_ui_error_no_method)

    ConnectionFailure.SignInDisabled -> stringResource(R.string.core_ui_error_sign_in_disabled)

    is ConnectionFailure.SignInMethodNotSupported -> stringResource(R.string.core_ui_error_oauth)

    ConnectionFailure.BrowserSignInUnfinished -> stringResource(R.string.core_ui_error_browser_unfinished)

    is ConnectionFailure.BrowserSignInRefused ->
        stringResource(R.string.core_ui_error_browser_refused, failure.error)

    ConnectionFailure.AuthenticationRejected -> stringResource(R.string.core_ui_error_rejected)

    is ConnectionFailure.RateLimited -> rateLimitText(failure.retryAfterSeconds)

    ConnectionFailure.SignInRequired -> stringResource(R.string.core_ui_error_sign_in_required)

    is ConnectionFailure.Refused -> stringResource(R.string.core_ui_error_refused_detail, failure.detail)

    is ConnectionFailure.SessionTicketFailure -> withDetail(
        failure.detail,
        R.string.core_ui_error_ticket_detail,
        R.string.core_ui_error_ticket,
    )

    is ConnectionFailure.NotFound -> withDetail(
        failure.detail,
        R.string.core_ui_error_not_found_detail,
        R.string.core_ui_error_not_found,
    )

    is ConnectionFailure.Invalid -> withDetail(
        failure.detail,
        R.string.core_ui_error_invalid_detail,
        R.string.core_ui_error_invalid,
    )

    is ConnectionFailure.UnexpectedResponse -> when (val status = failure.status) {
        null -> stringResource(R.string.core_ui_error_unexpected_unknown)
        else -> stringResource(R.string.core_ui_error_unexpected, status)
    }

    is ConnectionFailure.OwnedElsewhere -> stringResource(R.string.core_ui_error_owned_elsewhere)

    is ConnectionFailure.TooLarge -> when (val limit = failure.limitBytes) {
        null -> stringResource(R.string.core_ui_error_too_large)

        // In the app's megabytes (1024 × 1024 bytes), as its limits are set and its other
        // messages count them: Android's formatter would read 512 of them as "537 MB".
        else -> stringResource(R.string.core_ui_error_too_large_limit, limit / MEGABYTE)
    }
}

private const val MEGABYTE = 1024L * 1024

@Composable
private fun addressProblemText(problem: AddressProblem): String = stringResource(
    when (problem) {
        AddressProblem.Empty -> R.string.core_ui_error_empty
        AddressProblem.UnsupportedScheme -> R.string.core_ui_error_scheme
        AddressProblem.Malformed -> R.string.core_ui_error_malformed
        AddressProblem.HasCredentials -> R.string.core_ui_error_credentials_in_address
        AddressProblem.HasQueryOrFragment -> R.string.core_ui_error_query
        AddressProblem.InsecureTransport -> R.string.core_ui_error_insecure
    },
)

@Composable
private fun rateLimitText(retryAfterSeconds: Long?): String = if (retryAfterSeconds != null) {
    val seconds = retryAfterSeconds.toInt()
    pluralStringResource(R.plurals.core_ui_error_rate_limited, seconds, seconds)
} else {
    stringResource(R.string.core_ui_error_rate_limited_unknown)
}

/** [withDetail] when the failure carries a detail, [without] otherwise. */
@Composable
private fun withDetail(detail: String?, withDetail: Int, without: Int): String =
    if (detail != null) stringResource(withDetail, detail) else stringResource(without)
