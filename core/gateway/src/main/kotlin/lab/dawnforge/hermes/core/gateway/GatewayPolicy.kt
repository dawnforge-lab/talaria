package lab.dawnforge.hermes.core.gateway

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Timings of the gateway connection. The defaults follow Hermes' own clients: a `gateway.ping`
 * after 15 s of silence and the socket given up after 45 s (any frame proves the socket alive),
 * and the iOS app's reconnect backoff: doubling from 1 s, capped at 8 s in the foreground and 60 s in
 * the background.
 */
data class GatewayPolicy(
    /** Opening the socket and receiving `gateway.ready`. */
    val openTimeoutMs: Long = 15_000,
    val callTimeoutMs: Long = 30_000,
    val keepaliveAfterMs: Long = 15_000,
    val deadAfterMs: Long = 45_000,
    val backoffBaseMs: Long = 1_000,
    val foregroundBackoffCapMs: Long = 8_000,
    val backgroundBackoffCapMs: Long = 60_000,
    val minimumRetryDelayMs: Long = 500,
    /** A connection that lasted this long resets the backoff. */
    val stableAfterMs: Long = 10_000,
) {
    /** How often the keepalive looks at the socket. */
    internal val keepaliveCheckMs: Long get() = max(50, min(keepaliveAfterMs, deadAfterMs) / 4)

    /**
     * The wait before reconnect attempt [attempt] (1 for the first retry). In the foreground the
     * wait stays in the upper half of the ceiling, so retries come steadily; in the background it
     * spreads over the whole range.
     */
    fun retryDelayMs(attempt: Int, foreground: Boolean, random: Random = Random.Default): Long {
        val cap = if (foreground) foregroundBackoffCapMs else backgroundBackoffCapMs
        val exponent = (attempt - 1).coerceIn(0, 30)
        val ceiling = min(cap, backoffBaseMs shl exponent).coerceAtLeast(1)
        val delay = if (foreground) ceiling / 2 + random.nextLong(ceiling / 2 + 1) else random.nextLong(ceiling + 1)
        return max(minimumRetryDelayMs, delay)
    }
}
