package lab.dawnforge.hermes.core.data.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Work still on its way between the phone and Hermes: a message being sent, or a reply Hermes is
 * still writing in a chat the app follows. Held by the chat screen. Android cuts a backgrounded
 * app's network within seconds, so leaving the app with work held starts [FinishWorkService] to
 * finish it ([FinishWork]); iOS lets such work go and catches up on return.
 */
class WorkOnItsWay : HoldCount()

/**
 * Starts [start] (the short service) each time the app leaves the screen ([inFront] turns false)
 * with [work] held. That moment is the one Android allows: an app may start a foreground service as
 * it leaves a visible state, not later from the background, so work that begins out of sight isn't
 * covered (a reply then comes as a notification).
 */
class FinishWork(
    private val scope: CoroutineScope,
    private val inFront: StateFlow<Boolean>,
    private val work: StateFlow<Boolean>,
    private val start: () -> Unit,
) {
    fun follow() {
        scope.launch {
            var wasInFront = inFront.value
            inFront.collect { now ->
                if (wasInFront && !now && work.value) start()
                wasInFront = now
            }
        }
    }
}
