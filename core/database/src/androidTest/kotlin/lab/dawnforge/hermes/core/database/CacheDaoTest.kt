package lab.dawnforge.hermes.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheDaoTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        HermesDatabase::class.java,
    ).build()
    private val dao = database.cache()

    @After
    fun close() = database.close()

    private fun session(id: String, position: Int, dashboard: String = "d1", archived: Boolean = false) = CachedSession(
        dashboardId = dashboard,
        profile = "default",
        archived = archived,
        id = id,
        position = position,
        title = "Chat $id",
        preview = null,
        model = null,
        source = "tui",
        startedAtMs = 1L,
        lastActiveAtMs = 2L,
        messageCount = 3,
        pinned = false,
        unread = false,
        parentSessionId = null,
    )

    @Test
    fun aListIsReplacedWholeAndKeepsItsOrder() = runTest {
        dao.replaceSessions("d1", "default", false, listOf(session("a", 0), session("b", 1)))
        dao.replaceSessions("d1", "default", false, listOf(session("c", 0), session("a", 1)))
        assertEquals(listOf("c", "a"), dao.sessions("d1", "default", false).map { it.id })
        assertEquals(emptyList<CachedSession>(), dao.sessions("d1", "default", true))
    }

    @Test
    fun onlyTheMostRecentlySavedTranscriptsAreKept() = runTest {
        (1..5).forEach { n ->
            dao.saveTranscript(CachedTranscript("d1", "default", "s$n", "s$n", "[]", false, savedAtMs = n.toLong()))
        }
        dao.trimTranscripts("d1", keep = 2)
        assertNull(dao.transcript("d1", "default", "s3"))
        assertEquals("s5", dao.transcript("d1", "default", "s5")?.resolvedId)
        assertEquals("s4", dao.transcript("d1", "default", "s4")?.resolvedId)
    }

    @Test
    fun forgettingADashboardLeavesTheOthers() = runTest {
        dao.replaceSessions("d1", "default", false, listOf(session("a", 0)))
        dao.replaceSessions("d2", "default", false, listOf(session("b", 0, dashboard = "d2")))
        dao.saveWaiting(WaitingMessage("d1", "default", "a", "hello", 10))
        dao.forget("d1")
        assertEquals(emptyList<CachedSession>(), dao.sessions("d1", "default", false))
        assertNull(dao.waiting("d1", "default", "a"))
        assertEquals(listOf("b"), dao.sessions("d2", "default", false).map { it.id })
    }
}
