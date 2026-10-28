package lab.dawnforge.hermes.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal val gatewayJson = Json { ignoreUnknownKeys = true }

/** A JSON-RPC error from the gateway, e.g. `4001 session not found` or `-32601 unknown method`. */
class RpcException(val code: Int, override val message: String, val data: JsonElement? = null) :
    Exception("RPC error $code: $message") {
    /** `data.reason`, which Hermes uses for codes such as 4090 (`SESSION_NOT_OWNED`). */
    val reason: String? get() = ((data as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull
}

/** The connection closed before the call was answered, or was never open. */
class GatewayClosedException(message: String) : Exception(message)

/** An event from the gateway (`{"method":"event","params":{type, session_id, payload, seq}}`). */
data class GatewayEvent(
    val type: String,
    /** The runtime session id; null for session-less broadcasts such as `sessions.changed`. */
    val sessionId: String?,
    val payload: JsonObject?,
    /** Per-session sequence number; null for session-less events. */
    val seq: Long?,
    /** True when delivered by `session.events.since` after a reconnect, rather than live. */
    val replayed: Boolean = false,
)

/** A request the gateway sends to the client: approvals, clarify questions, sudo, secrets… */
data class ServerRequest(
    /** `srq-…`; the answer must carry it back. */
    val id: String,
    val method: String,
    val params: JsonObject,
    /** True when re-delivered from a snapshot's `open_requests` after a reconnect. */
    val replayed: Boolean = false,
) {
    val sessionId: String? get() = (params["session_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
}

/** One frame from the gateway, classified. */
internal sealed interface Inbound {
    data class Response(val id: String, val result: JsonElement?, val error: RpcException?) : Inbound

    data class Event(val event: GatewayEvent) : Inbound

    data class Request(val request: ServerRequest) : Inbound

    /** Anything else (a notification we do not know, malformed JSON). */
    data object Ignored : Inbound

    companion object {
        fun parse(text: String): Inbound {
            val obj = runCatching { gatewayJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Ignored
            val method = (obj["method"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            return when {
                method == "event" -> parseEvent(obj["params"] as? JsonObject) ?: Ignored

                method != null && id != null -> Request(
                    ServerRequest(id, method, obj["params"] as? JsonObject ?: empty),
                )

                method == null && id != null && ("result" in obj || "error" in obj) ->
                    Response(id, obj["result"], (obj["error"] as? JsonObject)?.let(::parseError))

                else -> Ignored
            }
        }

        private fun parseEvent(params: JsonObject?): Event? {
            params ?: return null
            val type = (params["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            return Event(
                GatewayEvent(
                    type = type,
                    sessionId = (params["session_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty),
                    payload = params["payload"] as? JsonObject,
                    seq = (params["seq"] as? JsonPrimitive)?.longOrNull,
                ),
            )
        }

        private fun parseError(error: JsonObject) = RpcException(
            code = (error["code"] as? JsonPrimitive)?.intOrNull ?: 0,
            message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            data = error["data"],
        )

        private val empty = JsonObject(emptyMap())
    }
}

internal object Outbound {
    fun request(id: String, method: String, params: JsonObject?): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }.toString()

    fun result(id: String, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    fun error(id: String, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()
}
