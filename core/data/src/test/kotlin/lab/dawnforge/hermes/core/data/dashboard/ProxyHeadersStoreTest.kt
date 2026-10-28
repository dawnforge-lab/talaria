package lab.dawnforge.hermes.core.data.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.FakeCipher
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.net.ProxyHeader

class ProxyHeadersStoreTest {
    private val stores = TestStores()
    private val origin = "https://hermes.example.com"

    private fun store() = ProxyHeadersStore(stores.secrets, FakeCipher())

    @Test
    fun `headers are kept by origin and never reach disk in the clear`() = runTest {
        val store = store()
        store.save(origin, listOf(ProxyHeader("X-Proxy-Key", "proxy-secret")))

        assertEquals(listOf(ProxyHeader("X-Proxy-Key", "proxy-secret")), store.forOrigin(origin))
        assertEquals(emptyList(), store.forOrigin("https://other.example.com"))
        assertFalse(stores.secretsFileText().contains("proxy-secret"))
        // A fresh store, as after the app restarts, reads them back.
        assertEquals(listOf(ProxyHeader("X-Proxy-Key", "proxy-secret")), store().forOrigin(origin))
    }

    @Test
    fun `invalid rows are kept for editing but not sent`() = runTest {
        val store = store()
        store.save(origin, listOf(ProxyHeader("Host", "evil"), ProxyHeader("X-Ok", "1")))

        assertEquals(listOf(ProxyHeader("X-Ok", "1")), store.forOrigin(origin))
        assertEquals(2, store.rows(origin).size)
    }

    @Test
    fun `forgetting an origin drops only its headers`() = runTest {
        val store = store()
        store.save(origin, listOf(ProxyHeader("X-A", "1")))
        store.save("https://other.example.com", listOf(ProxyHeader("X-B", "2")))

        store.forget(origin)
        assertEquals(emptyList(), store().forOrigin(origin))
        assertEquals(listOf(ProxyHeader("X-B", "2")), store().forOrigin("https://other.example.com"))
    }
}
