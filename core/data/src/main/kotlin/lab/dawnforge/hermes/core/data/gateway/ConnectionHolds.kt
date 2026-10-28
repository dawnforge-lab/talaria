package lab.dawnforge.hermes.core.data.gateway

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A count of holds: [held] while any is held. Each release counts once, however often it is called. */
open class HoldCount {
    private val lock = Any()
    private var count = 0
    private val state = MutableStateFlow(false)

    /** Whether anything holds it now. */
    val held: StateFlow<Boolean> = state.asStateFlow()

    /** Holds until the returned release is called; releasing twice is harmless. */
    fun hold(): () -> Unit {
        synchronized(lock) {
            count++
            state.value = true
        }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    count--
                    state.value = count > 0
                }
            }
        }
    }
}

/**
 * What keeps the live connection open while no screen of the app shows: a voice conversation
 * (which a call from Hermes starts too) talks to Hermes out of sight, and [FinishWorkService]
 * finishes a send or a reply. [GatewayManager] treats a held connection as one in use, as it does
 * the app in front.
 */
class ConnectionHolds : HoldCount()
