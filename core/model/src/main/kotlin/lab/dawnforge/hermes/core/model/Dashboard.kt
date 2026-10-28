package lab.dawnforge.hermes.core.model

/** A dashboard the user has added. Its secrets (password, cookies, tokens) are stored apart. */
data class Dashboard(
    /** An app-generated identity that stays the same if the address changes. */
    val id: DashboardId,
    val url: DashboardUrl,
    /** The username last used to sign in, if the dashboard uses password sign-in. */
    val username: String?,
    /** When the dashboard was added, in epoch milliseconds. */
    val addedAt: Long,
)

@JvmInline
value class DashboardId(val value: String) {
    override fun toString(): String = value
}
