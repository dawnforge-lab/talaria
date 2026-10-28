package lab.dawnforge.hermes.feature.connect.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.signin.ConnectionTest
import lab.dawnforge.hermes.core.data.signin.ConnectionTestState
import lab.dawnforge.hermes.core.data.signin.TestOutcome
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.feature.connect.navigation.SetupRoute

/** The wizard's screens. Back goes to the one before, as iOS's navigation path does. */
enum class WizardStep {
    Dashboard,
    Credentials,
    AccessMethod,
    Lan,
    Tailscale,
    ReverseProxy,
    Details,
    LoginCredentials,
    Test,
    Review,
    Tls,
    Cloudflare,
}

enum class SetupAnswer { Yes, No, Unknown }

/** Where the wizard opens: the start, or the step nearest a failure ("Troubleshoot connection"). */
enum class SetupStart { Dashboard, AccessMethod, Credentials, Tls, Cloudflare }

/** Which wizard this is: first setup, a repair of a failed connection, or editing a working one. */
enum class WizardMode { Setup, Repair, Edit }

data class SetupUiState(
    val path: List<WizardStep>,
    val draft: SetupDraft,
    val dashboardAnswer: SetupAnswer? = null,
    val credentialsAnswer: SetupAnswer? = null,
    val notSureShown: Boolean = false,
    /** Why Continue on the details step didn't go on. */
    val detailsProblem: SetupAddressProblem? = null,
    val credentialsMissing: Boolean = false,
    /** The test of the current draft; null before it runs, and again after any change. */
    val test: ConnectionTestState? = null,
    val testing: Boolean = false,
    /** The address the test ran against. */
    val testedAddress: String? = null,
    /** A repair is reconnecting with what it tested. */
    val reconnecting: Boolean = false,
    /** Why the last reconnect didn't work. */
    val reconnectFailure: ReconnectFailure? = null,
) {
    val step: WizardStep get() = path.last()

    val outcome: TestOutcome? get() = test?.outcome
}

sealed interface SetupEvent {
    data object Close : SetupEvent

    /** "Use these settings": the address and sign-in go to the sign-in screen, as iOS fills its login form. */
    data class Use(val address: String, val username: String, val password: String) : SetupEvent

    /** A repair's "Sign in to reconnect": the sign-in screen for the address, which saves the session. */
    data class SignIn(val address: String, val username: String?) : SetupEvent

    /** A repair reconnected. */
    data object Reconnected : SetupEvent
}

/**
 * The connection-setup wizard, as iOS's: a few questions, how the phone reaches Hermes, the
 * details that make the address, the dashboard sign-in, then a staged test and a review. The test
 * saves nothing; what the review does depends on the [mode].
 */
abstract class WizardViewModel(private val connectionTest: ConnectionTest, initial: SetupUiState) : ViewModel() {
    abstract val mode: WizardMode

    protected val mutableState = MutableStateFlow(initial)
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    protected val eventChannel = Channel<SetupEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var testJob: Job? = null

    /** The Cloudflare Access token the test sends to [address]. */
    protected abstract suspend fun cloudflareTokenFor(address: String): CloudflareAccessToken?

    protected fun push(step: WizardStep) = mutableState.update { it.copy(path = it.path + step) }

    fun back() {
        val path = mutableState.value.path
        if (path.size <= 1) {
            close()
            return
        }
        if (path.last() == WizardStep.Test) cancelTest()
        mutableState.update { it.copy(path = it.path.dropLast(1)) }
    }

    open fun close() {
        cancelTest()
        eventChannel.trySend(SetupEvent.Close)
    }

    fun answerDashboard(answer: SetupAnswer) {
        mutableState.update { it.copy(dashboardAnswer = answer) }
        if (answer == SetupAnswer.Yes) push(WizardStep.AccessMethod)
    }

    fun dashboardReady() = push(WizardStep.AccessMethod)

    fun answerCredentials(answer: SetupAnswer) {
        mutableState.update { it.copy(credentialsAnswer = answer) }
        if (answer == SetupAnswer.Yes) credentialsReady()
    }

    /** Asked after a rejected password, so the address is already known: on to the sign-in. */
    fun credentialsReady() {
        if (mutableState.value.draft.existingAddress.isBlank()) {
            push(WizardStep.AccessMethod)
            return
        }
        editDraft { it.copy(useExisting = true) }
        push(WizardStep.LoginCredentials)
    }

    fun useExistingAddress() {
        editDraft { it.copy(useExisting = true) }
        push(WizardStep.Details)
    }

