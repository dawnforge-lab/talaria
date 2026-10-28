package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lab.dawnforge.hermes.core.gateway.ServerRequest

/**
 * Something Hermes asks the user in the middle of a turn, and waits on: a command to approve, a
 * question to answer, a password or a secret to type. Built from the gateway's server requests
 * (see docs/hermes-chat-protocol.md); [answer]s go back as the request's result.
 */
sealed interface ChatRequest {
    /** The server request id (`srq-…`) the answer carries back. */
    val id: String
    val request: ServerRequest

    /** A command the agent wants to run. [choices] is what Hermes allows for this one. */
    data class Approval(
        override val request: ServerRequest,
        val command: String,
        val description: String?,
        val toolName: String?,
        val choices: List<ApprovalChoice>,
        /** Hermes' guardian model refused it; the user may still allow it, once. */
        val smartDenied: Boolean,
    ) : ChatRequest {
        override val id get() = request.id

        fun answer(choice: ApprovalChoice): JsonObject = buildJsonObject { put("choice", choice.wire) }
    }

    /** One to five questions, each with up to four choices; the user may also write their own answer. */
    data class Clarify(override val request: ServerRequest, val questions: List<Question>) : ChatRequest {
        override val id get() = request.id

        data class Question(
            val qid: String,
            val text: String,
            val choices: List<String>,
            val multiSelect: Boolean,
            /** An answer already given (from another device), on a request replayed after a reconnect. */
            val answered: String?,
        )

        /**
         * The answers by question id: one string each, or several for a multi-select question
         * (Hermes takes those as a JSON array in text). A question left out is skipped.
         */
        fun answer(answers: Map<String, List<String>>): JsonObject = buildJsonObject {
            putJsonObject("answers") {
                for (question in questions) {
                    val given = answers[question.qid]?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
                    when {
                        given.isEmpty() -> put(question.qid, JsonNull)
                        question.multiSelect -> put(question.qid, JsonArray(given.map(::JsonPrimitive)).toString())
                        else -> put(question.qid, given.first())
                    }
                }
            }
        }

        /** Declines every question; answers already given stay. */
        fun cancel(): JsonObject = JsonObject(emptyMap())
    }

    /** A password for `sudo`, to run [command]. */
    data class Sudo(override val request: ServerRequest, val command: String?) : ChatRequest {
        override val id get() = request.id
    }

    /** A secret for the agent to keep in the profile's environment as [envVar]. */
    data class Secret(override val request: ServerRequest, val envVar: String?, val prompt: String?) : ChatRequest {
        override val id get() = request.id
    }

    companion object {
        /** A one-string answer (sudo, secret): the text, or empty to decline. */
        fun value(text: String): JsonObject = buildJsonObject { put("value", text) }

        /**
         * The card for [request], or null for one this app does not show (password-vault and
         * desktop-window requests); those are declined so another client can answer.
         */
        fun from(request: ServerRequest): ChatRequest? {
            val params = request.params
            return when (request.method) {
                "approval" -> {
                    val smartDenied = params.flag("smart_denied") == true
                    val offered = params.array("choices")
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ApprovalChoice::fromWire) }
                        ?.takeIf(List<ApprovalChoice>::isNotEmpty)
                        ?: listOf(ApprovalChoice.Once, ApprovalChoice.Deny)
                    // Deny is always possible, and stays last.
                    val choices = offered.filter { it != ApprovalChoice.Deny } + ApprovalChoice.Deny
                    Approval(
                        request = request,
                        command = params.string("command").orEmpty(),
                        description = params.string("description")?.trim()?.takeIf(String::isNotEmpty),
                        toolName = params.string("tool_name")?.takeIf(String::isNotBlank),
                        choices = choices,
                        smartDenied = smartDenied,
                    )
                }

                "clarify" -> {
                    val answered = params.obj("answers")
                    val questions = params.array("questions").orEmpty().mapIndexedNotNull { index, element ->
                        val q = element as? JsonObject ?: return@mapIndexedNotNull null
                        val qid = q.string("qid") ?: "q$index"
                        val text =
                            q.string("question")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
                        Clarify.Question(
                            qid = qid,
                            text = text,
                            choices = q.array("choices").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                            multiSelect = q.flag("multi_select") == true,
                            answered = answered?.string(qid),
                        )
                    }
                    // An older Hermes asks a single question as `question` and `choices`.
                    val single = params.string("question")?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
                        listOf(
                            Clarify.Question(
                                qid = "q0",
                                text = text,
                                choices = params.array("choices").orEmpty().mapNotNull {
                                    (it as? JsonPrimitive)?.contentOrNull
                                },
                                multiSelect = false,
                                answered = null,
                            ),
                        )
                    }
                    (questions.ifEmpty { single.orEmpty() }).takeIf(List<Clarify.Question>::isNotEmpty)
                        ?.let { Clarify(request, it) }
                }

                "sudo" -> Sudo(request, params.string("command")?.takeIf(String::isNotBlank))

                "secret" -> Secret(
                    request,
                    envVar = params.string("env_var")?.takeIf(String::isNotBlank),
                    prompt = params.string("prompt")?.trim()?.takeIf(String::isNotEmpty),
                )

                else -> null
            }
        }
    }
}

/** How far an approval reaches. */
enum class ApprovalChoice(internal val wire: String) {
    /** This call only. */
    Once("once"),

    /** Calls like it, for the rest of this session. */
    Session("session"),

    /** Calls like it, from now on (Hermes adds it to its allow list). */
    Always("always"),
    Deny("deny"),
    ;

    internal companion object {
        fun fromWire(value: String): ApprovalChoice? = entries.firstOrNull { it.wire == value }
    }
}
