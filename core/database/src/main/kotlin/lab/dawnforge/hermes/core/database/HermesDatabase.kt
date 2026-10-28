package lab.dawnforge.hermes.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * What the app keeps of the dashboards' chats so they open with no network. Mostly a cache: Hermes
 * has the real copy. A waiting message has none, so each version migrates rather than drop it.
 *
 * Version 2: a waiting message knows the new chat it belongs to, and whether its reply was lost.
 */
@Database(
    entities = [CachedSession::class, CachedTranscript::class, WaitingMessage::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        fun create(context: Context): HermesDatabase =
            Room.databaseBuilder(context, HermesDatabase::class.java, "hermes-cache.db")
                // Only for a version with no migration (going back to an older app, say): the
                // cache can be loaded again.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
