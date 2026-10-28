package lab.dawnforge.hermes.core.push.calls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.plugin.HermesCallsRepository

/** Why Hermes calls: a job ended a way, or it waits on the user's OK or answer. */
enum class CallKind(val key: String) {
    Done("done"),
    Failed("failed"),
    Stopped("stopped"),
    Approval("approval"),
    Question("question"),
    ;

    val waitsOnUser: Boolean get() = this == Approval || this == Question
}

/**
 * A call Hermes placed (`call.requested`), as the verified push carries it: its id (for reporting a
 * decline), what it's about, the chat's ids, and when Hermes sent it.
 */
data class HermesCall(
    /** Empty when the host's id isn't one it accepts back. */
    val id: String,
    val kind: CallKind?,
    val title: String?,
    val reason: String?,
    val sessionIds: List<String>,
    /** When the push was sealed, on the host's clock (seconds), bound to the ciphertext. */
    val issuedAt: Long,
) {
    companion object {
        private const val MAX_TITLE = 120
        private const val MAX_REASON = 200
        private const val MAX_SESSIONS = 4

        fun parse(call: JsonObject?, issuedAt: Long): HermesCall? {
            call ?: return null
            fun text(key: String) = (call[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            return HermesCall(
                id = text("id")?.takeIf { HermesCallsRepository.CALL_ID.matches(it) }.orEmpty(),
                kind = CallKind.entries.firstOrNull { it.key == text("kind") },
                title = cleaned(text("title"), MAX_TITLE),
                reason = cleaned(text("reason"), MAX_REASON),
                sessionIds = (call["session_ids"] as? JsonArray).orEmpty()
                    .mapNotNull {
                        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)
                    }
                    .distinct()
                    .take(MAX_SESSIONS),
                issuedAt = issuedAt,
            )
        }

        /** One line, without double quotes (it's quoted when spoken), clipped. */
        fun cleaned(text: String?, limit: Int): String? = text
            ?.replace('"', '\'')
            ?.split(Regex("\\s+"))
            ?.filter(String::isNotEmpty)
            ?.joinToString(" ")
            ?.take(limit)
            ?.takeIf(String::isNotEmpty)
    }
}
