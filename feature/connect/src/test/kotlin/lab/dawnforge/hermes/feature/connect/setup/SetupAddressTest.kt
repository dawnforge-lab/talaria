package lab.dawnforge.hermes.feature.connect.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import lab.dawnforge.hermes.core.net.AddressProblem

class SetupAddressTest {
    private fun built(draft: SetupDraft) = SetupAddressBuilder.build(draft)

    private fun problem(draft: SetupDraft) = (built(draft) as SetupAddress.Problem).problem

    @Test
    fun `a LAN address is http with its port`() {
        val draft = SetupDraft(method = AccessMethod.Lan, lanHost = " 192.168.1.5 ", lanPort = "9119")
        assertEquals(SetupAddress.Built("http://192.168.1.5:9119"), built(draft))
        assertEquals(SetupAddressProblem.InvalidPort, problem(draft.copy(lanPort = "")))
        assertEquals(SetupAddressProblem.InvalidPort, problem(draft.copy(lanPort = "70000")))
        assertEquals(SetupAddressProblem.InvalidHost, problem(draft.copy(lanHost = "192.168.1")))
        assertEquals(SetupAddressProblem.InvalidHost, problem(draft.copy(lanHost = "bad host")))
    }

    @Test
    fun `a Tailscale Serve name is https and other hosts need a scheme`() {
        val serve = SetupDraft(method = AccessMethod.Tailscale, tailscaleHost = "home.tail0a1b2c.ts.net")
        assertEquals(SetupAddress.Built("https://home.tail0a1b2c.ts.net"), built(serve))
        assertEquals(
            SetupAddress.Built("https://home.tail0a1b2c.ts.net:8443"),
            built(serve.copy(tailscalePort = "8443")),
        )

        val ip = SetupDraft(method = AccessMethod.Tailscale, tailscaleHost = "100.64.0.7", tailscalePort = "9119")
        assertEquals(SetupAddressProblem.SchemeRequired, problem(ip))
        assertEquals(SetupAddress.Built("http://100.64.0.7:9119"), built(ip.copy(tailscaleScheme = SetupScheme.Http)))
        assertEquals(SetupAddressProblem.InvalidHost, problem(ip.copy(tailscaleHost = "")))
    }

    @Test
    fun `a reverse proxy must be a full https address`() {
        val proxy = SetupDraft(method = AccessMethod.ReverseProxy, proxyAddress = "https://example.com/hermes")
        assertEquals(SetupAddress.Built("https://example.com/hermes"), built(proxy))
        assertEquals(SetupAddressProblem.HttpsRequired, problem(proxy.copy(proxyAddress = "http://example.com")))
        assertEquals(SetupAddressProblem.HttpsRequired, problem(proxy.copy(proxyAddress = "example.com")))
    }

    @Test
    fun `the app's address rules have the last word`() {
        val existing = SetupDraft(useExisting = true, existingAddress = "http://hermes.example.com")
        assertEquals(SetupAddressProblem.Policy(AddressProblem.InsecureTransport), problem(existing))
        assertEquals(SetupAddressProblem.MethodRequired, problem(SetupDraft()))
    }
}
