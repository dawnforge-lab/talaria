package lab.dawnforge.hermes.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each version keeps what the one before had: a waiting message has no copy on Hermes. */
@RunWith(AndroidJUnit4::class)
class HermesDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HermesDatabase::class.java)

    @Test
    fun aWaitingMessageSurvivesTheMoveToVersion2() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO waiting_message (dashboardId, profile, chatKey, text, typedAtMs) " +
                    "VALUES ('d1', 'default', 's1', 'Sends when back online', 5)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 2, true).use { db ->
            db.query("SELECT text, typedAtMs, newChat, reservedId, cwd, unclear FROM waiting_message").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("Sends when back online", row.getString(0))
                assertEquals(5L, row.getLong(1))
                // An old message was in a listed chat, and its reply wasn't lost.
                assertEquals(0, row.getInt(2))
                assertTrue(row.isNull(3))
                assertTrue(row.isNull(4))
                assertEquals(0, row.getInt(5))
            }
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
