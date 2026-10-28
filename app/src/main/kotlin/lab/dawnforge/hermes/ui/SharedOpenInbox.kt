package lab.dawnforge.hermes.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import lab.dawnforge.hermes.share.SharedOpen

/** A share the share sheet handed to the app, until the app opens its chat. */
@Singleton
class SharedOpenInbox @Inject constructor() {
    private val pending = MutableStateFlow<SharedOpen?>(null)

    val shared: StateFlow<SharedOpen?> = pending.asStateFlow()

    fun offer(open: SharedOpen) {
        pending.value = open
    }

    fun take(): SharedOpen? = pending.getAndUpdate { null }
}
