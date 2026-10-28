package lab.dawnforge.hermes.core.data.session

import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import kotlin.test.Test
import kotlin.test.assertEquals
import lab.dawnforge.hermes.core.model.DashboardId

class BrowserProfilesTest {
    @Test
    fun `a sweep takes only dashboard profiles no saved dashboard owns`() {
        val names = listOf("Default", "dashboard-saved", "dashboard-abandoned", "other-app-profile")
        assertEquals(
            listOf("dashboard-abandoned"),
            WebViewCookieStores.leftovers(names, setOf(DashboardId("saved"))),
        )
    }

    @Test
    fun `with nothing saved every dashboard profile goes`() {
        assertEquals(
            listOf("dashboard-a", "dashboard-b"),
            WebViewCookieStores.leftovers(listOf("Default", "dashboard-a", "dashboard-b"), emptySet()),
        )
    }

    @Test
    fun `removing a dashboard that never had a profile touches none`() {
        // On the emulator (WebView 124, 2026-10-26): removing a dashboard signed in with a password
        // crashed the app, and the dashboard came back on the next start.
        val store = WebView124Store(listOf("Default"))
        WebViewCookieStores.forget(store, "dashboard-password-only")
        assertEquals(emptyList(), store.deleted)
    }

    @Test
    fun `a dashboard's own profile is deleted`() {
        val store = WebView124Store(listOf("Default", "dashboard-browser"))
        WebViewCookieStores.forget(store, "dashboard-browser")
        assertEquals(listOf("dashboard-browser"), store.deleted)
    }

    /** Profiles as WebView 124 keeps them: asked for a name it doesn't have, it hands back a broken one. */
    private class WebView124Store(names: List<String>) : ProfileStore {
        private val names = names.toMutableList()
        val deleted = mutableListOf<String>()

        override fun getAllProfileNames(): List<String> = names.toList()

        override fun deleteProfile(name: String): Boolean {
            if (!names.remove(name)) return false
            deleted += name
            return true
        }

        override fun getProfile(name: String): Profile? =
            if (name in names) error("not used in these tests") else throw NullPointerException("no such profile")

        override fun getOrCreateProfile(name: String): Profile = error("not used in these tests")
    }
}
