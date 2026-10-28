package lab.dawnforge.hermes.core.data.signin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** The connection test's checks, in the order they run. */
enum class TestStage { Server, Dashboard, Authentication }

sealed interface StageState {
    data object Pending : StageState

    data object Running : StageState

    data object Passed : StageState

    /** Done without signing in, for the reason the outcome gives (browser sign-in, no password typed). */
    data object Partial : StageState

    data class Failed(val failure: ConnectionFailure) : StageState
}

sealed interface TestOutcome {
    /**
     * Signed in and opened a live-connection ticket. Nothing was saved; the [session] stays in
     * memory for a repair's Reconnect now.
     */
    data class Ready(val url: DashboardUrl, val session: VerifiedSession) : TestOutcome

    /** The dashboard signs in through the browser, so no sign-in was tried. */
    data class BrowserSignIn(val url: DashboardUrl) : TestOutcome

    /** A password dashboard, tested with no password typed: an empty sign-in is never sent. */
    data class CredentialsRequired(val url: DashboardUrl) : TestOutcome

    /** The dashboard offers a password and the browser, and no password was typed. */
    data class SignInChoice(val url: DashboardUrl) : TestOutcome

    data class Failed(val stage: TestStage, val failure: ConnectionFailure) : TestOutcome
}

data class ConnectionTestState(
    val stages: Map<TestStage, StageState> = TestStage.entries.associateWith { StageState.Pending },
    val outcome: TestOutcome? = null,
) {
    fun stage(stage: TestStage): StageState = stages.getValue(stage)

    internal fun with(vararg changes: Pair<TestStage, StageState>, outcome: TestOutcome? = null) =
        ConnectionTestState(stages + changes, outcome)
}

/**
 * The setup wizard's connection test, as iOS's: is the server there, is it a Hermes dashboard,
 * and does the sign-in work. It sends the address's Cloudflare token and extra headers but saves
 * nothing: no cookies, password or dashboard.
 */
class ConnectionTest(private val signIn: SignInService) {
    fun run(
        address: String,
        cloudflareToken: CloudflareAccessToken?,
        username: String,
        password: String,
    ): Flow<ConnectionTestState> = flow {
        var state = ConnectionTestState().with(TestStage.Server to StageState.Running)
        emit(state)
        val inspection = signIn.inspectAddress(address, cloudflareToken)
        if (inspection is AddressInspection.Failed) {
            emit(failedAt(state, inspection.failure))
            return@flow
        }
        val ready = inspection as AddressInspection.Ready
        val url = ready.url
        state = state.with(TestStage.Server to StageState.Passed, TestStage.Dashboard to StageState.Passed)

        val method = ready.method
        if (method !is SignInMethod.Password) {
            emit(state.with(TestStage.Authentication to StageState.Partial, outcome = TestOutcome.BrowserSignIn(url)))
            return@flow
        }
        if (username.isBlank() || password.isEmpty()) {
            val outcome = if (method.browserAlsoAvailable) {
                TestOutcome.SignInChoice(url)
            } else {
                TestOutcome.CredentialsRequired(url)
            }
            emit(state.with(TestStage.Authentication to StageState.Partial, outcome = outcome))
            return@flow
        }
        state = state.with(TestStage.Authentication to StageState.Running)
        emit(state)
        emit(
            when (val result = signIn.testPassword(url, method, username, password, cloudflareToken)) {
                is NetResult.Success -> state.with(
                    TestStage.Authentication to StageState.Passed,
                    outcome = TestOutcome.Ready(url, result.value),
                )

                is NetResult.Failure -> state.with(
                    TestStage.Authentication to StageState.Failed(result.failure),
                    outcome = TestOutcome.Failed(TestStage.Authentication, result.failure),
                )
            },
        )
    }

    private fun failedAt(state: ConnectionTestState, failure: ConnectionFailure) = when {
        failure.isTransport() -> state.with(
            TestStage.Server to StageState.Failed(failure),
            outcome = TestOutcome.Failed(TestStage.Server, failure),
        )

        else -> state.with(
            TestStage.Server to StageState.Passed,
            TestStage.Dashboard to StageState.Failed(failure),
            outcome = TestOutcome.Failed(TestStage.Dashboard, failure),
        )
    }
}

/** The request never got an HTTP answer: the address, the network or TLS. */
fun ConnectionFailure.isTransport(): Boolean = when (this) {
    is ConnectionFailure.InvalidAddress,
    ConnectionFailure.HostNotFound,
    ConnectionFailure.ConnectionRefused,
    ConnectionFailure.TimedOut,
    is ConnectionFailure.TlsFailure,
    is ConnectionFailure.NetworkError,
    -> true

    else -> false
}
