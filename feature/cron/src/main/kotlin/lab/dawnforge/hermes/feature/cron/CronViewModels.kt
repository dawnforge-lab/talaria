package lab.dawnforge.hermes.feature.cron

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.data.cron.CronRepository
import lab.dawnforge.hermes.core.data.cron.CronTrigger
import lab.dawnforge.hermes.core.data.cron.ScheduleChoice
import lab.dawnforge.hermes.core.data.dashboard.DashboardRepository
import lab.dawnforge.hermes.core.data.plugin.PushPluginRepository
import lab.dawnforge.hermes.core.data.session.ChatListSettings
import lab.dawnforge.hermes.core.data.settings.AppSettings
import lab.dawnforge.hermes.core.data.settings.HermesSettingsRepository
import lab.dawnforge.hermes.core.model.BlueprintField
import lab.dawnforge.hermes.core.model.BlueprintFieldType
import lab.dawnforge.hermes.core.model.CronBlueprint
import lab.dawnforge.hermes.core.model.CronDraft
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronRun
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.model.DashboardId
import lab.dawnforge.hermes.core.model.DeliveryTarget
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import lab.dawnforge.hermes.feature.cron.navigation.CronBlueprintRoute
import lab.dawnforge.hermes.feature.cron.navigation.CronBlueprintsRoute
import lab.dawnforge.hermes.feature.cron.navigation.CronEditRoute
import lab.dawnforge.hermes.feature.cron.navigation.CronJobRoute

