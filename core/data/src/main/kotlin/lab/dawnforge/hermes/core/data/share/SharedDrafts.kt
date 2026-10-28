package lab.dawnforge.hermes.core.data.share

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import lab.dawnforge.hermes.core.data.chat.PickedFile

/** What another app shared, for one chat's composer: never sent until the user sends it. */
class SharedDraft(val text: String, val files: List<PickedFile>)

/**
 * Drafts the share sheet hands to the chat it opens, by id, as iOS's share extension hands its
 * drafts to the app. Kept in memory: the sheet opens the chat at once.
 */
@Singleton
class SharedDrafts @Inject constructor() {
    private val drafts = LinkedHashMap<String, SharedDraft>()

    fun put(draft: SharedDraft): String = synchronized(drafts) {
        // A few at most: one per share the user hasn't opened yet.
        while (drafts.size >= MAX_WAITING) drafts.remove(drafts.keys.first())
        UUID.randomUUID().toString().also { drafts[it] = draft }
    }

    fun take(id: String): SharedDraft? = synchronized(drafts) { drafts.remove(id) }

    companion object {
        /** iOS keeps at most 10 drafts waiting. */
        const val MAX_WAITING = 10

        /** iOS takes up to 10 files and 100,000 characters of text, 100 MB in all. */
        const val MAX_FILES = 10
        const val MAX_TEXT = 100_000
        const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    }
}
