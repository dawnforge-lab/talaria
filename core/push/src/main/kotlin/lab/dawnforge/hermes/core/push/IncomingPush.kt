package lab.dawnforge.hermes.core.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.chat.PlainText
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.calls.HermesCall
import lab.dawnforge.hermes.core.push.e2e.E2EException
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E

/** The channel a Hermes event is shown in; each is tuned separately in Android's settings. */
enum class NotificationKind {
    /** Approvals and questions: Hermes is waiting on you. */
    NeedsYou,
    Reply,
    Failure,
    BackgroundJob,

    /** Hermes phones the user: it rings like a call where it can. */
    Call,
}

/** What the phone shows for one push. */
data class HermesNotification(
    val kind: NotificationKind,
    /** The Hermes event type, which picks the generic copy for a missing [title] or [body]. */
    val type: String,
    val title: String?,
    val body: String?,
    /** Where a tap leads; null when the push did not verify, so it leads nowhere but the app. */
    val target: Target?,
    /** Groups one chat's notifications, so a newer one replaces the older. */
    val thread: String,
    /** What can be answered from the notification itself; only a push that verified carries one. */
    val action: NotificationAction? = null,
    /** The call Hermes placed, for a verified `call.requested`. */
    val call: HermesCall? = null,
) {
    data class Target(val dashboardId: DashboardId, val profile: String, val sessionId: String?)
}

/** An approval or question the notification can answer without opening the app. */
sealed interface NotificationAction {
    /**
     * Answered on the dashboard's gateway with `approval.respond`, by the chat's stored id
     * ([sessionKey]). The plugin only offers once and deny; "always" stays in the app.
     */
    data class Approval(val sessionKey: String, val choices: List<String>) : NotificationAction

    /**
     * One question, answered through the relay with the answer sealed for the plugin. [questionId]
     * is the question's qid in a batch of one, empty for a single question; [choices] may be
     * empty, and any typed text is an answer.
     */
    data class Question(
        val gatewayId: String,
        val requestId: String,
        val questionId: String,
        val question: String,
        val choices: List<String>,
    ) : NotificationAction
}

/** A pairing as the reader needs it: where it leads, its keys, and what it notifies about. */
class PairingKeys(
    val dashboardId: DashboardId,
    val profile: String,
    val keys: NotificationE2E.Keys,
    val preferences: PushStore.NotifyPreferences = PushStore.NotifyPreferences(),
)

sealed interface IncomingPush {
    /** The relay's nonce for a registration waiting on [TokenChallengeInbox]. */
    data class Challenge(val nonce: String) : IncomingPush

    data class Show(val notification: HermesNotification, val messageId: String?) : IncomingPush

    /** Not for this phone (an unknown or forgotten pairing), a type Android has no use for, or stale. */
    data object Ignore : IncomingPush
}

/** Hermes asking for the phone's location now: where it asks from, and the request to answer. */
data class LocateRequest(val dashboardId: DashboardId, val profile: String, val locateId: String)

/**
 * Reads a data message from the relay. Every pairing this app makes has an end-to-end key, so the
 * content and the chat a tap opens come only from an envelope that verifies; a push that doesn't
 * (or arrives in plaintext for a keyed pairing) shows the generic copy and leads nowhere.
 */
object IncomingPushReader {
    /** Older pushes are dropped: the phone was off long enough that they no longer help. */
    const val MAX_AGE_SECONDS = 3L * 24 * 60 * 60

    /** Hosts' clocks drift; anything further ahead than this is not believed. */
    const val MAX_FUTURE_SECONDS = 10L * 60
    private const val CHALLENGE_KEY = "talaria_token_challenge"
    private const val CONFIRM_TYPE = "phone.changes"
    private const val CALL_TYPE = "call.requested"
    private const val LOCATE_TYPE = "location.requested"

    /** The host waits 20 seconds for an answer; a request older than a minute is past it. */
    private const val LOCATE_MAX_AGE_SECONDS = 60L

    /** The host's `^l_[A-Za-z0-9_-]{16,64}$`. */
    private val LOCATE_ID = Regex("^l_[A-Za-z0-9_-]{16,64}$")
    private val APPROVAL_CHOICES = setOf("once", "deny")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(
        data: Map<String, String>,
        installationId: String?,
        nowSeconds: Long,
        pairing: suspend (gatewayId: String) -> PairingKeys?,
    ): IncomingPush {
        data[CHALLENGE_KEY]?.let { return IncomingPush.Challenge(it) }
        val routing = data["talaria"]?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return IncomingPush.Ignore
        val type = routing.text("type") ?: return IncomingPush.Ignore
        val kind = kindOf(type, routing.text("mode")) ?: return IncomingPush.Ignore
        val gatewayId = routing.text("gateway_id") ?: return IncomingPush.Ignore
        val keys = pairing(gatewayId) ?: return IncomingPush.Ignore
        // The user turned this kind off for this profile.
        if (type in keys.preferences.muted) return IncomingPush.Ignore

        fun unverified(messageId: String?) = IncomingPush.Show(
            HermesNotification(
                kind,
                type,
                title = null,
                body = null,
                target = null,
                thread = "gw:$gatewayId:unverified",
            ),
            messageId,
        )

        val raw = data["talaria_e2e"] ?: return unverified(messageId = null)
        if (installationId == null) return unverified(messageId = null)
        val envelope = try {
            NotificationE2E.parseEnvelope(raw)
        } catch (_: E2EException) {
            return unverified(messageId = null)
        }
        val inner = try {
            NotificationE2E.open(envelope, type, keys.keys, installationId, gatewayId)
        } catch (_: E2EException) {
            return unverified(envelope.msg)
        }
        // The issue time is bound to the ciphertext, so it can be trusted once it verified.
        if (envelope.iat < nowSeconds - MAX_AGE_SECONDS || envelope.iat > nowSeconds + MAX_FUTURE_SECONDS) {
            return IncomingPush.Ignore
        }
        val sessionId = inner.text("stored_session_id") ?: inner.text("session_id")
        val target = HermesNotification.Target(keys.dashboardId, inner.text("profile") ?: keys.profile, sessionId)
        return IncomingPush.Show(
            HermesNotification(
                kind = kind,
                type = type,
                title = inner.text("title").takeUnless { keys.preferences.hideText },
                // A confirmation's body is the first line of its Markdown preview.
                body = inner.text("body")
                    ?.let { if (type == CONFIRM_TYPE) PlainText.of(it) else it }
                    .takeUnless { keys.preferences.hideText },
                target = target,
                thread = "gw:$gatewayId:${sessionId ?: envelope.tok}",
                action = (inner["decision"] as? JsonObject)
                    ?.let { actionFor(it, type, gatewayId, envelope.req) }
                    ?.let { if (keys.preferences.hideText) it.withoutText() else it },
                call = if (type == CALL_TYPE) HermesCall.parse(inner["call"] as? JsonObject, envelope.iat) else null,
            ),
            envelope.msg,
        )
    }

