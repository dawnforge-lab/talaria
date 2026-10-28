package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Settings pages Hermes links to, as iOS reads `talaria://settings/<page>`. */
class SettingsLinkTest {
    @Test
    fun `each page Hermes links to is read`() {
        assertEquals(SettingsLink.Location, SettingsLink.parse("talaria://settings/location"))
        assertEquals(SettingsLink.Calls, SettingsLink.parse("talaria://settings/calls"))
        assertEquals(SettingsLink.Voice, SettingsLink.parse("talaria://settings/voice"))
        assertEquals(SettingsLink.Plugin, SettingsLink.parse("talaria://settings/plugin"))
        assertEquals(SettingsLink.Health, SettingsLink.parse("talaria://settings/health"))
        assertEquals(SettingsLink.Phone, SettingsLink.parse("talaria://settings/phone"))
    }

    @Test
    fun `case, a trailing slash and a query don't matter`() {
        assertEquals(SettingsLink.Location, SettingsLink.parse("TALARIA://Settings/Location/"))
        assertEquals(SettingsLink.Calls, SettingsLink.parse("talaria://settings/calls?from=reply"))
    }

    @Test
    fun `anything else isn't a settings link`() {
        assertNull(SettingsLink.parse("talaria://settings/"))
        assertNull(SettingsLink.parse("talaria://settings/wallet"))
        assertNull(SettingsLink.parse("talaria://settings/location/extra"))
        assertNull(SettingsLink.parse("talaria://session/abc"))
        assertNull(SettingsLink.parse("https://example.com/settings/location"))
    }
}
