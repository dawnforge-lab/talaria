package lab.dawnforge.hermes.core.data.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.database.CacheDao
import lab.dawnforge.hermes.core.database.CachedSession
import lab.dawnforge.hermes.core.database.CachedTranscript
import lab.dawnforge.hermes.core.database.WaitingMessage
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.SessionSummary

class ChatCacheTest {
    private val dao = MemoryDao()
    private val cache = ChatCache(dao) { 1_000L }
    private val id = DashboardId("d1")

    private fun summary(sessionId: String, pinned: Boolean = false) = SessionSummary(
        id = sessionId,
        title = "Chat $sessionId",
        preview = "Hello",
        model = "glm",
        source = "tui",
        startedAtMs = 1L,
        lastActiveAtMs = 2L,
        messageCount = 4,
        archived = false,
        pinned = pinned,
        unread = true,
        profile = "default",
        parentSessionId = null,
    )

    @Test
    fun `a saved list reads back as it was, once per chat`() = runTest {
        val rows = listOf(summary("a", pinned = true), summary("b"), summary("a", pinned = true))
        cache.saveSessions(id, "default", archived = false, rows)
        assertEquals(listOf(summary("a", pinned = true), summary("b")), cache.sessions(id, "default", archived = false))
        assertEquals(emptyList(), cache.sessions(id, "default", archived = true))
    }

    @Test
    fun `a transcript keeps its newest rows and says when older ones were left out`() = runTest {
        val rows = (1..(ChatCache.MAX_ROWS + 5)).map { n -> buildJsonObject { put("id", n) } }
        cache.saveTranscript(id, "default", "s1", "s1-tip", rows, hasOlder = false)
        val saved = cache.transcript(id, "default", "s1")!!
        assertEquals(ChatCache.MAX_ROWS, saved.rows.size)
        assertEquals(rows.last(), saved.rows.last())
        assertEquals("s1-tip", saved.sessionId)
        assertTrue(saved.hasOlder)
        assertEquals(1_000L, saved.savedAtMs)
    }

    @Test
    fun `a deleted chat and a forgotten dashboard leave nothing behind`() = runTest {
        cache.saveSessions(id, "default", archived = false, listOf(summary("a"), summary("b")))
        cache.saveTranscript(id, "default", "a", "a", listOf(buildJsonObject { put("id", 1) }), hasOlder = false)
        cache.forgetSession(id, "default", "a")
        assertEquals(listOf("b"), cache.sessions(id, "default", archived = false).map { it.id })
        assertNull(cache.transcript(id, "default", "a"))

        cache.saveWaiting(id, "default", "b", Waiting("hi", 5L))
        assertEquals(Waiting("hi", 5L), cache.waiting(id, "default", "b"))
        cache.forget(id)
        assertNull(cache.waiting(id, "default", "b"))
        assertEquals(emptyList(), cache.sessions(id, "default", archived = false))
    }

    @Test
    fun `a new chat's waiting message is listed until it goes, and keeps what it needs`() = runTest {
        val chat = NewChatRef(reservedId = "20261024_190700_ab12cd", cwd = "/work")
        cache.saveWaiting(id, "default", "6eda2c50", Waiting("Push test", 7L, newChat = chat, unclear = true))
        cache.saveWaiting(id, "default", "stored-chat", Waiting("In a listed chat", 8L))

        assertEquals(
            listOf(UnsentNewChat("6eda2c50", "Push test", 7L, chat)),
            cache.unsentNewChats(id, "default").first(),
        )
        assertEquals(Waiting("Push test", 7L, chat, unclear = true), cache.waiting(id, "default", "6eda2c50"))

        cache.clearWaiting(id, "default", "6eda2c50")
        assertEquals(emptyList(), cache.unsentNewChats(id, "default").first())
    }

    /** The DAO's contract, in memory. */
    private class MemoryDao : CacheDao {
        val sessions = mutableListOf<CachedSession>()
        val transcripts = mutableMapOf<Triple<String, String, String>, CachedTranscript>()
        val waiting = mutableMapOf<Triple<String, String, String>, WaitingMessage>()

        override suspend fun sessions(dashboardId: String, profile: String, archived: Boolean) =
            sessions.filter { it.dashboardId == dashboardId && it.profile == profile && it.archived == archived }
                .sortedBy { it.position }

        override suspend fun deleteSessions(dashboardId: String, profile: String, archived: Boolean) {
            sessions.removeAll { it.dashboardId == dashboardId && it.profile == profile && it.archived == archived }
        }

        override suspend fun insertSessions(rows: List<CachedSession>) {
            sessions += rows
        }

        override suspend fun deleteSession(dashboardId: String, profile: String, sessionId: String) {
            sessions.removeAll { it.dashboardId == dashboardId && it.profile == profile && it.id == sessionId }
        }

        override suspend fun transcript(dashboardId: String, profile: String, sessionId: String) =
            transcripts[Triple(dashboardId, profile, sessionId)]

        override suspend fun saveTranscript(transcript: CachedTranscript) {
            transcripts[Triple(transcript.dashboardId, transcript.profile, transcript.sessionId)] = transcript
        }

        override suspend fun deleteTranscript(dashboardId: String, profile: String, sessionId: String) {
            transcripts.remove(Triple(dashboardId, profile, sessionId))
        }

        override suspend fun trimTranscripts(dashboardId: String, keep: Int) {
            val keepKeys = transcripts.values.filter { it.dashboardId == dashboardId }
                .sortedByDescending { it.savedAtMs }.take(keep)
                .map { Triple(it.dashboardId, it.profile, it.sessionId) }.toSet()
            transcripts.keys.removeAll { it.first == dashboardId && it !in keepKeys }
        }

        override suspend fun waiting(dashboardId: String, profile: String, chatKey: String) =
            waiting[Triple(dashboardId, profile, chatKey)]

        override suspend fun saveWaiting(message: WaitingMessage) {
            waiting[Triple(message.dashboardId, message.profile, message.chatKey)] = message
            changes.value++
        }

        override suspend fun deleteWaiting(dashboardId: String, profile: String, chatKey: String) {
            waiting.remove(Triple(dashboardId, profile, chatKey))
            changes.value++
        }

        /** Bumped on every change to the waiting messages, as Room's query flows re-run. */
        private val changes = MutableStateFlow(0)

        override fun unsentNewChats(dashboardId: String, profile: String) = changes.map {
            waiting.values.filter { it.dashboardId == dashboardId && it.profile == profile && it.newChat }
                .sortedByDescending { it.typedAtMs }
        }

        override suspend fun deleteAllSessions(dashboardId: String) {
            sessions.removeAll { it.dashboardId == dashboardId }
        }

        override suspend fun deleteAllTranscripts(dashboardId: String) {
            transcripts.keys.removeAll { it.first == dashboardId }
        }

        override suspend fun deleteAllWaiting(dashboardId: String) {
            waiting.keys.removeAll { it.first == dashboardId }
        }
    }
}