    /**
     * Hermes asking for the location now (`location.requested`), as iOS's locate check: only from
     * an envelope that verifies and is fresh, with a request id the host would take. Anything else
     * (plaintext, another type, a maps lookup this phone doesn't answer) is null.
     */
    suspend fun readLocate(
        data: Map<String, String>,
        installationId: String?,
        nowSeconds: Long,
        pairing: suspend (gatewayId: String) -> PairingKeys?,
    ): LocateRequest? {
        val routing = data["talaria"]?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return null
        if (routing.text("type") != LOCATE_TYPE || installationId == null) return null
        val gatewayId = routing.text("gateway_id") ?: return null
        val keys = pairing(gatewayId) ?: return null
        val raw = data["talaria_e2e"] ?: return null
        val inner = try {
            val envelope = NotificationE2E.parseEnvelope(raw)
            // A locate is answered within seconds or not at all.
            if (envelope.iat < nowSeconds - LOCATE_MAX_AGE_SECONDS || envelope.iat > nowSeconds + MAX_FUTURE_SECONDS) {
                return null
            }
            NotificationE2E.open(envelope, LOCATE_TYPE, keys.keys, installationId, gatewayId)
        } catch (_: E2EException) {
            return null
        }
        val locateId = (inner["locate"] as? JsonObject)?.text("id")?.takeIf { LOCATE_ID.matches(it) } ?: return null
        return LocateRequest(keys.dashboardId, inner.text("profile") ?: keys.profile, locateId)
    }

    /**
     * The answerable part of a verified decision. A question's request id must be the one bound to
     * the ciphertext (`req`), which is the id the relay routes the answer by. Batches of several
     * questions and multiple-choice questions are answered in the app.
     */
    private fun actionFor(
        decision: JsonObject,
        type: String,
        gatewayId: String,
        boundRequest: String,
    ): NotificationAction? = when (decision.text("kind")) {
        "approval" -> {
            val sessionKey = decision.text("session_key")
            val choices = decision.strings("choices").filter { it in APPROVAL_CHOICES }
            if (type == "approval.needed" && sessionKey != null && "deny" in choices) {
                NotificationAction.Approval(sessionKey, choices)
            } else {
                null
            }
        }

        "clarify" -> {
            val requestId = decision.text("request_id")
            val questions = (decision["questions"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            val single = questions?.singleOrNull()
            when {
                type != "input.needed" || requestId == null || requestId != boundRequest -> null

                questions == null -> decision.text("question")?.let { question ->
                    NotificationAction.Question(gatewayId, requestId, "", question, decision.strings("choices"))
                }

                single == null || single.bool("multi_select") -> null

                else -> {
                    val qid = single.text("qid")
                    val question = single.text("question")
                    if (qid == null || question == null) {
                        null
                    } else {
                        NotificationAction.Question(gatewayId, requestId, qid, question, single.strings("choices"))
                    }
                }
            }
        }

        else -> null
    }

    /** With text hidden, a question keeps its reply field but shows neither its wording nor its choices. */
    private fun NotificationAction.withoutText(): NotificationAction = when (this) {
        is NotificationAction.Approval -> this
        is NotificationAction.Question -> copy(question = "", choices = emptyList())
    }

    /**
     * [mode] is how a `phone.changes` wakes the phone. The relay sends those that wake it in the
     * background another way and doesn't pass the mode on, so one without is an alert: a
     * confirmation Hermes waits on.
     */
    fun kindOf(type: String, mode: String? = null): NotificationKind? = when (type) {
        "approval.needed", "input.needed" -> NotificationKind.NeedsYou

        // The rest of the phone's changes (calendars, reminders) wake an iPhone in the background.
        "phone.changes" -> if (mode == "background") null else NotificationKind.NeedsYou

        "response.ready" -> NotificationKind.Reply

        "turn.failed" -> NotificationKind.Failure

        "background_task.finished" -> NotificationKind.BackgroundJob

        "call.requested" -> NotificationKind.Call

        // Calendar changes and location requests are iPhone features so far.
        else -> null
    }

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull {
        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    }.orEmpty()

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.text(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
}
