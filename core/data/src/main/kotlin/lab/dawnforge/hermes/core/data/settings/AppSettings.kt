package lab.dawnforge.hermes.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import lab.dawnforge.hermes.core.data.dashboard.DashboardDataOwner
import lab.dawnforge.hermes.core.data.plugin.AnswerVia
import lab.dawnforge.hermes.core.data.plugin.ConfirmAnswer
import lab.dawnforge.hermes.core.data.plugin.ConfirmOutcome
import lab.dawnforge.hermes.core.model.AccentChoice
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.ThemeChoice

/** The phone's own settings, as iOS keeps them on the device. */
data class AppPreferences(
    val theme: ThemeChoice = ThemeChoice.System,
    val accent: AccentChoice = AccentChoice.Wallpaper,
    /** 0 to 4; 2 is the default size. */
    val textSize: Int = DEFAULT_TEXT_SIZE,
    /** A hardware keyboard's Return sends the message; Shift-Return still adds a line. */
    val returnKeySends: Boolean = false,
    val attachmentLimitMb: Int = DEFAULT_ATTACHMENT_MB,
    val haptics: Boolean = true,
    /** A chat reopens at its newest message instead of where it was read. */
    val openAtNewest: Boolean = false,
    /** Whether chats in a project also show in the chat list. */
    val projectChats: ProjectChats = ProjectChats.All,
    /** A chat open in Hermes Desktop or a terminal is taken over when a message is sent here. */
    val takeOverChats: Boolean = true,
) {
    /** The chat text's scale for [textSize], as on iOS. */
    val textScale: Float get() = TEXT_SCALES[textSize.coerceIn(TEXT_SCALES.indices)]

    val attachmentLimitBytes: Long get() = attachmentLimitMb.toLong() * 1024 * 1024

    companion object {
        const val DEFAULT_TEXT_SIZE = 2
        const val DEFAULT_ATTACHMENT_MB = 16
        val TEXT_SCALES = listOf(0.85f, 0.925f, 1f, 1.125f, 1.25f)
        val ATTACHMENT_LIMITS_MB = listOf(8, 16, 32, 64, 128, 256)

        /** Above this, a file is read into memory whole and may slow the phone down. */
        const val LARGE_ATTACHMENT_MB = 16
    }
}

/**
 * Which chats of projects the chat list shows too: none (they show in their project), those active
 * today or yesterday, or all. Pinned ones always show.
 */
enum class ProjectChats { Hidden, Recent, All }

/** What the model picker leaves out, per dashboard. Hermes still offers them; the phone hides them. */
data class ModelVisibility(val hiddenProviders: Set<String> = emptySet(), val hiddenModels: Set<String> = emptySet()) {
    fun shows(provider: String): Boolean = provider !in hiddenProviders

    fun shows(provider: String, model: String): Boolean = shows(provider) && modelKey(provider, model) !in hiddenModels

    companion object {
        /** A model's key: its provider and id, with a separator neither can contain. */
        fun modelKey(provider: String, model: String) = "$provider\u001F$model"
    }
}

/** The phone's own settings, and the model picker's hidden providers and models. */
class AppSettings(private val settings: DataStore<Preferences>) : DashboardDataOwner {
    val preferences: Flow<AppPreferences> = settings.data.map { it.read() }.distinctUntilChanged()

    suspend fun update(change: (AppPreferences) -> AppPreferences) {
        settings.edit { prefs ->
            val current = prefs.read()
            val next = change(current)
            prefs[THEME] = next.theme.name
            prefs[ACCENT] = next.accent.name
            prefs[TEXT_SIZE] = next.textSize.coerceIn(AppPreferences.TEXT_SCALES.indices)
            prefs[RETURN_SENDS] = next.returnKeySends
            prefs[ATTACHMENT_MB] = next.attachmentLimitMb
            prefs[HAPTICS] = next.haptics
            prefs[OPEN_AT_NEWEST] = next.openAtNewest
            prefs[PROJECT_CHATS] = next.projectChats.name
            prefs[TAKE_OVER] = next.takeOverChats
        }
    }

    fun modelVisibility(id: DashboardId): Flow<ModelVisibility> = settings.data.map {
        ModelVisibility(it[hiddenProvidersKey(id)].orEmpty(), it[hiddenModelsKey(id)].orEmpty())
    }.distinctUntilChanged()

    suspend fun setModelVisibility(id: DashboardId, visibility: ModelVisibility) {
        settings.edit {
            it[hiddenProvidersKey(id)] = visibility.hiddenProviders.filter(String::isNotBlank).toSet()
            it[hiddenModelsKey(id)] = visibility.hiddenModels.filter(String::isNotBlank).toSet()
        }
    }

    /** The task board last shown for the dashboard; null follows Hermes' current one. */
    fun board(id: DashboardId): Flow<String?> = settings.data.map { it[boardKey(id)] }.distinctUntilChanged()

