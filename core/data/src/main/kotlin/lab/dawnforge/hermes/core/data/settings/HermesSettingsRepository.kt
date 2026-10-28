package lab.dawnforge.hermes.core.data.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.chat.ModelChoices
import lab.dawnforge.hermes.core.data.session.DashboardRequests
import lab.dawnforge.hermes.core.data.session.map
import lab.dawnforge.hermes.core.gateway.GatewayClient
import lab.dawnforge.hermes.core.gateway.GatewayClosedException
import lab.dawnforge.hermes.core.gateway.RpcException
import lab.dawnforge.hermes.core.gateway.RpcTimeoutException
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult

/** The profile's default model, as its settings show it. */
data class ModelDefaults(val provider: String, val model: String, val reasoning: String)

/** The providers and models a profile can use, and its default. */
data class ModelCatalog(val choices: ModelChoices, val defaults: ModelDefaults) {
    /**
     * The row [key] names (by slug, name or alias), else the one Hermes marks current, else the
     * only one listing [model]. Null when none fits: the saved provider is then shown as it is.
     */
    fun providerFor(key: String, model: String): ModelChoices.Provider? =
        choices.providers.firstOrNull { key.isNotBlank() && it.named(key) }
            ?: choices.providers.firstOrNull { it.current }
            ?: choices.providers.filter { p -> p.models.any { it.id == model } }.singleOrNull()
}

/**
 * How Hermes answers spoken turns: [provider] and [model] (empty: the main model), and how hard it
 * thinks. A reasoning model with reasoning "none" may think out loud in its answer.
 */
data class VoiceReplies(val provider: String, val model: String, val reasoning: String) {
    companion object {
        val REASONING = listOf("none", "minimal", "low", "medium", "high")
    }
}

/** How setting the default model went. */
sealed interface ModelSet {
    data object Done : ModelSet

    /** Hermes asks before an expensive model; sending again with the confirmation sets it. */
    data class Confirm(val message: String) : ModelSet
}

/** Choices Hermes lists for the memory and context settings (`/api/dashboard/plugins/hub`). */
data class ProviderOptions(
    /** Memory providers that are set up and ready. */
    val memory: List<String> = emptyList(),
    val contextEngines: List<String> = emptyList(),
)

/**
 * A profile's Hermes settings, as the desktop's settings pages read and write them. A save sends
 * only the key that changed: Hermes deep-merges it into the profile's config file.
 */
