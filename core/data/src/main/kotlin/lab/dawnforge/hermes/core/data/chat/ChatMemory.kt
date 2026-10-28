package lab.dawnforge.hermes.core.data.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * What each chat had in its composer, and where it was being read, kept while the app runs: a
 * draft survives leaving the chat, as on the iOS app. Neither is written to disk.
 */
class ChatMemory {
    /** A message being written: its text, and the reply it quotes. */
    data class Draft(val text: String, val replyTo: String?)

    private val drafts = lru<Draft>(MAX_DRAFTS)
    private val places = lru<String>(MAX_PLACES)

    @Synchronized
    fun draft(chat: String): Draft? = drafts[chat]

    /** Saves [draft]; an empty one is forgotten. */
    @Synchronized
    fun saveDraft(chat: String, draft: Draft) {
        if (draft.text.isBlank() && draft.replyTo == null) drafts.remove(chat) else drafts[chat] = draft
    }

    private val prefills = MutableSharedFlow<String>(extraBufferCapacity = MAX_PREFILLS)

    /** Chats whose draft was written from outside them; an open chat takes its new draft. */
    val prefilled: SharedFlow<String> = prefills.asSharedFlow()

    /** Puts [text] in [chat]'s composer, whether it is open now or opens later. */
    fun prefill(chat: String, text: String) {
        saveDraft(chat, Draft(text, replyTo = null))
        prefills.tryEmit(chat)
    }

    /** A chat known by its live id that is now stored: what it had moves to its stored id. */
    @Synchronized
    fun move(from: String, to: String) {
        drafts.remove(from)?.let { drafts[to] = it }
        places.remove(from)?.let { places[to] = it }
    }

    /** The key of the row the chat was last read at, or null when it was read at its newest. */
    @Synchronized
    fun place(chat: String): String? = places[chat]

    @Synchronized
    fun savePlace(chat: String, rowKey: String?) {
        if (rowKey == null) places.remove(chat) else places[chat] = rowKey
    }

    private fun <T> lru(capacity: Int) = object : LinkedHashMap<String, T>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > capacity
    }

    private companion object {
        const val MAX_DRAFTS = 12
        const val MAX_PREFILLS = 4
        const val MAX_PLACES = 50
        const val LOAD_FACTOR = 0.75f
    }
}
