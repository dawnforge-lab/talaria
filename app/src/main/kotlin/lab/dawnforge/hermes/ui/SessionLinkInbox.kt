package lab.dawnforge.hermes.ui

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import lab.dawnforge.hermes.core.net.SessionLink

/** A link to a chat another app opened (`talaria://session/…`), until the app opens it. */
@Singleton
class SessionLinkInbox @Inject constructor() {
    private val pending = MutableStateFlow<SessionLink?>(null)

    val link: StateFlow<SessionLink?> = pending.asStateFlow()

    /** True when [raw] was a readable session link. */
    fun offer(raw: String): Boolean {
        pending.value = SessionLink.parse(raw) ?: return false
        return true
    }

    fun take(): SessionLink? = pending.getAndUpdate { null }
}
