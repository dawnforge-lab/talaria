package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.net.ConnectionFailure

class NewChatRecoveryTest {
    private val gone = ConnectionFailure.NotFound("session not found")

    @Test
    fun `a chat started here that Hermes let go before storing it starts again`() {
        assertTrue(NewChatRecovery.startsAgain(startedHere = true, everStored = false, failure = gone))
    }

    @Test
    fun `a chat with a stored message is never started again`() {
        // Hermes losing it means it was deleted.
        assertFalse(NewChatRecovery.startsAgain(startedHere = true, everStored = true, failure = gone))
    }

    @Test
    fun `a chat opened from the list is never started again`() {
        assertFalse(NewChatRecovery.startsAgain(startedHere = false, everStored = false, failure = gone))
    }

    @Test
    fun `only Hermes saying it has no such chat starts it again`() {
        listOf(
            ConnectionFailure.TimedOut,
            ConnectionFailure.NetworkError("down"),
            ConnectionFailure.UnexpectedResponse(5000, "busy"),
        ).forEach { failure ->
            assertFalse(NewChatRecovery.startsAgain(startedHere = true, everStored = false, failure = failure))
        }
    }
}