/** The profile's time zone, named when it isn't the phone's: cron times are read in it. */
private suspend fun HermesSettingsRepository.zoneLabel(id: DashboardId, profile: String): String? {
    val config = (config(id, profile) as? NetResult.Success)?.value ?: return null
    val zone = (config["timezone"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val profileZone = try {
        ZoneId.of(zone)
    } catch (_: DateTimeException) {
        return zone
    }
    return zone.takeIf { profileZone.normalized() != ZoneId.systemDefault().normalized() }
}

/** What happened to the last thing asked of a job, shown once. */
sealed interface CronJobMessage {
    data class Triggered(val outcome: CronTrigger) : CronJobMessage

    data class Failed(val failure: ConnectionFailure) : CronJobMessage

    data class NotifyFailed(val failure: ConnectionFailure) : CronJobMessage
}

data class CronJobsUiState(
    val profile: String? = null,
    /** Pinned jobs in the order they were pinned; [jobs] are the rest. */
    val pinned: List<CronJob> = emptyList(),
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Why the list couldn't be loaded. */
    val failure: ConnectionFailure? = null,
    /** Jobs being paused, resumed, run or deleted. */
    val working: Set<String> = emptySet(),
    /** Jobs whose runs don't notify; null when the push plugin doesn't keep that. */
    val muted: Set<String>? = null,
    val zoneLabel: String? = null,
    val confirmDelete: CronJob? = null,
    val message: CronJobMessage? = null,
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && jobs.isEmpty()
}

/** The active profile's scheduled jobs, pinned ones first, as on iOS. */
@HiltViewModel
class CronJobsViewModel @Inject constructor(
    private val dashboards: DashboardRepository,
    private val cron: CronRepository,
    private val plugin: PushPluginRepository,
    private val settings: AppSettings,
    private val hermes: HermesSettingsRepository,
    private val chatList: ChatListSettings,
) : ViewModel() {
    private val _state = MutableStateFlow(CronJobsUiState())
    val state: StateFlow<CronJobsUiState> = _state.asStateFlow()

    private var all: List<CronJob> = emptyList()
    private var pins: List<Pair<String, String>> = emptyList()

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            settings.pinnedJobs(id).collect {
                pins = it
                arrange()
            }
        }
    }

    /** Loads the list; the screen calls it each time it shows, so edits made elsewhere appear. */
    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            val profile = dashboards.activeProfile(id).first()
            _state.update { it.copy(profile = profile, refreshing = !it.loading) }
            when (val result = cron.jobs(id, profile)) {
                is NetResult.Success -> {
                    all = result.value.sortedWith(JOB_ORDER)
                    _state.update { it.copy(loading = false, refreshing = false, failure = null) }
                    arrange()
                }

                is NetResult.Failure -> _state.update {
                    it.copy(loading = false, refreshing = false, failure = result.failure)
                }
            }
            // A plugin without the route (or none) leaves every job notifying, unmarked.
            val muted = (plugin.mutedJobs(id, profile) as? NetResult.Success)?.value
            val zone = hermes.zoneLabel(id, profile)
            _state.update { it.copy(muted = muted, zoneLabel = zone) }
        }
    }

    private fun arrange() {
        val profile = _state.value.profile ?: return
        val byId = all.associateBy { it.id }
        val pinned = pins.filter { it.first == profile }.mapNotNull { byId[it.second] }
        val pinnedIds = pinned.mapTo(HashSet()) { it.id }
        _state.update { it.copy(pinned = pinned, jobs = all.filterNot { job -> job.id in pinnedIds }) }
    }

    fun setPinned(job: CronJob, pinned: Boolean) {
        val profile = _state.value.profile ?: return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            settings.setJobPinned(id, profile, job.id, pinned)
        }
    }

    fun setPaused(job: CronJob, paused: Boolean) = act(job) { id, profile ->
        val result = if (paused) cron.pause(id, profile, job.id) else cron.resume(id, profile, job.id)
        when (result) {
            is NetResult.Success -> replace(result.value ?: job)
            is NetResult.Failure -> _state.update { it.copy(message = CronJobMessage.Failed(result.failure)) }
        }
    }

    fun runNow(job: CronJob) = act(job) { id, profile ->
        val message = when (val result = cron.trigger(id, profile, job.id)) {
            is NetResult.Success -> CronJobMessage.Triggered(result.value)
            is NetResult.Failure -> CronJobMessage.Failed(result.failure)
        }
        _state.update { it.copy(message = message) }
        (cron.job(id, profile, job.id) as? NetResult.Success)?.value?.let(::replace)
    }

    fun askDelete(job: CronJob) = _state.update { it.copy(confirmDelete = job) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = null) }

    fun delete() {
        val job = _state.value.confirmDelete ?: return
        _state.update { it.copy(confirmDelete = null) }
        act(job) { id, profile ->
            when (val result = cron.delete(id, profile, job.id)) {
                is NetResult.Success -> {
                    // Its pin and its muted mark go with it.
                    settings.setJobPinned(id, profile, job.id, pinned = false)
                    if (_state.value.muted?.contains(job.id) == true) plugin.setJobNotifies(id, profile, job.id, true)
                    all = all.filterNot { it.id == job.id }
                    arrange()
                }

                is NetResult.Failure -> _state.update { it.copy(message = CronJobMessage.Failed(result.failure)) }
            }
        }
    }

    /** The chat list, showing only the runs of scheduled jobs. */
    fun showRunsInChats() {
        viewModelScope.launch { chatList.setFilters(setOf(SCHEDULED_FILTER)) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun replace(job: CronJob) {
        all = all.map { if (it.id == job.id) job else it }
        arrange()
    }

    private fun act(job: CronJob, block: suspend (DashboardId, String) -> Unit) {
        val profile = _state.value.profile ?: return
        if (job.id in _state.value.working) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(working = it.working + job.id) }
            try {
                block(id, profile)
            } finally {
                _state.update { it.copy(working = it.working - job.id) }
            }
        }
    }

    private companion object {
        /** The chat list's filter for scheduled runs (`ChatFilter.Scheduled`). */
        const val SCHEDULED_FILTER = "scheduled"

        /** Active jobs first, soonest run first; then paused, failed and finished ones by name. */
        val JOB_ORDER = compareBy<CronJob>(
            { if (it.state == CronState.Scheduled) 0 else 1 },
            { it.nextRunAtMs ?: Long.MAX_VALUE },
            { it.name.lowercase() },
        )
    }
}

