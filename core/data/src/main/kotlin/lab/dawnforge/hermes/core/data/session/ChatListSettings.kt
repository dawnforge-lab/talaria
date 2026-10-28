package lab.dawnforge.hermes.core.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.model.DashboardId

/**
 * How the chat list is shown, kept on this phone: which sections are closed, which filters are
 * on, and the starred chats. Hermes has no star, so stars are per dashboard and profile here.
 */
class ChatListSettings(private val settings: DataStore<Preferences>) : DashboardDataOwner {
    /** The closed sections' names; until the user changes them, [defaultCollapsed]. */
    fun collapsed(defaultCollapsed: Set<String>): Flow<Set<String>> =
        settings.data.map { it[COLLAPSED] ?: defaultCollapsed }.distinctUntilChanged()

    suspend fun setCollapsed(sections: Set<String>) {
        settings.edit { it[COLLAPSED] = sections }
    }

    val filters: Flow<Set<String>> = settings.data.map { it[FILTERS].orEmpty() }.distinctUntilChanged()

    suspend fun setFilters(filters: Set<String>) {
        settings.edit { it[FILTERS] = filters }
    }

    fun starred(id: DashboardId, profile: String): Flow<Set<String>> =
        settings.data.map { it[starredKey(id, profile)].orEmpty() }.distinctUntilChanged()

    suspend fun setStarred(id: DashboardId, profile: String, sessionId: String, starred: Boolean) {
        settings.edit {
            val key = starredKey(id, profile)
            val current = it[key].orEmpty()
            it[key] = if (starred) current + sessionId else current - sessionId
        }
    }

    /** Signing out of a dashboard, or removing it, forgets its stars. */
    override suspend fun forget(id: DashboardId) {
        settings.edit { prefs ->
            val prefix = "$STARRED_PREFIX${id.value}."
            prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
        }
    }

    private fun starredKey(id: DashboardId, profile: String) =
        stringSetPreferencesKey("$STARRED_PREFIX${id.value}.$profile")

    private companion object {
        val COLLAPSED = stringSetPreferencesKey("chat_list.collapsed")
        val FILTERS = stringSetPreferencesKey("chat_list.filters")
        const val STARRED_PREFIX = "chat_list.starred."
    }
}
