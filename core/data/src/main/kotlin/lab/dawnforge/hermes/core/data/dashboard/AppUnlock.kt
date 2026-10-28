package lab.dawnforge.hermes.core.data.dashboard

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import lab.dawnforge.hermes.core.model.DashboardId

/**
 * Whether the app waits for the phone's fingerprint, face or screen lock before showing the active
 * dashboard, as iOS's Face ID unlock: a dashboard set to it, with a saved password, locks until
 * one unlock, which holds until the app is closed. Work in the background (a renewal, a call)
 * goes on meanwhile, as on iOS.
 */
class AppUnlock(private val repository: DashboardRepository) {
    private val unlocked = MutableStateFlow<Set<DashboardId>>(emptySet())

    /** The dashboard waiting to be unlocked, or null when nothing is locked. */
    val locked: Flow<DashboardId?> = combine(
        repository.activeDashboardId,
        repository.unlockRequired,
        unlocked,
    ) { active, required, open ->
        active?.takeIf { it in required && it !in open && repository.credentials(it) != null }
    }

    fun unlocked(id: DashboardId) = unlocked.update { it + id }

    /** Signing out or a new sign-in starts over: the next start asks again. */
    fun forget(id: DashboardId) = unlocked.update { it - id }
}
