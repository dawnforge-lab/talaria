package lab.dawnforge.hermes.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    // ---- Session lists -------------------------------------------------------------------------

    @Query(
        "SELECT * FROM cached_session WHERE dashboardId = :dashboardId AND profile = :profile " +
            "AND archived = :archived ORDER BY position",
    )
    suspend fun sessions(dashboardId: String, profile: String, archived: Boolean): List<CachedSession>

    /** Replaces the saved first page of a list with [rows]. */
    @Transaction
    suspend fun replaceSessions(dashboardId: String, profile: String, archived: Boolean, rows: List<CachedSession>) {
        deleteSessions(dashboardId, profile, archived)
        insertSessions(rows)
    }

    @Query(
        "DELETE FROM cached_session WHERE dashboardId = :dashboardId AND profile = :profile AND archived = :archived",
    )
    suspend fun deleteSessions(dashboardId: String, profile: String, archived: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(rows: List<CachedSession>)

    @Query("DELETE FROM cached_session WHERE dashboardId = :dashboardId AND profile = :profile AND id = :sessionId")
    suspend fun deleteSession(dashboardId: String, profile: String, sessionId: String)

    // ---- Transcripts ---------------------------------------------------------------------------

    @Query(
        "SELECT * FROM cached_transcript WHERE dashboardId = :dashboardId AND profile = :profile " +
            "AND sessionId = :sessionId",
    )
    suspend fun transcript(dashboardId: String, profile: String, sessionId: String): CachedTranscript?

    @Upsert
    suspend fun saveTranscript(transcript: CachedTranscript)

    @Query(
        "DELETE FROM cached_transcript WHERE dashboardId = :dashboardId AND profile = :profile " +
            "AND sessionId = :sessionId",
    )
    suspend fun deleteTranscript(dashboardId: String, profile: String, sessionId: String)

    /** Keeps the [keep] most recently saved transcripts of a dashboard; the cache does not grow without end. */
    @Query(
        "DELETE FROM cached_transcript WHERE dashboardId = :dashboardId AND sessionId NOT IN " +
            "(SELECT sessionId FROM cached_transcript WHERE dashboardId = :dashboardId " +
            "ORDER BY savedAtMs DESC LIMIT :keep)",
    )
    suspend fun trimTranscripts(dashboardId: String, keep: Int)

    // ---- Waiting messages ----------------------------------------------------------------------

    @Query(
        "SELECT * FROM waiting_message WHERE dashboardId = :dashboardId AND profile = :profile " +
            "AND chatKey = :chatKey",
    )
    suspend fun waiting(dashboardId: String, profile: String, chatKey: String): WaitingMessage?

    @Upsert
    suspend fun saveWaiting(message: WaitingMessage)

    @Query("DELETE FROM waiting_message WHERE dashboardId = :dashboardId AND profile = :profile AND chatKey = :chatKey")
    suspend fun deleteWaiting(dashboardId: String, profile: String, chatKey: String)

    /** The new chats, started on the phone and not stored on Hermes, with a message still to go; newest first. */
    @Query(
        "SELECT * FROM waiting_message WHERE dashboardId = :dashboardId AND profile = :profile " +
            "AND newChat = 1 ORDER BY typedAtMs DESC",
    )
    fun unsentNewChats(dashboardId: String, profile: String): Flow<List<WaitingMessage>>

    // ---- A dashboard removed or signed out of --------------------------------------------------

    @Transaction
    suspend fun forget(dashboardId: String) {
        deleteAllSessions(dashboardId)
        deleteAllTranscripts(dashboardId)
        deleteAllWaiting(dashboardId)
    }

    @Query("DELETE FROM cached_session WHERE dashboardId = :dashboardId")
    suspend fun deleteAllSessions(dashboardId: String)

    @Query("DELETE FROM cached_transcript WHERE dashboardId = :dashboardId")
    suspend fun deleteAllTranscripts(dashboardId: String)

    @Query("DELETE FROM waiting_message WHERE dashboardId = :dashboardId")
    suspend fun deleteAllWaiting(dashboardId: String)
}