data class CronJobUiState(
    val job: CronJob? = null,
    val runs: List<CronRun> = emptyList(),
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    /** A run now, pause, resume or delete is in flight. */
    val working: Boolean = false,
    /** "Run now" is waiting on Hermes, which answers when the run ends. */
    val triggering: Boolean = false,
    val confirmDelete: Boolean = false,
    val message: CronJobMessage? = null,
    /** Whether a run notifies; null when the push plugin doesn't keep that. */
    val notifies: Boolean? = null,
    val changingNotify: Boolean = false,
    val targets: List<DeliveryTarget> = emptyList(),
    val zoneLabel: String? = null,
)

/** One job: its details, its last runs, and what can be done with it. */
@HiltViewModel
class CronJobViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val cron: CronRepository,
    private val plugin: PushPluginRepository,
    private val settings: AppSettings,
    private val hermes: HermesSettingsRepository,
) : ViewModel() {
    private val route = savedState.toRoute<CronJobRoute>()
    val profile = route.profile

    private val _state = MutableStateFlow(CronJobUiState())
    val state: StateFlow<CronJobUiState> = _state.asStateFlow()

    private val _deleted = Channel<Unit>(Channel.CONFLATED)

    /** Fires once the job is deleted, for the screen to close. */
    val deleted: Flow<Unit> = _deleted.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            loadJob(id)
            loadRuns(id)
            val muted = (plugin.mutedJobs(id, profile) as? NetResult.Success)?.value
            val targets = (cron.deliveryTargets(id, profile) as? NetResult.Success)?.value.orEmpty()
            val zone = hermes.zoneLabel(id, profile)
            _state.update {
                it.copy(notifies = muted?.let { set -> route.jobId !in set }, targets = targets, zoneLabel = zone)
            }
        }
    }

    fun runNow() = act { id ->
        _state.update { it.copy(triggering = true) }
        val result = cron.trigger(id, profile, route.jobId)
        _state.update { it.copy(triggering = false) }
        when (result) {
            is NetResult.Success -> {
                _state.update { it.copy(message = CronJobMessage.Triggered(result.value)) }
                loadJob(id)
                loadRuns(id)
            }

            is NetResult.Failure -> _state.update { it.copy(message = CronJobMessage.Failed(result.failure)) }
        }
    }

    fun setPaused(paused: Boolean) = act { id ->
        val result = if (paused) cron.pause(id, profile, route.jobId) else cron.resume(id, profile, route.jobId)
        when (result) {
            is NetResult.Success -> result.value?.let { job -> _state.update { it.copy(job = job) } } ?: loadJob(id)
            is NetResult.Failure -> _state.update { it.copy(message = CronJobMessage.Failed(result.failure)) }
        }
    }

    /** Shown at once, put back if the host refuses. One change at a time. */
    fun setNotifies(notify: Boolean) {
        val before = _state.value.notifies ?: return
        if (_state.value.changingNotify) return
        _state.update { it.copy(notifies = notify, changingNotify = true) }
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = plugin.setJobNotifies(id, profile, route.jobId, notify)) {
                is NetResult.Success -> _state.update {
                    it.copy(notifies = route.jobId !in result.value, changingNotify = false)
                }

                is NetResult.Failure -> _state.update {
                    it.copy(
                        notifies = before,
                        changingNotify = false,
                        message = CronJobMessage.NotifyFailed(result.failure),
                    )
                }
            }
        }
    }

    fun askDelete() = _state.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun delete() {
        _state.update { it.copy(confirmDelete = false) }
        act { id ->
            when (val result = cron.delete(id, profile, route.jobId)) {
                is NetResult.Success -> {
                    settings.setJobPinned(id, profile, route.jobId, pinned = false)
                    if (_state.value.notifies == false) plugin.setJobNotifies(id, profile, route.jobId, true)
                    _deleted.send(Unit)
                }

                is NetResult.Failure -> _state.update { it.copy(message = CronJobMessage.Failed(result.failure)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun act(block: suspend (DashboardId) -> Unit) {
        if (_state.value.working) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(working = true) }
            try {
                block(id)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    private suspend fun loadJob(id: DashboardId) {
        when (val result = cron.job(id, profile, route.jobId)) {
            is NetResult.Success -> _state.update { it.copy(job = result.value, loading = false, failure = null) }
            is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
        }
    }

    // A job whose runs can't be read still shows; its runs stay as last seen.
    private suspend fun loadRuns(id: DashboardId) {
        val result = cron.runs(id, profile, route.jobId)
        if (result is NetResult.Success) _state.update { it.copy(runs = result.value) }
    }
}

/** How the editor builds a schedule, as iOS's "Repeats". */
enum class ScheduleMode { AtTime, Every, Once, Custom }

data class CronEditUiState(
    val editing: Boolean = false,
    val loading: Boolean = false,
    val name: String = "",
    val prompt: String = "",
    val mode: ScheduleMode = ScheduleMode.AtTime,
    val atTime: ScheduleChoice.AtTime = ScheduleChoice.AtTime(),
    val every: ScheduleChoice.Every = ScheduleChoice.Every(),
    val once: ScheduleChoice.Once = ScheduleChoice.Once(defaultOnce()),
    val custom: ScheduleChoice.Custom = ScheduleChoice.Custom(""),
    val deliver: String = DeliveryTarget.LOCAL,
    /** Hermes' targets, and the job's own when Hermes doesn't list it. */
    val targets: List<DeliveryTarget> = listOf(DeliveryTarget(DeliveryTarget.LOCAL, "", hasHome = true)),
    val zoneLabel: String? = null,
    /** A job that runs a script may have no prompt. */
    val isScript: Boolean = false,
    val saving: Boolean = false,
    val failure: ConnectionFailure? = null,
    /** Something differs from the job as it was opened. */
    val dirty: Boolean = false,
) {
    val choice: ScheduleChoice
        get() = when (mode) {
            ScheduleMode.AtTime -> atTime
            ScheduleMode.Every -> every
            ScheduleMode.Once -> once
            ScheduleMode.Custom -> custom
        }

    val canSave: Boolean
        get() = !loading && !saving && (prompt.isNotBlank() || isScript) && choice.toHermes() != null &&
            (!editing || dirty)

    companion object {
        /** An hour from now, on the minute. */
        fun defaultOnce(): OffsetDateTime = OffsetDateTime.now().plusHours(1).truncatedTo(ChronoUnit.MINUTES)
    }
}

/** A new job, or the name, prompt, schedule and delivery of an existing one. */
@HiltViewModel
class CronEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val cron: CronRepository,
    private val hermes: HermesSettingsRepository,
) : ViewModel() {
    private val route = savedState.toRoute<CronEditRoute>()

    private val _state = MutableStateFlow(CronEditUiState(editing = route.jobId != null, loading = route.jobId != null))
    val state: StateFlow<CronEditUiState> = _state.asStateFlow()

    /** The job as opened, so an edit sends only what changed. */
    private var original = CronDraft()
    private var originalChoice: ScheduleChoice? = null

    private val _saved = Channel<Unit>(Channel.CONFLATED)

    /** Fires once the job is saved, for the screen to close. */
    val saved: Flow<Unit> = _saved.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            route.jobId?.let { loadJob(id, it) }
            val targets = (cron.deliveryTargets(id, route.profile) as? NetResult.Success)?.value
            val zone = hermes.zoneLabel(id, route.profile)
            _state.update { state ->
                val listed = targets ?: state.targets
                // The job's own target stays a choice when Hermes doesn't list it.
                val withOwn = if (listed.any { it.id == state.deliver }) {
                    listed
                } else {
                    listed + DeliveryTarget(state.deliver, state.deliver, hasHome = true)
                }
                state.copy(targets = withOwn, zoneLabel = zone)
            }
        }
    }

    private suspend fun loadJob(id: DashboardId, jobId: String) {
        when (val result = cron.job(id, route.profile, jobId)) {
            is NetResult.Success -> {
                val job = result.value
                if (job == null) {
                    _state.update { it.copy(loading = false) }
                    return
                }
                val choice = ScheduleChoice.of(job)
                original = CronDraft(job.name, job.prompt, job.scheduleInput, job.deliver)
                originalChoice = choice
                _state.update { state ->
                    val opened = state.copy(
                        loading = false,
                        name = job.name,
                        prompt = job.prompt,
                        deliver = job.deliver,
                        isScript = job.isScript,
                    )
                    when (choice) {
                        is ScheduleChoice.AtTime -> opened.copy(mode = ScheduleMode.AtTime, atTime = choice)
                        is ScheduleChoice.Every -> opened.copy(mode = ScheduleMode.Every, every = choice)
                        is ScheduleChoice.Once -> opened.copy(mode = ScheduleMode.Once, once = choice)
                        is ScheduleChoice.Custom -> opened.copy(mode = ScheduleMode.Custom, custom = choice)
                    }
                }
            }

            is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
        }
    }

    fun onNameChange(name: String) = change { it.copy(name = name) }

    fun onPromptChange(prompt: String) = change { it.copy(prompt = prompt) }

    fun onModeChange(mode: ScheduleMode) = change { it.copy(mode = mode) }

    fun onAtTimeChange(choice: ScheduleChoice.AtTime) = change { it.copy(atTime = choice) }

    fun onEveryChange(choice: ScheduleChoice.Every) = change { it.copy(every = choice) }

    fun onOnceChange(choice: ScheduleChoice.Once) = change { it.copy(once = choice) }

    fun onCustomChange(text: String) = change { it.copy(custom = ScheduleChoice.Custom(text)) }

    fun onDeliverChange(deliver: String) = change { it.copy(deliver = deliver) }

    private fun change(transform: (CronEditUiState) -> CronEditUiState) = _state.update { current ->
        val next = transform(current).copy(failure = null)
        next.copy(dirty = changes(next) != CronDraft())
    }

    /** What differs from the job as opened; for a new job, everything given. */
    private fun changes(state: CronEditUiState): CronDraft {
        val name = state.name.trim()
        val prompt = state.prompt.trim()
        return CronDraft(
            name = name.takeIf { it.isNotEmpty() && it != original.name },
            prompt = prompt.takeIf { it != original.prompt.orEmpty() },
            // An unchanged choice keeps Hermes' own wording ("every 120m" isn't rewritten as "every 2h").
            schedule = state.choice.toHermes().takeIf { state.choice != originalChoice },
            deliver = state.deliver.takeIf { it != (original.deliver ?: DeliveryTarget.LOCAL) },
        )
    }

    fun save() {
        val current = _state.value
        if (!current.canSave) return
        val schedule = current.choice.toHermes() ?: return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(saving = true, failure = null) }
            val result = when (val jobId = route.jobId) {
                // A blank name is left to Hermes, which names the job from its prompt.
                null -> cron.create(
                    id,
                    route.profile,
                    CronDraft(current.name.trim().ifEmpty { null }, current.prompt.trim(), schedule, current.deliver),
                )

                else -> cron.update(id, route.profile, jobId, changes(current))
            }
            when (result) {
                is NetResult.Success -> {
                    _state.update { it.copy(saving = false, dirty = false) }
                    _saved.send(Unit)
                }

                is NetResult.Failure -> _state.update { it.copy(saving = false, failure = result.failure) }
            }
        }
    }
}

