package lab.dawnforge.hermes.feature.bots.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import lab.dawnforge.hermes.core.data.bots.BotProfile
import lab.dawnforge.hermes.core.data.bots.BotsRepository
import lab.dawnforge.hermes.core.data.bots.BotsState
import lab.dawnforge.hermes.core.data.groups.GroupCreate
import lab.dawnforge.hermes.core.data.groups.GroupRoom
import lab.dawnforge.hermes.core.data.groups.GroupsRepository
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** Why a room couldn't be made. */
sealed interface CreateProblem {
    /** Two picked bots would answer to one @handle. */
    data object HandleClash : CreateProblem

    data class Failed(val failure: ConnectionFailure) : CreateProblem
}

data class GroupCreateUiState(
    val bots: List<BotProfile> = emptyList(),
    val name: String = "",
    val selected: Set<String> = emptySet(),
    val creating: Boolean = false,
    val problem: CreateProblem? = null,
) {
    val canCreate: Boolean
        get() = !creating && name.isNotBlank() && selected.size in GroupCreate.MIN_MEMBERS..GroupCreate.MAX_MEMBERS
}

/** Making a group chat: a name and 2–6 bots, as iOS's create sheet. */
@HiltViewModel
class GroupCreateViewModel @Inject constructor(
    private val bots: BotsRepository,
    private val groups: GroupsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(
        GroupCreateUiState(bots = (bots.state.value as? BotsState.Available)?.roster?.visible.orEmpty()),
    )
    val state: StateFlow<GroupCreateUiState> = _state.asStateFlow()
    val avatars: StateFlow<Map<String, ByteArray>> = bots.avatars

    /** One room id per name and members: a retry of an unanswered create finds the room it made. */
    private var roomId = GroupCreate.mintRoomId()
    private var attempted: Pair<String, Set<String>>? = null

    fun setName(value: String) = _state.update { it.copy(name = value, problem = null) }

    fun toggle(bot: String) = _state.update { state ->
        val selected = when {
            bot in state.selected -> state.selected - bot
            state.selected.size >= GroupCreate.MAX_MEMBERS -> state.selected
            else -> state.selected + bot
        }
        state.copy(selected = selected, problem = null)
    }

    fun create(onCreated: (GroupRoom) -> Unit) {
        val current = _state.value
        if (!current.canCreate) return
        val picked = current.bots.filter { it.name in current.selected }
        val members = GroupCreate.members(picked)
        if (members == null) {
            _state.update { it.copy(problem = CreateProblem.HandleClash) }
            return
        }
        val name = current.name.trim()
        roomId = GroupCreate.roomId(name, current.selected, roomId, attempted)
        attempted = name to current.selected
        _state.update { it.copy(creating = true, problem = null) }
        viewModelScope.launch {
            when (val result = groups.create(roomId, name, members)) {
                is NetResult.Success -> {
                    groups.refreshRooms()
                    _state.update { it.copy(creating = false) }
                    onCreated(result.value)
                }

                is NetResult.Failure -> _state.update {
                    it.copy(creating = false, problem = CreateProblem.Failed(result.failure))
                }
            }
        }
    }
}
