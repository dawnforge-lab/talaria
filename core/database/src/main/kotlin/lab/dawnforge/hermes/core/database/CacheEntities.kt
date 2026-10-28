package lab.dawnforge.hermes.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * One row of a profile's session list as last loaded, so the list opens with no network. [position]
 * keeps the order Hermes gave (pinned first, then the most recent).
 */
@Entity(tableName = "cached_session", primaryKeys = ["dashboardId", "profile", "archived", "id"])
data class CachedSession(
    val dashboardId: String,
    val profile: String,
    val archived: Boolean,
    val id: String,
    val position: Int,
    val title: String?,
    val preview: String?,
    val model: String?,
    val source: String?,
    val startedAtMs: Long?,
    val lastActiveAtMs: Long?,
    val messageCount: Int,
    val pinned: Boolean,
    val unread: Boolean,
    val parentSessionId: String?,
)

/**
 * The newest stored rows of a chat as last loaded (Hermes' own JSON, oldest first), so a saved chat
 * opens with no network. [resolvedId] is the id Hermes read them from (a later part of a compressed
 * conversation, perhaps).
 */
@Entity(tableName = "cached_transcript", primaryKeys = ["dashboardId", "profile", "sessionId"])
data class CachedTranscript(
    val dashboardId: String,
    val profile: String,
    val sessionId: String,
    val resolvedId: String,
    val rowsJson: String,
    val hasOlder: Boolean,
    val savedAtMs: Long,
)

/**
 * A message typed while the app could not reach Hermes, waiting to be sent: one per chat. [chatKey]
 * is the chat's stored id, or its live id for a chat not stored yet.
 */
@Entity(tableName = "waiting_message", primaryKeys = ["dashboardId", "profile", "chatKey"])
data class WaitingMessage(
    val dashboardId: String,
    val profile: String,
    val chatKey: String,
    val text: String,
    val typedAtMs: Long,
    /**
     * Its chat was started on the phone and nothing of it is stored on Hermes yet, so the chat list
     * can't list it: the list shows it from here until the message goes.
     */
    @ColumnInfo(defaultValue = "0") val newChat: Boolean = false,
    /** The stored id Hermes gave that new chat; it is opened by it first, in case Hermes stored it. */
    val reservedId: String? = null,
    /** That new chat's folder on the host, to start it again there. */
    val cwd: String? = null,
    /** It went out but its reply was lost: Hermes may have it, so that is checked before it goes again. */
    @ColumnInfo(defaultValue = "0") val unclear: Boolean = false,
)
