package lab.dawnforge.hermes.core.data.dashboard

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.StoredCookie

class DashboardRepositoryTest {
    private val stores = TestStores()
    private val repository = stores.repository

    private val home = Dashboard(DashboardId("a"), DashboardUrl("http", "192.168.1.5", 9119, ""), "me", addedAt = 1)
    private val work =
        Dashboard(DashboardId("b"), DashboardUrl("https", "hermes.example.com", null, "/hermes"), "me", addedAt = 2)

    @Test
    fun `saving activates the dashboard and keeps the others`() = runTest {
        repository.saveAndActivate(home, "basic")
        repository.saveAndActivate(work, "basic")
        assertEquals(listOf(home, work), repository.dashboards.first())
        assertEquals(work.id, repository.activeDashboardId.first())
        assertEquals(work, repository.dashboardFor(work.url))
        assertEquals("basic", repository.passwordProvider(work.id))

        repository.setActive(home.id)
        assertEquals(home.id, repository.activeDashboardId.first())
    }

    @Test
    fun `secrets round-trip and never reach disk in the clear`() = runTest {
        repository.saveAndActivate(home, "basic")
        repository.saveCredentials(home.id, DashboardCredentials("me", "hunter2-secret"))
        val cookie = StoredCookie("hermes_session_at", "cookie-secret", "192.168.1.5", "/", 0, false, false, true, true)
        repository.saveCookies(home.id, listOf(cookie))
        repository.saveCloudflareToken(home.id, CloudflareAccessToken(" id.access ", "cf-secret"))

        assertEquals(DashboardCredentials("me", "hunter2-secret"), repository.credentials(home.id))
        assertEquals(listOf(cookie), repository.cookies(home.id))
        assertEquals(CloudflareAccessToken("id.access", "cf-secret"), repository.cloudflareToken(home.id))

        val onDisk = stores.secretsFileText()
        listOf("hunter2-secret", "cookie-secret", "cf-secret").forEach { assertFalse(onDisk.contains(it), it) }
    }

    @Test
    fun `a secret moved to another dashboard does not decrypt`() = runTest {
        repository.saveCredentials(home.id, DashboardCredentials("me", "pw"))
        val stolen = stores.secrets.data.first()[stringPreferencesKey("a.credentials")]!!
        stores.secrets.edit { it[stringPreferencesKey("b.credentials")] = stolen }
        assertNull(repository.credentials(work.id))
    }

    @Test
    fun `sign out forgets the session but keeps the dashboard and Cloudflare token`() = runTest {
        repository.saveAndActivate(home, "basic")
        repository.saveCredentials(home.id, DashboardCredentials("me", "pw"))
        repository.saveCookies(home.id, listOf(StoredCookie("a", "1", "192.168.1.5", "/", 0, false, false, true, true)))
        repository.saveCloudflareToken(home.id, CloudflareAccessToken("id", "secret"))

        repository.signOut(home.id)
        assertNull(repository.credentials(home.id))
        assertEquals(emptyList(), repository.cookies(home.id))
        assertEquals(CloudflareAccessToken("id", "secret"), repository.cloudflareToken(home.id))
        assertEquals(listOf(home), repository.dashboards.first())
    }

    @Test
    fun `removing the active dashboard activates the newest remaining one`() = runTest {
        repository.saveAndActivate(home, "basic")
        repository.saveAndActivate(work, "basic")
        repository.saveCredentials(work.id, DashboardCredentials("me", "pw"))

        repository.remove(work.id)
        assertEquals(listOf(home), repository.dashboards.first())
        assertEquals(home.id, repository.activeDashboardId.first())
        assertNull(repository.credentials(work.id))

        repository.remove(home.id)
        assertNull(repository.activeDashboardId.first())
    }
}