    fun chooseMethod(method: AccessMethod) {
        editDraft { it.copy(useExisting = false, method = method) }
        push(
            when (method) {
                AccessMethod.Lan -> WizardStep.Lan
                AccessMethod.Tailscale -> WizardStep.Tailscale
                AccessMethod.ReverseProxy -> WizardStep.ReverseProxy
            },
        )
    }

    fun toggleNotSure() = mutableState.update { it.copy(notSureShown = !it.notSureShown) }

    fun detailsReady() = push(WizardStep.Details)

    /** From a checklist: on to setting the connection up, or back to the details in a repair. */
    fun setUpConnection() {
        if (mode == WizardMode.Setup) push(WizardStep.AccessMethod) else goTo(WizardStep.Details)
    }

    /** Any change makes an earlier test stale, as on iOS. */
    fun editDraft(change: (SetupDraft) -> SetupDraft) {
        cancelTest()
        mutableState.update {
            it.copy(
                draft = change(it.draft),
                detailsProblem = null,
                credentialsMissing = false,
                test = null,
                reconnectFailure = null,
            )
        }
    }

    /** Continue on the details step: on to the test, or to the sign-in when one is typed already. */
    fun submitDetails() {
        when (val built = SetupAddressBuilder.build(mutableState.value.draft)) {
            is SetupAddress.Problem -> mutableState.update { it.copy(detailsProblem = built.problem) }

            // The test finds out first whether the dashboard signs in with a password at all.
            is SetupAddress.Built -> push(
                if (mutableState.value.draft.hasCredentials) WizardStep.LoginCredentials else WizardStep.Test,
            )
        }
    }

    /** A first setup needs both; a repair may test without them, as on iOS. */
    fun submitCredentials() {
        val draft = mutableState.value.draft
        val partial = draft.username.isNotBlank() != draft.password.isNotEmpty()
        if ((mode == WizardMode.Setup && !draft.hasCredentials) || partial) {
            mutableState.update { it.copy(credentialsMissing = true) }
            return
        }
        push(WizardStep.Test)
    }

    fun runTest() {
        val draft = mutableState.value.draft
        val address = (SetupAddressBuilder.build(draft) as? SetupAddress.Built)?.address ?: return
        cancelTest()
        mutableState.update {
            it.copy(test = ConnectionTestState(), testing = true, testedAddress = address, reconnectFailure = null)
        }
        testJob = viewModelScope.launch {
            try {
                val token = cloudflareTokenFor(address)
                connectionTest.run(address, token, draft.username, draft.password).collect { progress ->
                    mutableState.update { it.copy(test = progress) }
                }
            } finally {
                mutableState.update { it.copy(testing = false) }
            }
            when (mutableState.value.outcome) {
                is TestOutcome.Ready, is TestOutcome.BrowserSignIn, is TestOutcome.SignInChoice ->
                    if (mutableState.value.step == WizardStep.Test) push(WizardStep.Review)

                else -> Unit
            }
        }
    }

    protected fun cancelTest() {
        testJob?.cancel()
        testJob = null
    }

    /** Back to a step already on the path, or on to it. */
    fun goTo(step: WizardStep) {
        cancelTest()
        mutableState.update { state ->
            val index = state.path.lastIndexOf(step)
            state.copy(path = if (index >= 0) state.path.take(index + 1) else state.path + step)
        }
    }

    /** First setup: the address and sign-in go to the sign-in screen. */
    fun useSettings() {
        val current = mutableState.value
        val address = current.testedAddress ?: return
        eventChannel.trySend(SetupEvent.Use(address, current.draft.username.trim(), current.draft.password))
    }

    companion object {
        internal fun firstStep(start: SetupStart) = when (start) {
            SetupStart.Dashboard -> WizardStep.Dashboard
            SetupStart.AccessMethod -> WizardStep.AccessMethod
            SetupStart.Credentials -> WizardStep.Credentials
            SetupStart.Tls -> WizardStep.Tls
            SetupStart.Cloudflare -> WizardStep.Cloudflare
        }
    }
}

/** The wizard for adding a dashboard; nothing is saved here, the sign-in screen does that. */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(savedStateHandle: SavedStateHandle, connectionTest: ConnectionTest) :
    WizardViewModel(
        connectionTest,
        savedStateHandle.toRoute<SetupRoute>().let { route ->
            SetupUiState(
                path = listOf(firstStep(route.start)),
                draft = SetupDraft(existingAddress = route.address.orEmpty()),
            )
        },
    ) {
    override val mode = WizardMode.Setup

    /** The token typed on the address step; set by the screen that hosts the wizard. */
    var typedCloudflareToken: () -> CloudflareAccessToken? = { null }

    override suspend fun cloudflareTokenFor(address: String): CloudflareAccessToken? = typedCloudflareToken()
}
