package lab.dawnforge.hermes.core.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The model a chat's next turn uses, as `session.info` reports it. */
data class ChatModel(
    val model: String,
    val provider: String?,
    /** `""` is the provider's default, `"none"` thinking off. */
    val reasoning: String,
) {
    companion object {
        fun from(sessionInfo: JsonObject): ChatModel? {
            val model = sessionInfo.text("model") ?: return null
            return ChatModel(model, sessionInfo.text("provider"), sessionInfo.text("reasoning_effort").orEmpty())
        }
    }
}

/** The reasoning levels Hermes takes; models don't say which they support, and Hermes clamps the level itself. */
val REASONING_LEVELS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** What `model.options` offers: the providers this Hermes can reach, and their models. */
data class ModelChoices(val providers: List<Provider>) {
    data class Provider(
        val slug: String,
        val name: String,
        val models: List<Model>,
        /** Other names Hermes takes for it in config. */
        val aliases: List<String> = emptyList(),
        /** The provider the profile's default model uses. */
        val current: Boolean = false,
    ) {
        /** True when [key] (a slug, name or alias, in any case) names this provider. */
        fun named(key: String): Boolean = key.equals(slug, ignoreCase = true) || key.equals(name, ignoreCase = true) ||
            aliases.any { it.equals(key, ignoreCase = true) }
    }

    data class Model(
        val id: String,
        /** False when the model can't think, so the reasoning levels don't apply. */
        val reasons: Boolean,
        /** False when thinking can't be turned off for it ("none" is not offered). */
        val canDisableReasoning: Boolean,
        /** True when the model has a fast (priority) tier. */
        val fast: Boolean = false,
    )

    fun model(provider: String?, id: String): Model? =
        providers.firstOrNull { it.slug == provider }?.models?.firstOrNull { it.id == id }
            ?: providers.firstNotNullOfOrNull { p -> p.models.firstOrNull { it.id == id } }

    companion object {
        /** Providers the user can use (signed in), with at least one model. */
        fun parse(options: JsonObject): ModelChoices {
            val providers = (options["providers"] as? JsonArray).orEmpty().mapNotNull { row ->
                val provider = row as? JsonObject ?: return@mapNotNull null
                val slug = provider.text("slug") ?: return@mapNotNull null
                if (provider.flag("authenticated") == false) return@mapNotNull null
                val capabilities = provider["capabilities"] as? JsonObject
                val models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { entry ->
                    val id = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                    val caps = capabilities?.get(id) as? JsonObject
                    Model(
                        id = id,
                        reasons = caps?.flag("reasoning") ?: true,
                        canDisableReasoning = caps?.flag("can_disable_reasoning") ?: true,
                        fast = caps?.flag("fast") == true,
                    )
                }
                val aliases = (provider["aliases"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonPrimitive)?.takeIf { alias -> alias.isString }?.content
                }
                if (models.isEmpty()) {
                    null
                } else {
                    Provider(slug, provider.text("name") ?: slug, models, aliases, provider.flag("is_current") == true)
                }
            }
            return ModelChoices(providers)
        }
    }
}

/** A non-blank string, trimmed. */
private fun JsonObject.text(key: String) = string(key)?.trim()?.takeIf(String::isNotEmpty)
