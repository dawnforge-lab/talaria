package lab.dawnforge.hermes.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.data.bots.BotChat
import lab.dawnforge.hermes.core.data.bots.BotChatOutcome
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotWrite
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.groups.GroupsRepository
import lab.dawnforge.hermes.core.data.groups.GroupsState
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Something to tell about the last action on the roster. */
sealed interface BotsMessage {
    data class ChatUnchecked(val label: String) : BotsMessage

    data class ChatUnconfirmed(val label: String) : BotsMessage

    data class Failed(val failure: ConnectionFailure) : BotsMessage

    data class NotUpdated(val label: String) : BotsMessage

    data class Deleted(val label: String) : BotsMessage
}

data class BotsUiState(
    /** The bot whose chat is being opened. */
    val opening: String? = null,
    val message: BotsMessage? = null,
    /** The bot waiting for the user to confirm its deletion. */
    val deleting: BotProfile? = null,
    /** The profile the app shows, which can't be deleted. */
    val activeProfile: String? = null,
)

/** The Bots screen: the roster, opening a bot's chat, pinning and deleting. */
@HiltViewModel
class BotsViewModel @Inject constructor(
    private val bots: BotsRepository,
    private val groups: GroupsRepository,
    private val dashboards: DashboardRepository,
) : ViewModel() {
    val roster: StateFlow<BotsState> = bots.state
    val avatars: StateFlow<Map<String, ByteArray>> = bots.avatars

    /** The hosted group chats, beside the bots as on iOS. */
    val groupChats: StateFlow<GroupsState> = groups.state
    val roomPictures: StateFlow<Map<String, ByteArray>> = groups.pictures

    private val _state = MutableStateFlow(BotsUiState())
    val state: StateFlow<BotsUiState> = _state.asStateFlow()

    private var dashboard: DashboardId? = null

    init {
        viewModelScope.launch {
            dashboard = dashboards.activeDashboardId.first()
            dashboard?.let { id -> _state.update { it.copy(activeProfile = dashboards.activeProfile(id).first()) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val next = bots.refresh()
            (next as? BotsState.Available)?.roster?.visible?.forEach { launch { bots.loadAvatar(it, again = true) } }
        }
        viewModelScope.launch {
            if (groups.refresh() !is GroupsState.Available) return@launch
            dashboards.activeDashboardId.first()?.let { groups.refreshPictures(it) }
        }
    }

    /** Opens [bot]'s one chat, making it when it certainly has none. */
    fun open(bot: BotProfile, onOpen: (BotChat, String) -> Unit) {
        if (_state.value.opening != null) return
        _state.update { it.copy(opening = bot.name, message = null) }
        viewModelScope.launch {
            val outcome = bots.openChat(bot)
            _state.update { it.copy(opening = null) }
            when (outcome) {
                is BotChatOutcome.Open -> onOpen(outcome.chat, bot.label)

                BotChatOutcome.Unchecked, BotChatOutcome.Missing ->
                    _state.update { it.copy(message = BotsMessage.ChatUnchecked(bot.label)) }

                BotChatOutcome.Unconfirmed -> _state.update {
                    it.copy(message = BotsMessage.ChatUnconfirmed(bot.label))
                }

                is BotChatOutcome.Failed -> _state.update { it.copy(message = BotsMessage.Failed(outcome.failure)) }
            }
        }
    }

    /** A bot just made: its chat opens once the roster has it. */
    fun openCreated(name: String, onOpen: (BotChat, String) -> Unit) {
        viewModelScope.launch {
            val bot = (bots.refresh() as? BotsState.Available)?.roster?.find(name) ?: return@launch
            open(bot, onOpen)
        }
    }

    fun setPinned(bot: BotProfile, pinned: Boolean) {
        viewModelScope.launch {
            if (bots.saveLook(bot, mapOf("pinned" to JsonPrimitive(pinned))) != BotWrite.Saved) {
                _state.update { it.copy(message = BotsMessage.NotUpdated(bot.label)) }
            }
            bots.refresh()
        }
    }

    fun askDelete(bot: BotProfile?) = _state.update { it.copy(deleting = bot) }

    fun delete(bot: BotProfile) {
        val id = dashboard ?: return
        _state.update { it.copy(deleting = null) }
        viewModelScope.launch {
            when (val result = bots.delete(id, bot.name)) {
                is NetResult.Success -> _state.update { it.copy(message = BotsMessage.Deleted(bot.label)) }
                is NetResult.Failure -> _state.update { it.copy(message = BotsMessage.Failed(result.failure)) }
            }
            bots.refresh()
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Whether [bot] can be deleted: not the primary profile, nor the one the app shows. */
    fun canDelete(bot: BotProfile): Boolean = bot.isDeletable && bot.name != _state.value.activeProfile
}
