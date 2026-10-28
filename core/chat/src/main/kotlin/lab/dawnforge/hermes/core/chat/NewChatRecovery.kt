package lab.dawnforge.hermes.core.chat

import lab.dawnforge.hermes.core.net.ConnectionFailure

/**
 * A chat started on this phone that Hermes no longer has. Hermes stores a chat only with its first
 * message, and lets an unstored one go when the connection that started it drops (on a phone that
 * cuts an app's network as soon as it leaves the screen, within seconds). A message typed in it,
 * waiting or on its way, would then have nowhere to go: the chat starts again, so the message goes
 * there. iOS leaves such a message waiting until it goes back into the composer.
 *
 * A chat that ever had a message stored is never started again: Hermes losing it means it was
 * deleted, and a new chat would only hide that.
 */
object NewChatRecovery {
    /**
     * Whether the chat starts again: [startedHere] it was started on this phone (not opened from
     * the list), [everStored] some message of it reached Hermes' stored history, and [failure] is
     * why opening it failed.
     */
    fun startsAgain(startedHere: Boolean, everStored: Boolean, failure: ConnectionFailure): Boolean =
        startedHere && !everStored && failure is ConnectionFailure.NotFound
}
