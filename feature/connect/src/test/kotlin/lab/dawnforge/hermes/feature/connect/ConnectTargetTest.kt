package lab.dawnforge.hermes.feature.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.model.SignInMethod

class ConnectTargetTest {
    private val url = DashboardUrl("https", "hermes.example", null, "")

    @Test
    fun `the browser beside a password opens the other provider, never the password one`() {
        val target =
            ConnectTarget(url, SignInMethod.Password("basic", browser = SignInMethod.NativeOAuth("self-hosted")))
        assertTrue(target.browserAvailable)
        assertEquals("self-hosted", target.browserProvider)
        assertEquals("basic", target.passwordProvider)
    }

    @Test
    fun `several other providers leave the choice to Hermes`() {
        val target = ConnectTarget(url, SignInMethod.Password("basic", browser = SignInMethod.NativeOAuth(null)))
        assertTrue(target.browserAvailable)
        assertNull(target.browserProvider)
    }

    @Test
    fun `a password alone offers no browser`() {
        val target = ConnectTarget(url, SignInMethod.Password("basic"))
        assertFalse(target.browserAvailable)
        assertNull(target.browserProvider)
    }
}
