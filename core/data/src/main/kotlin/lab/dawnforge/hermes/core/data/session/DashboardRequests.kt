package lab.dawnforge.hermes.core.data.session

import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.signin.SessionState
import lab.dawnforge.hermes.core.data.signin.SignInService
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.DashboardApi
import lab.dawnforge.hermes.core.net.NetResult
import okhttp3.CookieJar

/**
 * Runs REST calls against a dashboard with its shared session (cookies, or a browser sign-in's
 * bearer token). A call refused with "sign in required" is retried once after the session is
 * renewed (with the saved password, or the refresh token); Hermes
 * refuses such a request before acting on it, so the retry is safe for any method.
 */
class DashboardRequests(
    private val api: DashboardApi,
    private val repository: DashboardRepository,
    private val cookies: SessionCookies,
    private val signIn: SignInService,
) {
    suspend fun <T> run(
        id: DashboardId,
        call: suspend DashboardApi.(
            url: DashboardUrl,
            jar: CookieJar,
            cloudflare: CloudflareAccessToken?,
        ) -> NetResult<T>,
    ): NetResult<T> {
        val dashboard = repository.dashboard(id) ?: return NetResult.Failure(ConnectionFailure.SignInRequired)
        val cloudflare = repository.cloudflareToken(id)
        val jar = cookies.jar(id)
        val sent = jar.mark()
        val first = api.call(dashboard.url, jar, cloudflare)
        if (first !is NetResult.Failure || first.failure != ConnectionFailure.SignInRequired) return first

        return when (val session = signIn.renewAfterRejection(id, sent)) {
            is SessionState.Ready -> api.call(dashboard.url, cookies.jar(id), cloudflare)
            is SessionState.NeedsSignIn -> NetResult.Failure(ConnectionFailure.SignInRequired)
            is SessionState.Unreachable -> NetResult.Failure(session.failure)
            null -> first
        }
    }
}
