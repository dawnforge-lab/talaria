package lab.dawnforge.hermes.core.push.relay

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

/**
 * The Talaria relay's device API: registering this phone's FCM token, proving it with the nonce
 * the relay pushes to it, and creating pairing codes for a Hermes profile to claim. The relay only ever sees routing data; notification content is sealed.
 */
class RelayClient(private val client: OkHttpClient) {

    /** Asks the relay to push a one-time nonce to [token]; it arrives as a data message. */
    suspend fun requestChallenge(relay: HttpUrl, token: String, packageName: String) {
        send(relay, "v1/token-challenges", "POST", registrationBody(token, packageName))
    }

    suspend fun register(relay: HttpUrl, token: String, packageName: String, proof: String): Registration {
        val reply = send(relay, "v1/installations", "POST", registrationBody(token, packageName, proof))
        val id = reply.objectAt("installation")?.text("id")
        val credential = reply.text("credential")
        if (id == null ||
            credential == null
        ) {
            throw RelayException.Unexpected("The relay's registration answer was incomplete")
        }
        return Registration(id, credential)
    }

    /** Moves the installation to a new FCM token, proven like a registration. */
    suspend fun updateToken(relay: HttpUrl, installation: RelayInstallation, token: String, proof: String) {
        val body = buildJsonObject {
            put("device_token", token)
            put("token_proof", proof)
        }
        send(relay, "v1/installations/${installation.id}", "PUT", body, installation.credential)
    }

    /** Ends the installation: every host paired with it is turned away from then on. */
    suspend fun delete(relay: HttpUrl, installation: RelayInstallation) {
        send(relay, "v1/installations/${installation.id}", "DELETE", body = null, credential = installation.credential)
    }

    /** A one-time code a Hermes profile claims to send this phone notifications, bound to [dashboardId]. */
    suspend fun createPairing(relay: HttpUrl, installation: RelayInstallation, dashboardId: String): String {
        val body = buildJsonObject { put("dashboard_id", dashboardId) }
        val reply = send(relay, "v1/installations/${installation.id}/pairings", "POST", body, installation.credential)
        return reply.text("pairing_code") ?: throw RelayException.Unexpected("The relay sent no pairing code")
    }

    /**
     * Answers a question the plugin parked at the relay ([requestId]). [answer] is sealed for the
     * plugin; [gatewayId] names the host, as two can park the same id. The relay refuses an answer
     * to a question already answered (409), handed back to Hermes (410) or gone (404).
     */
    suspend fun respondDecision(
        relay: HttpUrl,
        installation: RelayInstallation,
        requestId: String,
        answer: String,
        questionId: String,
        gatewayId: String,
    ) {
        val body = buildJsonObject {
            put("answer", answer)
            if (questionId.isNotEmpty()) put("question_id", questionId)
            put("gateway_id", gatewayId)
        }
        // The request id is the plugin's; added as a segment, so it is encoded whatever it holds.
        val url = relay.newBuilder().addPathSegments(
            "v1/decisions",
        ).addPathSegment(requestId).addPathSegment("respond").build()
        send(url, "POST", body, installation.credential)
    }

    /**
     * Follows a turn this phone just sent: the relay pushes its progress for [sessionIds] (live and
     * stored ids, six at most) as data messages naming [activity], a UUID this phone made.
     */
    suspend fun registerLiveTurn(
        relay: HttpUrl,
        installation: RelayInstallation,
        activity: String,
        sessionIds: List<String>,
    ) {
        val body = buildJsonObject {
            put("push_token", activity)
            put("session_ids", JsonArray(sessionIds.map(::JsonPrimitive)))
        }
        send(relay, "v1/installations/${installation.id}/live-activities", "PUT", body, installation.credential)
    }

    private fun registrationBody(token: String, packageName: String, proof: String? = null) = buildJsonObject {
        put("platform", "fcm")
        put("bundle_id", packageName)
        put("device_token", token)
        if (proof != null) put("token_proof", proof)
    }

    private suspend fun send(
        relay: HttpUrl,
        path: String,
        method: String,
        body: JsonObject?,
        credential: String? = null,
    ): JsonObject {
        val url = relay.resolve(path) ?: throw RelayException.Unexpected("Not a relay address")
        return send(url, method, body, credential)
    }

    private suspend fun send(url: HttpUrl, method: String, body: JsonObject?, credential: String?): JsonObject {
        val request = Request.Builder()
            .url(url)
            .method(method, body?.toString()?.toRequestBody(JSON))
            .apply { if (credential != null) header("Authorization", "Bearer $credential") }
            .build()
        val (code, text) = try {
            val response = client.newCall(request).executeAsync()
            // The body may still be on its way: read off the caller's (perhaps main) thread.
            withContext(Dispatchers.IO) { response.use { it.code to it.body.string() } }
        } catch (e: IOException) {
            throw RelayException.Unreachable(e.message)
        }
        val json = text.takeIf(String::isNotBlank)?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        if (code in 200..299) return json ?: JsonObject(emptyMap())
        val error = json?.text("error")
        throw when {
            code == 401 -> RelayException.Unauthorized()
            code == 429 -> RelayException.RateLimited()
            error == "platform_unavailable" -> RelayException.NoAndroidSupport()
            error == "invalid_topic" -> RelayException.AppNotAllowed()
            error == "invalid_device_token" -> RelayException.TokenRefused()
            error == "token_proof_invalid" || error == "token_proof_required" -> RelayException.ProofRefused()
            else -> RelayException.Failed(code, error)
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.objectAt(key: String) = this[key] as? JsonObject

    data class Registration(val installationId: String, val credential: String)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The relay's base URL, or null unless it is an `https://` address. */
        fun relayUrl(raw: String): HttpUrl? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            // Paths resolve against it, so it must end with a slash.
            val path = url.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
            return url.newBuilder().encodedPath(path).build()
        }
    }
}

/** This phone's registration at the relay. [credential] is a secret, stored encrypted. */
data class RelayInstallation(val id: String, val credential: String)

sealed class RelayException(message: String) : Exception(message) {
    class Unreachable(detail: String?) :
        RelayException("The push relay could not be reached${detail?.let { ": $it" } ?: ""}")

    class Unauthorized : RelayException("The push relay no longer knows this phone")

    class RateLimited : RelayException("The push relay asked to slow down; try again in a minute")

    class NoAndroidSupport : RelayException("This push relay can't send to Android phones")

    class AppNotAllowed : RelayException("This push relay doesn't accept this app")

    class TokenRefused : RelayException("Firebase refused this phone's push token")

    class ProofRefused : RelayException("The push relay's check of this phone failed; try again")

    class Failed(val status: Int, val error: String?) : RelayException("The push relay refused (${error ?: status})")

    class Unexpected(detail: String) : RelayException(detail)
}
