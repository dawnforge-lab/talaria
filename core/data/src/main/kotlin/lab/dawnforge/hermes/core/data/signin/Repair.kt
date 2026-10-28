package lab.dawnforge.hermes.core.data.signin

import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.net.StoredCookie

/**
 * A password sign-in the connection test proved: its session cookies, kept in memory only until a
 * repair's Reconnect now saves them. Never logged.
 */
class VerifiedSession internal constructor(val providerName: String, internal val cookies: List<StoredCookie>) {
    override fun toString(): String = "VerifiedSession(provider=$providerName)"
}

sealed interface RepairSave {
    /** Saved: [previous] is the dashboard as it was, [updated] as it is now. */
    data class Saved(val previous: Dashboard, val updated: Dashboard) : RepairSave

    /** Another saved dashboard already has that address. */
    data object AddressInUse : RepairSave

    /** The dashboard was removed meanwhile. */
    data object Gone : RepairSave
}