    suspend fun setBoard(id: DashboardId, slug: String?) {
        settings.edit { if (slug == null) it.remove(boardKey(id)) else it[boardKey(id)] = slug }
    }

    /**
     * The jobs pinned to the top of [id]'s scheduled jobs, as profile and job id, in the order they
     * were pinned. Pins stay on the phone: Hermes has none.
     */
    fun pinnedJobs(id: DashboardId): Flow<List<Pair<String, String>>> = settings.data.map { prefs ->
        prefs[pinsKey(id)].orEmpty().lineSequence().mapNotNull { line ->
            val (profile, job) = line.split(FIELD).takeIf { it.size == 2 } ?: return@mapNotNull null
            profile to job
        }.toList()
    }.distinctUntilChanged()

    /** Pins [jobId] last, or unpins it. */
    suspend fun setJobPinned(id: DashboardId, profile: String, jobId: String, pinned: Boolean) {
        settings.edit { prefs ->
            val kept = prefs[pinsKey(id)].orEmpty().lineSequence()
                .filter { it.isNotEmpty() && it != listOf(profile, jobId).joinToString(FIELD) }
                .toList()
            val next = if (pinned) kept + listOf(profile, jobId).joinToString(FIELD) else kept
            if (next.isEmpty()) prefs.remove(pinsKey(id)) else prefs[pinsKey(id)] = next.joinToString("\n")
        }
    }

    private fun pinsKey(id: DashboardId) = stringPreferencesKey("cron.pinned.${id.value}")

    /** Whether a call from Hermes rings like a phone call; off, a notification comes instead. On by default, as on iOS. */
    val ringsLikeCall: Flow<Boolean> = settings.data.map { it[RINGS_LIKE_CALL] ?: true }.distinctUntilChanged()

    suspend fun setRingsLikeCall(rings: Boolean) {
        settings.edit { it[RINGS_LIKE_CALL] = rings }
    }

    /** Whether the artifacts list shows files no longer on the host. */
    val showMissingArtifacts: Flow<Boolean> = settings.data.map { it[SHOW_MISSING] ?: false }.distinctUntilChanged()

    suspend fun setShowMissingArtifacts(show: Boolean) {
        settings.edit { it[SHOW_MISSING] = show }
    }

    /**
     * Names this phone to the plugins of every host it uses (confirmations, settings Hermes may
     * change); made once. It doesn't authenticate the phone: the dashboard's sign-in does.
     */
    suspend fun installationId(): String {
        var id: String? = null
        settings.edit { prefs ->
            id = prefs[INSTALLATION_ID] ?: UUID.randomUUID().toString().also { prefs[INSTALLATION_ID] = it }
        }
        return checkNotNull(id)
    }

    /** Answers to the confirmations of [id]'s [profile] that its host hasn't heard yet, oldest first. */
    suspend fun confirmAnswers(id: DashboardId, profile: String): List<ConfirmAnswer> =
        readAnswers(settings.data.first()[answersKey(id)].orEmpty()).filter { it.first == profile }.map { it.second }

    /** Keeps [answer] until the host hears it; a newer answer to the same prompt replaces it. */
    suspend fun addConfirmAnswer(id: DashboardId, profile: String, answer: ConfirmAnswer) {
        settings.edit { prefs ->
            val kept = readAnswers(prefs[answersKey(id)].orEmpty())
                .filterNot { it.first == profile && it.second.id == answer.id }
            prefs[answersKey(id)] = writeAnswers((kept + (profile to answer)).takeLast(MAX_ANSWERS))
        }
    }

    /** The host heard these. */
    suspend fun dropConfirmAnswers(id: DashboardId, profile: String, ids: Set<String>) {
        settings.edit { prefs ->
            val kept = readAnswers(prefs[answersKey(id)].orEmpty())
                .filterNot { it.first == profile && it.second.id in ids }
            if (kept.isEmpty()) prefs.remove(answersKey(id)) else prefs[answersKey(id)] = writeAnswers(kept)
        }
    }

