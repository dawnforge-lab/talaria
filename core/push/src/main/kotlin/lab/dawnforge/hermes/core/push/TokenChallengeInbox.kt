package lab.dawnforge.hermes.core.push

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/**
 * Hands the relay's one-time nonce from [HermesMessagingService] to the registration waiting for
 * it. The nonce proves to the relay that this phone holds the FCM token it registers.
 */
class TokenChallengeInbox {
    private val nonces = Channel<String>(Channel.CONFLATED)

    fun offer(nonce: String) {
        nonces.trySend(nonce)
    }

    /** Runs [request] (which asks the relay to push a nonce) and waits for that nonce to arrive. */
    suspend fun await(timeoutMs: Long = WAIT_MS, request: suspend () -> Unit): String {
        // A nonce left from an earlier attempt would be stale.
        var stale = nonces.tryReceive()
        while (stale.isSuccess) stale = nonces.tryReceive()
        request()
        return withTimeout(timeoutMs) { nonces.receive() }
    }

    companion object {
        /** FCM delivers a high-priority message in seconds; this leaves room for a slow network. */
        const val WAIT_MS = 30_000L
    }
}
