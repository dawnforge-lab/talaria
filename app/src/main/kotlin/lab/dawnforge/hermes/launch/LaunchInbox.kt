package lab.dawnforge.hermes.launch

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** The last action the widget, a shortcut or the tile asked for, until the app does it. */
@Singleton
class LaunchInbox @Inject constructor() {
    private val pending = MutableStateFlow<LaunchAction?>(null)

    val action: StateFlow<LaunchAction?> = pending.asStateFlow()

    fun offer(action: LaunchAction) {
        pending.value = action
    }

    fun take(): LaunchAction? = pending.getAndUpdate { null }
}
