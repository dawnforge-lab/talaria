package lab.dawnforge.hermes.feature.settings

import androidx.annotation.StringRes
import lab.dawnforge.hermes.core.data.settings.ConfigSchema
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository

/** How a setting is edited. */
internal sealed interface Control {
    data class Toggle(val default: Boolean) : Control

    /** A switch over a text setting: [on] or [off]. */
    data class TextToggle(val on: String, val off: String, val default: Boolean) : Control

    /** One of [values]; when empty, the choices come from Hermes (its schema, or the page). */
    data class Choice(val values: List<String>, val default: String) : Control

    data class Text(val default: String) : Control

    data class Number(val default: Double) : Control

    /** A list of text, one entry per line. */
    data object Lines : Control

    data object TimeZone : Control
}

/**
 * A setting in a profile's config, at the dotted [key]. One with [needsSchema] is left out when
 * this Hermes' schema doesn't list it (an older Hermes).
 */
internal data class Field(
    val key: String,
    @StringRes val label: Int,
    @StringRes val help: Int,
    val control: Control,
    val needsSchema: Boolean = false,
)

internal data class Card(@StringRes val title: Int, val fields: List<Field>)

/** The Hermes settings pages, as on iOS: each lists its own fields. */
enum class HermesPage(@StringRes internal val title: Int, @StringRes internal val detail: Int) {
    Model(R.string.feature_settings_page_model, R.string.feature_settings_page_model_detail),
    Chat(R.string.feature_settings_page_chat, R.string.feature_settings_page_chat_detail),
    Workspace(R.string.feature_settings_page_workspace, R.string.feature_settings_page_workspace_detail),
    Memory(R.string.feature_settings_page_memory, R.string.feature_settings_page_memory_detail),
    Advanced(R.string.feature_settings_page_advanced, R.string.feature_settings_page_advanced_detail),
    ;

    internal val cards: List<Card> get() = PAGES.getValue(this)
}

/** [cards] as this Hermes has them: fields it lacks left out, then cards left empty. */
internal fun List<Card>.shown(schema: ConfigSchema): List<Card> = mapNotNull { card ->
    card.copy(
        fields = card.fields.filter {
            !it.needsSchema || schema.has(it.key)
        },
    ).takeIf { it.fields.isNotEmpty() }
}

/** Hermes' own personalities; a profile can add more under `agent.personalities`. */
internal val PERSONALITIES = listOf(
    "", "helpful", "concise", "technical", "creative", "teacher", "kawaii", "catgirl", "pirate",
    "shakespeare", "surfer", "noir", "uwu", "philosopher", "hype",
)

/** The levels a delegate agent's reasoning can be set to; blank follows the chat. */
internal val DELEGATE_REASONING = listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra")

private fun field(key: String, label: Int, help: Int, control: Control, needsSchema: Boolean = false) =
    Field(key, label, help, control, needsSchema)

