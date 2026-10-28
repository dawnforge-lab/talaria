package lab.dawnforge.hermes.feature.connect.setup

import lab.dawnforge.hermes.core.net.ConnectionFailure

/**
 * Where "Troubleshoot connection" opens the wizard for a failure, as iOS routes it: the network
 * to how the phone reaches Hermes, certificates and Cloudflare to their checklists, a rejected
 * password to the credentials question, and an address or dashboard problem to the start. Null when the
 * wizard can't help (too many attempts, a sign-in that expired).
 */
fun ConnectionFailure.troubleshootStart(): SetupStart? = when (this) {
    is ConnectionFailure.InvalidAddress -> SetupStart.Dashboard

    ConnectionFailure.HostNotFound,
    ConnectionFailure.ConnectionRefused,
    ConnectionFailure.TimedOut,
    is ConnectionFailure.NetworkError,
    -> SetupStart.AccessMethod

    is ConnectionFailure.TlsFailure -> SetupStart.Tls

    ConnectionFailure.AuthenticationRejected -> SetupStart.Credentials

    ConnectionFailure.CloudflareTokenRejected -> SetupStart.Cloudflare

    is ConnectionFailure.SessionTicketFailure,
    is ConnectionFailure.DashboardUnavailable,
    is ConnectionFailure.NotADashboard,
    is ConnectionFailure.UnexpectedResponse,
    ConnectionFailure.NoSignInMethod,
    ConnectionFailure.SignInDisabled,
    -> SetupStart.Dashboard

    else -> null
}
