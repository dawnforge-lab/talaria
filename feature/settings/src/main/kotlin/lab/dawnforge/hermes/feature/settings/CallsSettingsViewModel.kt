package lab.dawnforge.hermes.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.plugin.CallSettings
import lab.dawnforge.hermes.core.data.plugin.CallsStatus
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository
import lab.dawnforge.hermes.core.data.session.SessionRepository
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Calls from Hermes for the active profile, as its host has them. */
data class CallsUiState(
    val profile: String? = null,
    val loading: Boolean = true,
    val status: CallsStatus? = null,
    val failure: ConnectionFailure? = null,
    val saving: Boolean = false,
)

/** Settings › Calls from Hermes: when Hermes may phone the user, kept on the host; and how it rings here. */
@HiltViewModel
class CallsSettingsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val calls: HermesCallsRepository,
    private val settings: AppSettings,
    private val sessions: SessionRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private var place: Pair<DashboardId, String>? = null
    private val _state = MutableStateFlow(CallsUiState())
    val state: StateFlow<CallsUiState> = _state.asStateFlow()

    /** Whether a call rings here like a phone call (off: a notification to talk comes instead). */
    val ringsLikeCall: StateFlow<Boolean> = settings.ringsLikeCall.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _fullScreen = MutableStateFlow(canUseFullScreen())

    /** Whether a ringing call may show its call screen over the lock screen (Android 14+ asks the user). */
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = dashboards.activeProfile(id).first()
            place = id to profile
            _state.update { it.copy(profile = profile) }
            load()
        }
    }

    fun load() {
        val (id, profile) = place ?: return
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val result = calls.status(id, profile)) {
                is NetResult.Success -> _state.update { it.copy(loading = false, status = result.value) }
                is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
            }
        }
    }

    /** Saves a change to the host's settings; the page shows what the host kept. */
    fun update(change: (CallSettings) -> CallSettings) {
        val (id, profile) = place ?: return
        val status = _state.value.status ?: return
        val next = change(status.settings)
        _state.update { it.copy(status = status.copy(settings = next), saving = true, failure = null) }
        viewModelScope.launch {
            when (val result = calls.save(id, profile, next)) {
                is NetResult.Success -> _state.update { state ->
                    state.copy(saving = false, status = state.status?.copy(settings = result.value))
                }

                is NetResult.Failure -> _state.update {
                    it.copy(saving = false, status = status, failure = result.failure)
                }
            }
        }
    }

    /**
     * Starts a new chat on the live connection, as the chat list does, then [open]s it with [text]
     * ready to send: its first message goes once the chat is known to Hermes.
     */
    fun newChat(text: String, open: (profile: String, runtimeId: String, text: String) -> Unit) {
        val (_, profile) = place ?: return
        viewModelScope.launch {
            when (val chat = sessions.startChat(profile)) {
                is NetResult.Success -> open(chat.value.profile, chat.value.runtimeId, text)
                is NetResult.Failure -> _state.update { it.copy(failure = chat.failure) }
            }
        }
    }

    fun setRingsLikeCall(rings: Boolean) {
        viewModelScope.launch { settings.setRingsLikeCall(rings) }
    }

    /** Back from Android's settings: the call screen may have been allowed. */
    fun recheck() {
        _fullScreen.value = canUseFullScreen()
    }

    private fun canUseFullScreen(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
