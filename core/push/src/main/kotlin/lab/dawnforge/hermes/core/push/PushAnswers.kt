package lab.dawnforge.hermes.core.push

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.push.e2e.E2EException
import lab.dawnforge.hermes.core.push.e2e.NotificationE2E
import lab.dawnforge.hermes.core.push.relay.RelayClient
import lab.dawnforge.hermes.core.push.relay.RelayException
import okhttp3.HttpUrl

/** How answering from a notification went. */
sealed interface AnswerOutcome {
    data object Answered : AnswerOutcome

    /** Someone answered first (the desktop, another device), or it timed out: nothing to do. */
    data object AlreadySettled : AnswerOutcome

    /** It didn't reach Hermes; worth another try while the question still waits. */
    data class Failed(val reason: String?) : AnswerOutcome
}

/** Makes Hermes' gateway call for an approval answer; GatewayManager.callOnce in the app. */
fun interface GatewayCall {
    suspend fun call(dashboardId: DashboardId, profile: String, method: String, params: JsonObject): Any?
}

/**
 * Answers an approval or a question from its notification, without the app on screen.
 *
 * - An approval goes to the dashboard's gateway as `approval.respond`, by the chat's stored id;
 *   Hermes finds the live session from it. `resolved: 0` means nothing was waiting any more.
 * - A question's answer is sealed with the pairing's answer key and handed to the relay, where the
 *   plugin collects it.
 */
class PushAnswers(
    private val store: PushStore,
    private val relay: RelayClient,
    private val gateway: GatewayCall,
    private val relayUrl: suspend () -> HttpUrl?,
) {
    suspend fun approve(dashboardId: DashboardId, profile: String, sessionKey: String, choice: String): AnswerOutcome {
        val params = buildJsonObject {
            put("session_id", sessionKey)
            put("choice", choice)
        }
        val reply = try {
            gateway.call(dashboardId, profile, "approval.respond", params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreachable, signed out, or Hermes refused: the caller may try again.
            return AnswerOutcome.Failed(e.message)
        }
        val resolved = ((reply as? JsonObject)?.get("resolved") as? JsonPrimitive)?.intOrNull ?: 0
        return if (resolved > 0) AnswerOutcome.Answered else AnswerOutcome.AlreadySettled
    }

    suspend fun answer(gatewayId: String, requestId: String, questionId: String, text: String): AnswerOutcome {
        val pairing =
            store.pairing(gatewayId) ?: return AnswerOutcome.Failed("This phone is no longer paired with that Hermes")
        val secret = store.secret(gatewayId) ?: return AnswerOutcome.Failed("The pairing's key is gone")
        val installation =
            store.installation() ?: return AnswerOutcome.Failed("This phone is no longer registered for push")
        val url = relayUrl() ?: return AnswerOutcome.Failed("No push relay is set")
        val sealed = try {
            NotificationE2E.sealAnswer(
                answer = text,
                keys = NotificationE2E.deriveKeys(pairing.kid, secret),
                installationId = installation.id,
                gatewayId = gatewayId,
                requestId = requestId,
                questionId = questionId,
            )
        } catch (e: E2EException) {
            return AnswerOutcome.Failed(e.message)
        }
        return try {
            relay.respondDecision(url, installation, requestId, sealed, questionId, gatewayId)
            AnswerOutcome.Answered
        } catch (e: RelayException.Failed) {
            // 409: answered elsewhere; 410: handed back to Hermes; 404: gone.
            if (e.status in SETTLED) AnswerOutcome.AlreadySettled else AnswerOutcome.Failed(e.message)
        } catch (e: RelayException) {
            AnswerOutcome.Failed(e.message)
        }
    }

    private companion object {
        val SETTLED = setOf(404, 409, 410)
    }
}