    /** Outcomes of Hermes' setting changes for [id]'s [profile] its host hasn't heard: id, status, note. */
    suspend fun settingResults(id: DashboardId, profile: String): List<Triple<String, String, String>> =
        settings.data.first()[settingResultsKey(id)].orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(FIELD)
            if (parts.size != SETTING_RESULT_FIELDS ||
                parts[0] != profile
            ) {
                null
            } else {
                Triple(parts[1], parts[2], parts[3])
            }
        }.toList()

    suspend fun addSettingResult(id: DashboardId, profile: String, requestId: String, status: String, note: String) {
        settings.edit { prefs ->
            val kept = prefs[settingResultsKey(id)].orEmpty().lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith(profile + FIELD + requestId + FIELD) }
                .toList()
            val line = listOf(
                profile,
                requestId,
                status,
                note.replace('\n', ' ').replace(FIELD, " "),
            ).joinToString(FIELD)
            prefs[settingResultsKey(id)] = (kept + line).takeLast(MAX_ANSWERS).joinToString("\n")
        }
    }

    suspend fun dropSettingResults(id: DashboardId, profile: String, ids: Set<String>) {
        settings.edit { prefs ->
            val kept = prefs[settingResultsKey(id)].orEmpty().lineSequence().filter { line ->
                val parts = line.split(FIELD)
                line.isNotEmpty() && !(parts.getOrNull(0) == profile && parts.getOrNull(1) in ids)
            }.toList()
            if (kept.isEmpty()) {
                prefs.remove(settingResultsKey(id))
            } else {
                prefs[settingResultsKey(id)] =
                    kept.joinToString("\n")
            }
        }
    }

    private fun settingResultsKey(id: DashboardId) = stringPreferencesKey("hermes_changes.results.${id.value}")

    /** Signing out of a dashboard, or removing it, forgets what its picker hides, its board and unsent answers. */
    override suspend fun forget(id: DashboardId) {
        settings.edit {
            it.remove(hiddenProvidersKey(id))
            it.remove(hiddenModelsKey(id))
            it.remove(boardKey(id))
            it.remove(answersKey(id))
            it.remove(pinsKey(id))
            it.remove(settingResultsKey(id))
        }
    }

    // One answer per line: profile, prompt id and outcome, split by a character none of them has.
    private fun answersKey(id: DashboardId) = stringPreferencesKey("present.answers.${id.value}")

    private fun readAnswers(stored: String): List<Pair<String, ConfirmAnswer>> =
        stored.lineSequence().mapNotNull { line ->
            val parts = line.split(FIELD)
            val outcome = ConfirmOutcome.entries.firstOrNull { it.key == parts.getOrNull(2) }
            // An answer kept before answers said how they were given was a tap.
            val via = AnswerVia.entries.firstOrNull { it.key == parts.getOrNull(ANSWER_FIELDS) } ?: AnswerVia.Tap
            if (parts.size !in ANSWER_FIELDS..ANSWER_FIELDS + 1 || outcome == null) {
                null
            } else {
                parts[0] to ConfirmAnswer(parts[1], outcome, via)
            }
        }.toList()

    private fun writeAnswers(answers: List<Pair<String, ConfirmAnswer>>): String =
        answers.joinToString("\n") { (profile, answer) ->
            listOf(profile, answer.id, answer.outcome.key, answer.via.key).joinToString(FIELD)
        }

    private fun boardKey(id: DashboardId) = stringPreferencesKey("tasks.board.${id.value}")

    private fun Preferences.read() = AppPreferences(
        theme = this[THEME]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.System,
        accent = this[ACCENT]?.let { runCatching { AccentChoice.valueOf(it) }.getOrNull() } ?: AccentChoice.Wallpaper,
        textSize = (this[TEXT_SIZE] ?: AppPreferences.DEFAULT_TEXT_SIZE).coerceIn(AppPreferences.TEXT_SCALES.indices),
        returnKeySends = this[RETURN_SENDS] ?: false,
        attachmentLimitMb = this[ATTACHMENT_MB]?.takeIf { it in AppPreferences.ATTACHMENT_LIMITS_MB }
            ?: AppPreferences.DEFAULT_ATTACHMENT_MB,
        haptics = this[HAPTICS] ?: true,
        openAtNewest = this[OPEN_AT_NEWEST] ?: false,
        projectChats =
            this[PROJECT_CHATS]?.let { runCatching { ProjectChats.valueOf(it) }.getOrNull() } ?: ProjectChats.All,
        takeOverChats = this[TAKE_OVER] ?: true,
    )

    private fun hiddenProvidersKey(id: DashboardId) =
        stringSetPreferencesKey("model_picker.hidden_providers.${id.value}")

    private fun hiddenModelsKey(id: DashboardId) = stringSetPreferencesKey("model_picker.hidden_models.${id.value}")

    private companion object {
        val THEME = stringPreferencesKey("app.theme")
        val ACCENT = stringPreferencesKey("app.accent")
        val TEXT_SIZE = intPreferencesKey("app.text_size")
        val RETURN_SENDS = booleanPreferencesKey("app.return_key_sends")
        val ATTACHMENT_MB = intPreferencesKey("app.attachment_limit_mb")
        val HAPTICS = booleanPreferencesKey("app.haptics")
        val OPEN_AT_NEWEST = booleanPreferencesKey("app.open_at_newest")
        val RINGS_LIKE_CALL = booleanPreferencesKey("calls.rings_like_call")
        val SHOW_MISSING = booleanPreferencesKey("artifacts.show_missing")
        val PROJECT_CHATS = stringPreferencesKey("app.project_chats")
        val TAKE_OVER = booleanPreferencesKey("app.take_over_chats")
        val INSTALLATION_ID = stringPreferencesKey("app.installation_id")
        const val FIELD = "\u001F"
        const val ANSWER_FIELDS = 3
        const val SETTING_RESULT_FIELDS = 4

        /** As many as the host takes in one exchange. */
        const val MAX_ANSWERS = 40
    }
}
