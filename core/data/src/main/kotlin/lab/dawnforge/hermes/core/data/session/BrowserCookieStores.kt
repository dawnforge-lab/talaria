package lab.dawnforge.hermes.core.data.session

import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.BrowserCookieStore

/** The app browser's cookie store of each dashboard signed in through its own page. */
interface BrowserCookieStores {
    suspend fun store(id: DashboardId): BrowserCookieStore

    /** Signs [id] out of the browser: its cookies go. */
    suspend fun clear(id: DashboardId)

    /** Forgets [id]'s browser data, for a removed dashboard. */
    suspend fun delete(id: DashboardId)

    /** Forgets what a sign-in page given up on left for [id], a dashboard never saved, at [url]. */
    suspend fun discard(id: DashboardId, url: String)

    /**
     * Forgets the browser data of every dashboard but [kept]: what sign-in pages left that the app
     * never saw end (it was closed on one), or whose profile was still on screen when given up.
     */
    suspend fun sweep(kept: Set<DashboardId>)
}

/**
 * Each dashboard's own WebView profile, as iOS gives each its own WebKit store: its cookies are
 * kept apart from every other dashboard's, and signing out empties only its own.
 *
 * A WebView without profiles (an old one) shares the app's single store; signing out then
 * expires only the cookies the dashboard's own address sees.
 */
class WebViewCookieStores(private val origins: suspend (DashboardId) -> String?) : BrowserCookieStores {
    /** The WebView profile of [id]; the sign-in page loads in it. */
    fun profileName(id: DashboardId): String = profileName(id.value)

    private val profiles: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** [id]'s cookie manager. Profiles are reached on the main thread only. */
    private suspend fun manager(id: DashboardId): CookieManager = withContext(Dispatchers.Main) {
        if (profiles) {
            ProfileStore.getInstance().getOrCreateProfile(
                profileName(id),
            ).cookieManager
        } else {
            CookieManager.getInstance()
        }
    }

    override suspend fun store(id: DashboardId): BrowserCookieStore = Store(manager(id))

    override suspend fun clear(id: DashboardId) {
        val manager = manager(id)
        withContext(Dispatchers.Main) {
            if (profiles) {
                manager.removeAllCookies(null)
            } else {
                origins(id)?.let { url -> expire(manager, url) }
            }
            manager.flush()
        }
    }

    override suspend fun delete(id: DashboardId) {
        if (profiles) {
            withContext(Dispatchers.Main) {
                forget(ProfileStore.getInstance(), profileName(id))
            }
        } else {
            clear(id)
        }
    }

    override suspend fun discard(id: DashboardId, url: String) {
        if (profiles) return delete(id)
        // The shared store: the page's cookies on the dashboard's own address are all it can tell apart.
        val manager = manager(id)
        withContext(Dispatchers.Main) {
            expire(manager, url)
            manager.flush()
        }
    }

    override suspend fun sweep(kept: Set<DashboardId>) = withContext(Dispatchers.Main) {
        if (!profiles) return@withContext
        val store = ProfileStore.getInstance()
        leftovers(store.allProfileNames, kept).forEach { name -> forget(store, name) }
    }

    companion object {
        private const val PREFIX = "dashboard-"

        private fun profileName(id: String): String = PREFIX + id

        /**
         * Deletes the profile [name] with everything in it. The WebView refuses for a profile this
         * process has opened (its sign-in page showed, or its cookies were read): that one is emptied
         * instead, and a sweep in a later run deletes it. Opening it first would make it refuse.
         *
         * A dashboard signed in with a password never had a profile: there is nothing to forget, and
         * WebView 124 hands back a profile for the missing name that crashes when its cookies are read.
         */
        internal fun forget(store: ProfileStore, name: String) {
            if (name !in store.allProfileNames) return
            if (runCatching { store.deleteProfile(name) }.getOrDefault(false)) return
            store.getProfile(name)?.cookieManager?.run {
                removeAllCookies(null)
                flush()
            }
        }

        /** The dashboard profiles among [names] that no dashboard in [kept] owns. */
        fun leftovers(names: List<String>, kept: Set<DashboardId>): List<String> {
            val owned = kept.mapTo(HashSet()) { profileName(it.value) }
            return names.filter { it.startsWith(PREFIX) && it !in owned }
        }
    }

    /** Expires every cookie [url] is sent: the shared store has no way to drop one site's alone. */
    private fun expire(manager: CookieManager, url: String) {
        manager.getCookie(url).orEmpty().split(';')
            .map { it.substringBefore('=').trim() }
            .filter(String::isNotEmpty)
            .forEach { name -> manager.setCookie(url, "$name=; Max-Age=0") }
    }

    private class Store(private val manager: CookieManager) : BrowserCookieStore {
        override fun cookieHeader(url: String): String? = manager.getCookie(url)

        override fun save(url: String, headers: List<String>) {
            headers.forEach { manager.setCookie(url, it) }
            manager.flush()
        }
    }
}