class HermesSettingsRepository(
    private val requests: DashboardRequests,
    /** The active dashboard's live connection, if any. */
    private val gateway: () -> GatewayClient?,
) {
    /** The profile's whole config, Hermes' defaults filled in. */
    suspend fun config(id: DashboardId, profile: String): NetResult<JsonObject> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, CONFIG, query = mapOf("profile" to profile)).map { it as? JsonObject ?: EMPTY }
    }

    suspend fun schema(id: DashboardId, profile: String): NetResult<ConfigSchema> = requests.run(id) { url, jar, cf ->
        get(url, jar, cf, "/api/config/schema", query = mapOf("profile" to profile)).map(ConfigSchema::parse)
    }

    /**
     * Sets [key] to [value]. The memory provider and context engine go through their own routes,
     * which check the choice is installed and ready.
     */
    suspend fun save(id: DashboardId, profile: String, key: String, value: JsonElement): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val query = mapOf("profile" to profile)
            when (key) {
                MEMORY_PROVIDER -> put(
                    url,
                    jar,
                    cf,
                    "/api/memory/provider",
                    emptyList(),
                    buildJsonObject { put("provider", value) },
                    query,
                )

                CONTEXT_ENGINE -> put(
                    url,
                    jar,
                    cf,
                    "/api/dashboard/plugin-providers",
                    emptyList(),
                    buildJsonObject { put("context_engine", value) },
                    query,
                )

                else -> put(
                    url,
                    jar,
                    cf,
                    CONFIG,
                    emptyList(),
                    buildJsonObject { put("config", configPatch(key, value)) },
                    query,
                )
            }.map { }
        }

    /** Sets every key of [values] in one save; none of them may be one with its own route. */
    suspend fun saveAll(id: DashboardId, profile: String, values: Map<String, JsonElement>): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            put(
                url,
                jar,
                cf,
                CONFIG,
                emptyList(),
                buildJsonObject { put("config", configPatch(values)) },
                mapOf("profile" to profile),
            ).map { }
        }

    /** The models the profile can pick from, and its current default. */
    suspend fun models(id: DashboardId, profile: String, config: JsonObject): NetResult<ModelCatalog> =
        requests.run(id) { url, jar, cf ->
            val query = mapOf("profile" to profile)
            val options = when (
                val result = get(url, jar, cf, "/api/model/options", query = query + ("explicit_only" to "true"))
            ) {
                is NetResult.Success -> result.value as? JsonObject ?: EMPTY
                is NetResult.Failure -> return@run result
            }
            // Only for the provider: the config's `model` reaches the phone as the bare name.
            val info = (get(url, jar, cf, "/api/model/info", query = query) as? NetResult.Success)?.value as? JsonObject
            val model = listOf(config.text("model"), info?.text("model"), options.text("model")).firstNotNullOfOrNull {
                it
            }
            val provider = listOf(info?.text("provider"), options.text("provider")).firstNotNullOfOrNull { it }
            NetResult.Success(
                ModelCatalog(
                    ModelChoices.parse(options),
                    ModelDefaults(provider.orEmpty(), model.orEmpty(), defaultReasoning(config)),
                ),
            )
        }

    /**
     * Makes [model] of [provider] the profile's default for new chats (`POST /api/model/set`), which
     * checks the provider can serve it. [confirmExpensive] answers Hermes' question about its cost.
     */
    suspend fun setDefaultModel(
        id: DashboardId,
        profile: String,
        provider: String,
        model: String,
        confirmExpensive: Boolean = false,
    ): NetResult<ModelSet> = requests.run(id) { url, jar, cf ->
        val body = buildJsonObject {
            put("scope", "main")
            put("provider", provider)
            put("model", model)
            put("profile", profile)
            if (confirmExpensive) put("confirm_expensive_model", true)
        }
        post(url, jar, cf, "/api/model/set", body, mapOf("profile" to profile)).map { reply ->
            val answer = reply as? JsonObject
            if ((answer?.get("confirm_required") as? JsonPrimitive)?.booleanOrNull == true) {
                ModelSet.Confirm(answer.text("confirm_message").orEmpty())
            } else {
                ModelSet.Done
            }
        }
    }

    /**
     * The model Hermes answers spoken turns with (the `voice_chat` task, `GET /api/model/auxiliary`):
     * an empty provider or model follows the main one.
     */
    suspend fun voiceReplies(id: DashboardId, profile: String): NetResult<VoiceReplies> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/model/auxiliary", query = mapOf("profile" to profile)).map { reply ->
                val task = ((reply as? JsonObject)?.get("tasks") as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .firstOrNull { it.text("task") == VOICE_TASK }
                VoiceReplies(
                    provider = task?.text("provider")?.takeUnless { it == AUTO }.orEmpty(),
                    model = task?.text("model").orEmpty(),
                    reasoning = task?.text("reasoning_effort") ?: DEFAULT_VOICE_REASONING,
                )
            }
        }

    /** Sets the model and reasoning of spoken turns; an empty provider follows the main model. */
    suspend fun setVoiceReplies(id: DashboardId, profile: String, replies: VoiceReplies): NetResult<Unit> =
        requests.run(id) { url, jar, cf ->
            val body = buildJsonObject {
                put("scope", "auxiliary")
                put("task", VOICE_TASK)
                put("provider", replies.provider.ifEmpty { AUTO })
                put("model", if (replies.provider.isEmpty()) "" else replies.model)
                put("reasoning_effort", replies.reasoning)
                put("profile", profile)
            }
            post(url, jar, cf, "/api/model/set", body, mapOf("profile" to profile)).map { }
        }

    suspend fun providerOptions(id: DashboardId, profile: String): NetResult<ProviderOptions> =
        requests.run(id) { url, jar, cf ->
            get(url, jar, cf, "/api/dashboard/plugins/hub", query = mapOf("profile" to profile)).map { hub ->
                val providers = (hub as? JsonObject)?.get("providers") as? JsonObject
                fun names(key: String, ready: Boolean) = (providers?.get(key) as? JsonArray).orEmpty().mapNotNull {
                    val row = it as? JsonObject ?: return@mapNotNull null
                    if (ready && row.text("status") != "ready") return@mapNotNull null
                    row.text("name")
                }.sorted()
                ProviderOptions(names("memory_options", ready = true), names("context_options", ready = false))
            }
        }

    /** Whether new delegate agents are paused (`delegation.status`); the pause covers the whole Hermes. */
    suspend fun delegationPaused(): NetResult<Boolean> = gatewayCall { client ->
        val result = client.call("delegation.status", JsonObject(emptyMap())) as? JsonObject
        paused(result)
    }

    suspend fun pauseDelegation(paused: Boolean): NetResult<Boolean> = gatewayCall { client ->
        val result = client.call("delegation.pause", buildJsonObject { put("paused", paused) }) as? JsonObject
        paused(result)
    }

    private fun paused(result: JsonObject?): NetResult<Boolean> =
        (result?.get("paused") as? JsonPrimitive)?.booleanOrNull?.let { NetResult.Success(it) }
            ?: NetResult.Failure(ConnectionFailure.UnexpectedResponse(null, "no paused state"))

    private inline fun <T> gatewayCall(block: (GatewayClient) -> NetResult<T>): NetResult<T> {
        val client = gateway() ?: return NetResult.Failure(NOT_CONNECTED)
        return try {
            block(client)
        } catch (e: RpcException) {
            NetResult.Failure(ConnectionFailure.UnexpectedResponse(e.code, e.message))
        } catch (_: GatewayClosedException) {
            NetResult.Failure(NOT_CONNECTED)
        } catch (_: RpcTimeoutException) {
            NetResult.Failure(ConnectionFailure.TimedOut)
        }
    }

    companion object {
        private const val VOICE_TASK = "voice_chat"
        private const val AUTO = "auto"
        private const val DEFAULT_VOICE_REASONING = "none"
        const val MEMORY_PROVIDER = "memory.provider"
        const val CONTEXT_ENGINE = "context.engine"
        private const val CONFIG = "/api/config"
        private val EMPTY = JsonObject(emptyMap())
        private val NOT_CONNECTED = ConnectionFailure.NetworkError("The live connection is not open")
    }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
