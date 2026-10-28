package lab.dawnforge.hermes.feature.connect.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import lab.dawnforge.hermes.core.net.AddressProblem
import lab.dawnforge.hermes.core.net.ConnectionFailure

class TroubleshootTest {
    @Test
    fun `each failure opens the wizard where iOS does`() {
        assertEquals(SetupStart.AccessMethod, ConnectionFailure.ConnectionRefused.troubleshootStart())
        assertEquals(SetupStart.AccessMethod, ConnectionFailure.HostNotFound.troubleshootStart())
        assertEquals(SetupStart.Tls, ConnectionFailure.TlsFailure("bad cert").troubleshootStart())
        assertEquals(SetupStart.Credentials, ConnectionFailure.AuthenticationRejected.troubleshootStart())
        assertEquals(SetupStart.Cloudflare, ConnectionFailure.CloudflareTokenRejected.troubleshootStart())
        assertEquals(SetupStart.Dashboard, ConnectionFailure.DashboardUnavailable(502).troubleshootStart())
        assertEquals(
            SetupStart.Dashboard,
            ConnectionFailure.InvalidAddress(AddressProblem.InsecureTransport).troubleshootStart(),
        )
    }

    @Test
    fun `the wizard can't help with too many attempts or an expired sign-in`() {
        assertNull(ConnectionFailure.RateLimited(60).troubleshootStart())
        assertNull(ConnectionFailure.SignInRequired.troubleshootStart())
    }
}
