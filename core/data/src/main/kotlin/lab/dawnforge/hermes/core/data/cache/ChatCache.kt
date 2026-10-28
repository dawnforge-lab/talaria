package lab.dawnforge.hermes.core.data.cache

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lab.dawnforge.hermes.core.database.CacheDao
import lab.dawnforge.hermes.core.database.CachedSession
import lab.dawnforge.hermes.core.database.CachedTranscript
import lab.dawnforge.hermes.core.database.WaitingMessage
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.SessionSummary

/** A chat's newest rows as last loaded. */
data class CachedRows(val sessionId: String, val rows: List<JsonObject>, val hasOlder: Boolean, val savedAtMs: Long)

/**
 * A message waiting for the connection. [newChat] is set when its chat was started on the phone and
 * Hermes hasn't stored it, so the chat list shows it ([ChatCache.unsentNewChats]); [unclear] when it
 * went out and its reply was lost, so Hermes may have it.
 */
data class Waiting(
    val text: String,
    val typedAtMs: Long,
    val newChat: NewChatRef? = null,
    val unclear: Boolean = false,
)

/** A chat started on the phone and not stored on Hermes: the stored id Hermes gave it, and its folder. */
data class NewChatRef(val reservedId: String?, val cwd: String?)

/** A new chat with a message still to go, which the chat list shows until Hermes has it. */
data class UnsentNewChat(val chatKey: String, val text: String, val typedAtMs: Long, val chat: NewChatRef)

/**
 * What the app keeps of a dashboard's chats, so the session list and the chats loaded before open
 * with no network: the first page of each list, and the newest rows of each chat opened (up to
 * [MAX_ROWS] of them, for the [MAX_CHATS] chats opened last). Hermes has the real copy; this is
 * replaced whenever the network answers. Cleared when the dashboard is signed out of or removed.
 */
class ChatCache(private val dao: CacheDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun sessions(id: DashboardId, profile: String, archived: Boolean): List<SessionSummary> =
        dao.sessions(id.value, profile, archived).map { it.toSummary() }

    suspend fun saveSessions(id: DashboardId, profile: String, archived: Boolean, rows: List<SessionSummary>) {
        dao.replaceSessions(
            id.value,
            profile,
            archived,
            rows.distinctBy { it.id }.mapIndexed { position, row -> row.toCached(id, profile, archived, position) },
        )
    }

    /** A chat deleted: gone from the saved lists, and its rows too. */
    suspend fun forgetSession(id: DashboardId, profile: String, sessionId: String) {
        dao.deleteSession(id.value, profile, sessionId)
        dao.deleteTranscript(id.value, profile, sessionId)
    }

    suspend fun transcript(id: DashboardId, profile: String, sessionId: String): CachedRows? =
        dao.transcript(id.value, profile, sessionId)?.let { saved ->
            val rows = runCatching { json.parseToJsonElement(saved.rowsJson) as? JsonArray }.getOrNull()
                ?.mapNotNull { it as? JsonObject }
                ?: return null
            CachedRows(saved.resolvedId, rows, saved.hasOlder, saved.savedAtMs)
        }

    /** Saves the newest rows of a chat (oldest first); older ones beyond [MAX_ROWS] are left to the network. */
    suspend fun saveTranscript(
        id: DashboardId,
        profile: String,
        sessionId: String,
        resolvedId: String,
        rows: List<JsonObject>,
        hasOlder: Boolean,
    ) {
        val kept = rows.takeLast(MAX_ROWS)
        dao.saveTranscript(
            CachedTranscript(
                dashboardId = id.value,
                profile = profile,
                sessionId = sessionId,
                resolvedId = resolvedId,
                rowsJson = JsonArray(kept).toString(),
                hasOlder = hasOlder || kept.size < rows.size,
                savedAtMs = clock(),
            ),
        )
        dao.trimTranscripts(id.value, MAX_CHATS)
    }

    suspend fun waiting(id: DashboardId, profile: String, chatKey: String): Waiting? =
        dao.waiting(id.value, profile, chatKey)?.let {
            Waiting(it.text, it.typedAtMs, it.newChatRef(), it.unclear)
        }

    suspend fun saveWaiting(id: DashboardId, profile: String, chatKey: String, waiting: Waiting) {
        dao.saveWaiting(
            WaitingMessage(
                dashboardId = id.value,
                profile = profile,
                chatKey = chatKey,
                text = waiting.text,
                typedAtMs = waiting.typedAtMs,
                newChat = waiting.newChat != null,
                reservedId = waiting.newChat?.reservedId,
                cwd = waiting.newChat?.cwd,
                unclear = waiting.unclear,
            ),
        )
    }

    /** The new chats with a message still to go, newest first: Hermes can't list them yet. */
    fun unsentNewChats(id: DashboardId, profile: String): Flow<List<UnsentNewChat>> =
        dao.unsentNewChats(id.value, profile).map { rows ->
            rows.map { UnsentNewChat(it.chatKey, it.text, it.typedAtMs, it.newChatRef() ?: NewChatRef(null, null)) }
        }

    private fun WaitingMessage.newChatRef(): NewChatRef? = if (newChat) NewChatRef(reservedId, cwd) else null

    suspend fun clearWaiting(id: DashboardId, profile: String, chatKey: String) {
        dao.deleteWaiting(id.value, profile, chatKey)
    }

    /** Everything kept for a dashboard; for signing out of it or removing it. */
    suspend fun forget(id: DashboardId) = dao.forget(id.value)

    private fun SessionSummary.toCached(id: DashboardId, profile: String, archived: Boolean, position: Int) =
        CachedSession(
            dashboardId = id.value,
            profile = profile,
            archived = archived,
            id = this.id,
            position = position,
            title = title,
            preview = preview,
            model = model,
            source = source,
            startedAtMs = startedAtMs,
            lastActiveAtMs = lastActiveAtMs,
            messageCount = messageCount,
            pinned = pinned,
            unread = unread,
            parentSessionId = parentSessionId,
        )

    private fun CachedSession.toSummary() = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        model = model,
        source = source,
        startedAtMs = startedAtMs,
        lastActiveAtMs = lastActiveAtMs,
        messageCount = messageCount,
        archived = archived,
        pinned = pinned,
        unread = unread,
        profile = profile,
        parentSessionId = parentSessionId,
    )

    companion object {
        /** Rows kept per chat: a few screens of history, never a whole long conversation. */
        const val MAX_ROWS = 300

        /** Chats whose rows are kept, the most recently opened first. */
        const val MAX_CHATS = 50

        private val json = Json { ignoreUnknownKeys = true }
    }
}
