package lab.dawnforge.hermes.core.data.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lab.dawnforge.hermes.core.data.TestStores
import lab.dawnforge.hermes.core.model.Dashboard
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DashboardUrl

class AppUnlockTest {
    private val stores = TestStores()
    private val repository = stores.repository
    private val unlock = AppUnlock(repository)

    private val home =
        Dashboard(DashboardId("a"), DashboardUrl("https", "hermes.example.com", null, ""), "me", addedAt = 1)

    private suspend fun lockedHome() {
        repository.saveAndActivate(home, "basic")
        repository.saveCredentials(home.id, DashboardCredentials("me", "pw"))
        repository.setUnlockRequired(home.id, true)
    }

    @Test
    fun `a dashboard set to unlock waits until it is unlocked`() = runTest {
        lockedHome()
        assertEquals(home.id, unlock.locked.first())

        unlock.unlocked(home.id)
        assertNull(unlock.locked.first())
    }

    @Test
    fun `nothing locks without the setting or a saved password`() = runTest {
        repository.saveAndActivate(home, "basic")
        repository.saveCredentials(home.id, DashboardCredentials("me", "pw"))
        assertNull(unlock.locked.first())

        repository.setUnlockRequired(home.id, true)
        repository.signOut(home.id)
        assertNull(unlock.locked.first())
    }

    @Test
    fun `forgetting the unlock asks again`() = runTest {
        lockedHome()
        unlock.unlocked(home.id)
        unlock.forget(home.id)
        assertEquals(home.id, unlock.locked.first())
    }

    @Test
    fun `removing the dashboard drops its setting`() = runTest {
        lockedHome()
        repository.remove(home.id)
        assertEquals(emptySet(), repository.unlockRequired.first())
        assertNull(unlock.locked.first())
    }
}
