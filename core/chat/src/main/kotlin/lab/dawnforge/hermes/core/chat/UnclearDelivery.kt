package lab.dawnforge.hermes.core.chat

import lab.dawnforge.hermes.core.model.ChatItem

/**
 * Where a message stands whose send lost its reply with the connection: Hermes may or may not have
 * it, so it is never sent again blindly. The reconnect's snapshot and the stored history say.
 */
sealed interface UnclearDelivery {
    /** Hermes stored it as [row]; its turn may be over already. */
    data class Stored(val row: ChatItem.User) : UnclearDelivery

    /** Hermes has it: the running turn answers it, or it waits for that turn. */
    data object Received : UnclearDelivery

    /** No sign of it on Hermes: it goes again. */
    data object Lost : UnclearDelivery

    companion object {
        /**
         * [text] is the message as its stored copy reads; [knownUsers] the user rows stored when it
         * was sent, so an earlier message with the same words isn't taken for it; [stored] the user
         * rows stored now; [inflightPrompt] and [queuedPrompt] the snapshot's running and waiting
         * messages, as Hermes received them.
         */
        fun settle(
            text: String,
            knownUsers: Set<String>,
            stored: List<ChatItem.User>,
            inflightPrompt: String?,
            queuedPrompt: String?,
        ): UnclearDelivery {
            stored.firstOrNull { it.key !in knownUsers && it.text == text }?.let { return Stored(it) }
            if (inflightPrompt?.contains(text) == true || queuedPrompt?.contains(text) == true) return Received
            return Lost
        }
    }
}