data class CronBlueprintsUiState(
    val blueprints: List<CronBlueprint> = emptyList(),
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
)

/** The ready-made jobs Hermes offers, by category. */
@HiltViewModel
class CronBlueprintsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val cron: CronRepository,
) : ViewModel() {
    private val profile = savedState.toRoute<CronBlueprintsRoute>().profile

    private val _state = MutableStateFlow(CronBlueprintsUiState())
    val state: StateFlow<CronBlueprintsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(loading = true, failure = null) }
            when (val result = cron.blueprints(id, profile)) {
                is NetResult.Success -> _state.update { it.copy(blueprints = result.value, loading = false) }
                is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
            }
        }
    }
}

data class CronBlueprintUiState(
    val blueprint: CronBlueprint? = null,
    val loading: Boolean = true,
    val failure: ConnectionFailure? = null,
    /** Each blank's value; a blank left empty takes Hermes' default. */
    val values: Map<String, String> = emptyMap(),
    val targets: List<DeliveryTarget> = emptyList(),
    val creating: Boolean = false,
    /** Why Hermes didn't make the job. */
    val createFailure: ConnectionFailure? = null,
) {
    val canCreate: Boolean
        get() = blueprint != null && !creating &&
            blueprint.fields.none { !it.optional && it.default == null && values[it.name].isNullOrBlank() }
}

