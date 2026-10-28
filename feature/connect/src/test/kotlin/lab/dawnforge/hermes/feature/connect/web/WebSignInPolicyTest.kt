package lab.dawnforge.hermes.feature.connect.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.DashboardUrl

class WebSignInPolicyTest {
    private val dashboard = DashboardUrl("https", "hermes.example.com", null, "/hermes")

    @Test
    fun `a dashboard page past its sign-in counts, a sign-in page doesn't`() {
        assertTrue(WebSignInPolicy.isSignedInDashboardPage("https://hermes.example.com/hermes/", dashboard))
        assertTrue(WebSignInPolicy.isSignedInDashboardPage("https://hermes.example.com:443/hermes/chat", dashboard))
        assertFalse(WebSignInPolicy.isSignedInDashboardPage("https://hermes.example.com/hermes/login", dashboard))
        assertFalse(WebSignInPolicy.isSignedInDashboardPage("https://hermes.example.com/oauth2/start?rd=/", dashboard))
        assertFalse(WebSignInPolicy.isSignedInDashboardPage("https://auth.example.com/", dashboard))
        assertFalse(WebSignInPolicy.isSignedInDashboardPage("http://hermes.example.com/hermes/", dashboard))
    }

    @Test
    fun `the sign-in service may redirect anywhere safe until the dashboard shows`() {
        assertTrue(WebSignInPolicy.allowsNavigation("https://accounts.example.org/o/auth", dashboard, pinned = false))
        assertTrue(WebSignInPolicy.allowsNavigation("http://192.168.1.10:9091/", dashboard, pinned = false))
        assertFalse(WebSignInPolicy.allowsNavigation("http://evil.example.org/", dashboard, pinned = false))
        assertFalse(
            WebSignInPolicy.allowsNavigation("https://user:pw@accounts.example.org/", dashboard, pinned = false),
        )
        assertFalse(WebSignInPolicy.allowsNavigation("javascript:alert(1)", dashboard, pinned = false))

        assertFalse(WebSignInPolicy.allowsNavigation("https://accounts.example.org/", dashboard, pinned = true))
        assertTrue(WebSignInPolicy.allowsNavigation("https://hermes.example.com/hermes/x", dashboard, pinned = true))
    }
}
