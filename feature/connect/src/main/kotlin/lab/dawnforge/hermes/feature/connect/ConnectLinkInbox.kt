package lab.dawnforge.hermes.feature.connect

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import lab.dawnforge.hermes.core.net.ConnectLink

/**
 * Hands a `talaria://connect` link from the activity to the connect flow.
 *
 * The link can carry a password, so it is kept in memory only, never in navigation arguments
 * or saved state, and is taken (cleared) by whoever handles it.
 */
@Singleton
class ConnectLinkInbox @Inject constructor() {
    private val pending = MutableStateFlow<ConnectLink?>(null)

    val link: StateFlow<ConnectLink?> = pending.asStateFlow()

    /** Returns true when [raw] was a readable connect link. */
    fun offer(raw: String): Boolean {
        val parsed = ConnectLink.parse(raw) ?: return false
        pending.value = parsed
        return true
    }

    fun take(): ConnectLink? = pending.getAndUpdate { null }
}