/** One blueprint's blanks, then the job it makes. */
@HiltViewModel
class CronBlueprintViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dashboards: DashboardRepository,
    private val cron: CronRepository,
) : ViewModel() {
    private val route = savedState.toRoute<CronBlueprintRoute>()
    val profile = route.profile

    private val _state = MutableStateFlow(CronBlueprintUiState())
    val state: StateFlow<CronBlueprintUiState> = _state.asStateFlow()

    private val _created = Channel<String>(Channel.CONFLATED)

    /** The new job's id, for the screen to open it. */
    val created: Flow<String> = _created.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            when (val result = cron.blueprints(id, profile)) {
                is NetResult.Success -> {
                    val blueprint = result.value.firstOrNull { it.key == route.key }
                    _state.update { state ->
                        state.copy(
                            blueprint = blueprint,
                            loading = false,
                            values = blueprint?.fields.orEmpty().mapNotNull { field ->
                                field.default?.let { field.name to it }
                            }.toMap(),
                        )
                    }
                }

                is NetResult.Failure -> _state.update { it.copy(loading = false, failure = result.failure) }
            }
            val targets = (cron.deliveryTargets(id, profile) as? NetResult.Success)?.value.orEmpty()
            _state.update { it.copy(targets = targets) }
        }
    }

    fun onValueChange(field: BlueprintField, value: String?) = _state.update {
        val values = if (value == null) it.values - field.name else it.values + (field.name to value)
        it.copy(values = values, createFailure = null)
    }

    /** The choices a field offers: a delivery field also offers every target Hermes lists. */
    fun optionsOf(field: BlueprintField): List<String> = when {
        field.isDelivery -> (field.options + _state.value.targets.map { it.id }).distinct()
        field.type == BlueprintFieldType.Weekdays -> field.options.ifEmpty { WEEKDAY_OPTIONS }
        else -> field.options
    }

    fun create() {
        val current = _state.value
        val blueprint = current.blueprint ?: return
        if (!current.canCreate) return
        viewModelScope.launch {
            val id = dashboards.activeDashboardId.first() ?: return@launch
            _state.update { it.copy(creating = true, createFailure = null) }
            // Blanks left empty are left out, for Hermes to fill.
            val values = current.values.filterValues(String::isNotBlank)
            when (val result = cron.instantiate(id, profile, blueprint.key, values)) {
                is NetResult.Success -> {
                    _state.update { it.copy(creating = false) }
                    result.value?.let { _created.send(it.id) }
                }

                is NetResult.Failure -> _state.update { it.copy(creating = false, createFailure = result.failure) }
            }
        }
    }

    private companion object {
        val WEEKDAY_OPTIONS = listOf("everyday", "weekdays", "weekends")
    }
}
