package lab.dawnforge.hermes.core.data.dashboard

import lab.dawnforge.hermes.core.model.DashboardId

/**
 * Something that keeps data of its own about a dashboard (push pairings, say) and forgets it when
 * the user signs out of the dashboard or removes it. Contributed to a Hilt set from other modules.
 */
fun interface DashboardDataOwner {
    suspend fun forget(id: DashboardId)
}