private val PAGES: Map<HermesPage, List<Card>> = mapOf(
    HermesPage.Model to listOf(
        Card(
            R.string.feature_settings_card_speed,
            listOf(
                field(
                    "agent.service_tier",
                    R.string.feature_settings_field_service_tier,
                    R.string.feature_settings_field_service_tier_help,
                    Control.Choice(emptyList(), ""),
                    needsSchema = true,
                ),
                field(
                    "model_context_length",
                    R.string.feature_settings_field_context_length,
                    R.string.feature_settings_field_context_length_help,
                    Control.Number(0.0),
                    needsSchema = true,
                ),
            ),
        ),
    ),
    HermesPage.Chat to listOf(
        Card(
            R.string.feature_settings_card_hermes,
            listOf(
                field(
                    "display.personality",
                    R.string.feature_settings_field_personality,
                    R.string.feature_settings_field_personality_help,
                    Control.Choice(emptyList(), ""),
                ),
                field(
                    "timezone",
                    R.string.feature_settings_field_timezone,
                    R.string.feature_settings_field_timezone_help,
                    Control.TimeZone,
                ),
                field(
                    "display.busy_input_mode",
                    R.string.feature_settings_field_busy,
                    R.string.feature_settings_field_busy_help,
                    Control.Choice(listOf("interrupt", "queue", "steer"), "interrupt"),
                ),
                field(
                    "display.show_reasoning",
                    R.string.feature_settings_field_show_reasoning,
                    R.string.feature_settings_field_show_reasoning_help,
                    Control.Toggle(true),
                ),
                field(
                    "display.tool_progress",
                    R.string.feature_settings_field_tool_progress,
                    R.string.feature_settings_field_tool_progress_help,
                    Control.TextToggle("all", "off", true),
                ),
                field(
                    "display.expand_tools",
                    R.string.feature_settings_field_expand_tools,
                    R.string.feature_settings_field_expand_tools_help,
                    Control.Toggle(false),
                ),
                field(
                    "display.memory_notifications",
                    R.string.feature_settings_field_memory_notifications,
                    R.string.feature_settings_field_memory_notifications_help,
                    Control.Choice(listOf("default", "on", "off"), "default"),
                ),
                field(
                    "agent.image_input_mode",
                    R.string.feature_settings_field_image_input,
                    R.string.feature_settings_field_image_input_help,
                    Control.Choice(listOf("auto", "native", "text"), "auto"),
                ),
                field(
                    "sessions.auto_archive",
                    R.string.feature_settings_field_auto_archive,
                    R.string.feature_settings_field_auto_archive_help,
                    Control.Toggle(false),
                    needsSchema = true,
                ),
                field(
                    "sessions.auto_archive_days",
                    R.string.feature_settings_field_auto_archive_days,
                    R.string.feature_settings_field_auto_archive_days_help,
                    Control.Number(3.0),
                    needsSchema = true,
                ),
            ),
        ),
    ),
    HermesPage.Workspace to listOf(
        Card(
            R.string.feature_settings_card_workspace,
            listOf(
                field(
                    "terminal.cwd",
                    R.string.feature_settings_field_cwd,
                    R.string.feature_settings_field_cwd_help,
                    Control.Text(""),
                ),
                field(
                    "code_execution.mode",
                    R.string.feature_settings_field_code_execution,
                    R.string.feature_settings_field_code_execution_help,
                    Control.Choice(listOf("project", "strict"), "project"),
                ),
                field(
                    "terminal.persistent_shell",
                    R.string.feature_settings_field_persistent_shell,
                    R.string.feature_settings_field_persistent_shell_help,
                    Control.Toggle(true),
                    needsSchema = true,
                ),
                field(
                    "terminal.env_passthrough",
                    R.string.feature_settings_field_env_passthrough,
                    R.string.feature_settings_field_env_passthrough_help,
                    Control.Lines,
                    needsSchema = true,
                ),
                field(
                    "file_read_max_chars",
                    R.string.feature_settings_field_file_read,
                    R.string.feature_settings_field_file_read_help,
                    Control.Number(100_000.0),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_safety,
            listOf(
                field(
                    "approvals.mode",
                    R.string.feature_settings_field_approvals,
                    R.string.feature_settings_field_approvals_help,
                    Control.Choice(listOf("manual", "smart", "off"), "smart"),
                ),
                field(
                    "approvals.timeout",
                    R.string.feature_settings_field_approval_timeout,
                    R.string.feature_settings_field_approval_timeout_help,
                    Control.Number(300.0),
                    needsSchema = true,
                ),
                field(
                    "approvals.mcp_reload_confirm",
                    R.string.feature_settings_field_mcp_reload,
                    R.string.feature_settings_field_mcp_reload_help,
                    Control.Toggle(true),
                    needsSchema = true,
                ),
                field(
                    "command_allowlist",
                    R.string.feature_settings_field_allowlist,
                    R.string.feature_settings_field_allowlist_help,
                    Control.Lines,
                    needsSchema = true,
                ),
                field(
                    "security.redact_secrets",
                    R.string.feature_settings_field_redact,
                    R.string.feature_settings_field_redact_help,
                    Control.Toggle(true),
                ),
                field(
                    "security.allow_private_urls",
                    R.string.feature_settings_field_private_urls,
                    R.string.feature_settings_field_private_urls_help,
                    Control.Toggle(false),
                ),
                field(
                    "checkpoints.enabled",
                    R.string.feature_settings_field_checkpoints,
                    R.string.feature_settings_field_checkpoints_help,
                    Control.Toggle(false),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_browser,
            listOf(
                field(
                    "browser.use_real_profile",
                    R.string.feature_settings_field_real_profile,
                    R.string.feature_settings_field_real_profile_help,
                    Control.Toggle(false),
                    needsSchema = true,
                ),
                field(
                    "browser.allow_private_urls",
                    R.string.feature_settings_field_browser_private,
                    R.string.feature_settings_field_browser_private_help,
                    Control.Toggle(false),
                    needsSchema = true,
                ),
                field(
                    "browser.auto_local_for_private_urls",
                    R.string.feature_settings_field_browser_local,
                    R.string.feature_settings_field_browser_local_help,
                    Control.Toggle(true),
                    needsSchema = true,
                ),
            ),
        ),
    ),
    HermesPage.Memory to listOf(
        Card(
            R.string.feature_settings_card_memory,
            listOf(
                field(
                    HermesSettingsRepository.MEMORY_PROVIDER,
                    R.string.feature_settings_field_memory_provider,
                    R.string.feature_settings_field_memory_provider_help,
                    Control.Choice(emptyList(), ""),
                ),
                field(
                    "memory.memory_enabled",
                    R.string.feature_settings_field_memory_enabled,
                    R.string.feature_settings_field_memory_enabled_help,
                    Control.Toggle(true),
                ),
                field(
                    "memory.user_profile_enabled",
                    R.string.feature_settings_field_user_profile,
                    R.string.feature_settings_field_user_profile_help,
                    Control.Toggle(true),
                ),
                field(
                    "memory.memory_char_limit",
                    R.string.feature_settings_field_memory_limit,
                    R.string.feature_settings_field_memory_limit_help,
                    Control.Number(2200.0),
                    needsSchema = true,
                ),
                field(
                    "memory.user_char_limit",
                    R.string.feature_settings_field_user_limit,
                    R.string.feature_settings_field_user_limit_help,
                    Control.Number(1375.0),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_context,
            listOf(
                field(
                    HermesSettingsRepository.CONTEXT_ENGINE,
                    R.string.feature_settings_field_context_engine,
                    R.string.feature_settings_field_context_engine_help,
                    Control.Choice(emptyList(), "default"),
                ),
                field(
                    "compression.enabled",
                    R.string.feature_settings_field_compression,
                    R.string.feature_settings_field_compression_help,
                    Control.Toggle(true),
                ),
                field(
                    "compression.threshold",
                    R.string.feature_settings_field_compression_threshold,
                    R.string.feature_settings_field_compression_threshold_help,
                    Control.Number(0.8),
                ),
                field(
                    "compression.target_ratio",
                    R.string.feature_settings_field_compression_target,
                    R.string.feature_settings_field_compression_target_help,
                    Control.Number(0.5),
                ),
                field(
                    "compression.protect_last_n",
                    R.string.feature_settings_field_protect_last,
                    R.string.feature_settings_field_protect_last_help,
                    Control.Number(8.0),
                ),
                field(
                    "compression.codex_gpt55_autoraise",
                    R.string.feature_settings_field_codex_autoraise,
                    R.string.feature_settings_field_codex_autoraise_help,
                    Control.Toggle(true),
                    needsSchema = true,
                ),
                field(
                    "auxiliary.compression.timeout",
                    R.string.feature_settings_field_compression_timeout,
                    R.string.feature_settings_field_compression_timeout_help,
                    Control.Number(120.0),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_delegation,
            listOf(
                field(
                    "delegation.max_concurrent_children",
                    R.string.feature_settings_field_max_children,
                    R.string.feature_settings_field_max_children_help,
                    Control.Number(10.0),
                ),
                field(
                    "delegation.max_iterations",
                    R.string.feature_settings_field_delegate_turns,
                    R.string.feature_settings_field_delegate_turns_help,
                    Control.Number(250.0),
                    needsSchema = true,
                ),
                field(
                    "delegation.child_timeout_seconds",
                    R.string.feature_settings_field_delegate_timeout,
                    R.string.feature_settings_field_delegate_timeout_help,
                    Control.Number(0.0),
                    needsSchema = true,
                ),
            ),
        ),
    ),
    HermesPage.Advanced to listOf(
        Card(
            R.string.feature_settings_card_runtime,
            listOf(
                field(
                    "agent.max_turns",
                    R.string.feature_settings_field_max_turns,
                    R.string.feature_settings_field_max_turns_help,
                    Control.Number(0.0),
                    needsSchema = true,
                ),
                field(
                    "agent.api_max_retries",
                    R.string.feature_settings_field_retries,
                    R.string.feature_settings_field_retries_help,
                    Control.Number(3.0),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_terminal,
            listOf(
                field(
                    "terminal.timeout",
                    R.string.feature_settings_field_command_timeout,
                    R.string.feature_settings_field_command_timeout_help,
                    Control.Number(180.0),
                    needsSchema = true,
                ),
                field(
                    "terminal.docker_image",
                    R.string.feature_settings_field_docker_image,
                    R.string.feature_settings_field_docker_image_help,
                    Control.Text(""),
                    needsSchema = true,
                ),
                field(
                    "terminal.singularity_image",
                    R.string.feature_settings_field_singularity_image,
                    R.string.feature_settings_field_singularity_image_help,
                    Control.Text(""),
                    needsSchema = true,
                ),
                field(
                    "terminal.modal_image",
                    R.string.feature_settings_field_modal_image,
                    R.string.feature_settings_field_modal_image_help,
                    Control.Text(""),
                    needsSchema = true,
                ),
                field(
                    "terminal.daytona_image",
                    R.string.feature_settings_field_daytona_image,
                    R.string.feature_settings_field_daytona_image_help,
                    Control.Text(""),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_output,
            listOf(
                field(
                    "tool_output.max_bytes",
                    R.string.feature_settings_field_output_bytes,
                    R.string.feature_settings_field_output_bytes_help,
                    Control.Number(50_000.0),
                    needsSchema = true,
                ),
                field(
                    "tool_output.max_lines",
                    R.string.feature_settings_field_output_lines,
                    R.string.feature_settings_field_output_lines_help,
                    Control.Number(2000.0),
                    needsSchema = true,
                ),
                field(
                    "tool_output.max_line_length",
                    R.string.feature_settings_field_line_length,
                    R.string.feature_settings_field_line_length_help,
                    Control.Number(2000.0),
                    needsSchema = true,
                ),
                field(
                    "checkpoints.max_snapshots",
                    R.string.feature_settings_field_snapshots,
                    R.string.feature_settings_field_snapshots_help,
                    Control.Number(20.0),
                    needsSchema = true,
                ),
            ),
        ),
        Card(
            R.string.feature_settings_card_updates,
            listOf(
                field(
                    "updates.non_interactive_local_changes",
                    R.string.feature_settings_field_local_changes,
                    R.string.feature_settings_field_local_changes_help,
                    Control.Choice(listOf("stash", "discard"), "stash"),
                    needsSchema = true,
                ),
            ),
        ),
    ),
)

/** The label of [value] for [key], when it has one of its own; null shows the value as it is. */
@StringRes
internal fun optionLabel(key: String, value: String): Int? = when (key) {
    "approvals.mode" -> mapOf(
        "manual" to R.string.feature_settings_option_manual,
        "smart" to R.string.feature_settings_option_smart,
        "off" to R.string.feature_settings_option_yolo,
    )[value]

    "code_execution.mode" -> mapOf(
        "project" to R.string.feature_settings_option_project,
        "strict" to R.string.feature_settings_option_strict,
    )[value]

    "agent.image_input_mode" -> mapOf(
        "auto" to R.string.feature_settings_option_auto,
        "native" to R.string.feature_settings_option_native,
        "text" to R.string.feature_settings_option_text,
    )[value]

    "agent.service_tier" -> mapOf(
        "" to R.string.feature_settings_default_value,
        "normal" to R.string.feature_settings_option_tier_off,
        "fast" to R.string.feature_settings_option_tier_fast,
        "auto" to R.string.feature_settings_option_tier_auto,
        "cold" to R.string.feature_settings_option_tier_cold,
    )[value]

    "display.memory_notifications" -> mapOf(
        "default" to R.string.feature_settings_memory_notifications_default,
        "on" to R.string.feature_settings_memory_notifications_on,
        "off" to R.string.feature_settings_memory_notifications_off,
    )[value]

    "display.busy_input_mode" -> mapOf(
        "interrupt" to R.string.feature_settings_option_interrupt,
        "queue" to R.string.feature_settings_option_queue,
        "steer" to R.string.feature_settings_option_steer,
    )[value]

    "updates.non_interactive_local_changes" -> mapOf(
        "stash" to R.string.feature_settings_option_stash,
        "discard" to R.string.feature_settings_option_discard,
    )[value]

    HermesSettingsRepository.MEMORY_PROVIDER -> R.string.feature_settings_memory_builtin.takeIf { value.isEmpty() }

    "timezone" -> R.string.feature_settings_timezone_server.takeIf { value.isEmpty() }

    else -> R.string.feature_settings_default_value.takeIf { value.isEmpty() }
}
