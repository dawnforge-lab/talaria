package lab.dawnforge.hermes.core.data.signin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.data.dashboard.DashboardCredentials
import lab.dawnforge.hermes.core.model.CloudflareAccessToken
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl
import lab.dawnforge.hermes.core.net.DashboardAuthClient
import lab.dawnforge.hermes.core.net.DashboardClientFactory
import lab.dawnforge.hermes.core.net.StoredCookie

class RepairTest {
    private val stores = TestStores()
    private val repository = stores.repository
    private val service = SignInService(
        auth = DashboardAuthClient(DashboardClientFactory(DashboardClientFactory.defaultBaseClient())),
        repository = repository,
        cookies = stores.cookies,
    )
    private val home =
        Dashboard(DashboardId("a"), DashboardUrl("https", "hermes.example.com", null, ""), "me", addedAt = 1)
    private val cookie =
        StoredCookie("hermes_session_at", "fresh", "hermes.example.com", "/", 4_102_444_800_000, true, true, true, true)
    private val session = VerifiedSession("basic", listOf(cookie))

    private suspend fun saved(credentials: DashboardCredentials? = DashboardCredentials("me", "old")) {
        repository.saveAndActivate(home, "basic")
        repository.saveCredentials(home.id, credentials)
        repository.saveCloudflareToken(home.id, CloudflareAccessToken("id.access", "secret"))
    }

    @Test
    fun `a tested password replaces the saved one and its session is used`() = runTest {
        saved()
        val result = service.applyRepair(home.id, home.url, session, " me ", "new")
        assertIs<RepairSave.Saved>(result)
        assertEquals(DashboardCredentials("me", "new"), repository.credentials(home.id))
        assertEquals(listOf(cookie), stores.cookies.jar(home.id).snapshot())
    }

    @Test
    fun `a repair never saves a password that wasn't saved before`() = runTest {
        saved(credentials = null)
        service.applyRepair(home.id, home.url, session, "me", "pw")
        assertNull(repository.credentials(home.id))
    }

    @Test
    fun `a move to another server drops its Cloudflare token, and can be put back`() = runTest {
        saved()
        val moved = DashboardUrl("https", "other.example.com", null, "")
        val result = assertIs<RepairSave.Saved>(service.applyRepair(home.id, moved, session, "me", "old"))
        assertEquals(moved, repository.dashboard(home.id)?.url)
        assertNull(repository.cloudflareToken(home.id))

        service.restoreAddress(result.previous)
        assertEquals(home.url, repository.dashboard(home.id)?.url)
    }

    @Test
    fun `a path change on the same server keeps the Cloudflare token`() = runTest {
        saved()
        service.applyRepair(home.id, home.url.copy(basePath = "/hermes"), session, "me", "old")
        assertEquals(CloudflareAccessToken("id.access", "secret"), repository.cloudflareToken(home.id))
    }

    @Test
    fun `an address another dashboard has is refused`() = runTest {
        saved()
        val work = Dashboard(DashboardId("b"), DashboardUrl("https", "work.example.com", null, ""), "me", addedAt = 2)
        repository.saveAndActivate(work, "basic")
        assertEquals(RepairSave.AddressInUse, service.applyRepair(home.id, work.url, session, "me", "old"))
        assertEquals(home.url, repository.dashboard(home.id)?.url)
    }
}
