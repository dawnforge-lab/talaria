package lab.dawnforge.hermes.core.model

/** A scheduled job on Hermes: a prompt that runs on a schedule, each run its own chat. */
data class CronJob(
    val id: String,
    val name: String,
    val prompt: String,
    /** How Hermes words the schedule ("0 9 * * *", "every 120m", "once at …"). */
    val schedule: String,
    /**
     * The schedule as Hermes reads it back unchanged, for editing: the cron expression, "every
     * 120m", or a one-off's ISO time (its display, "once in 30m", would move it).
     */
    val scheduleInput: String,
    /** Hermes' kind of schedule: `cron`, `interval` or `once`; null when it didn't say. */
    val scheduleKind: String? = null,
    val state: CronState,
    val nextRunAtMs: Long?,
    val lastRunAtMs: Long?,
    /** `ok`, `error` or `delivery_failed`, for the last run. */
    val lastStatus: String?,
    val lastError: String?,
    /** Where results go: `local`, `origin`, or messaging platforms. */
    val deliver: String,
    /** The job's own model, when it doesn't use the profile's. */
    val model: String?,
    /** A job that runs a script instead of the agent. */
    val isScript: Boolean,
)

enum class CronState {
    Scheduled,
    Paused,

    /** A one-off that has run; it can't be started again. */
    Completed,
    Error,
}

/** One run of a job. Agent runs are chats ([opensAsChat]); a script's output is only text. */
data class CronRun(
    val id: String,
    val title: String,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val profile: String?,
    val opensAsChat: Boolean,
)

/** A new job, or an edit to one: a null field is left as it is. */
data class CronDraft(
    val name: String? = null,
    val prompt: String? = null,
    val schedule: String? = null,
    /** Where results go ([DeliveryTarget.id], `origin`…); a new job without one is only saved. */
    val deliver: String? = null,
)

/** Where a job's results can go, as Hermes lists them; `local` (save only) comes first. */
data class DeliveryTarget(
    val id: String,
    val name: String,
    /** A platform without a home chat delivers nowhere: the run is only saved. */
    val hasHome: Boolean,
) {
    companion object {
        const val LOCAL = "local"
        const val ORIGIN = "origin"
    }
}

/** A ready-made job Hermes offers, with a few blanks to fill ([fields]). */
data class CronBlueprint(
    val key: String,
    val title: String,
    val description: String,
    val category: String,
    /** Its schedule in words, with the defaults ("weekdays at 08:00"). */
    val scheduleHuman: String?,
    val fields: List<BlueprintField>,
)

data class BlueprintField(
    val name: String,
    val type: BlueprintFieldType,
    val label: String,
    val default: String?,
    val options: List<String>,
    val optional: Boolean,
    val help: String?,
) {
    /** The field Hermes sends a job's results by. */
    val isDelivery: Boolean get() = name == "deliver"
}

/** A time is HH:MM; weekdays are `everyday`, `weekdays` or `weekends`. */
enum class BlueprintFieldType { Time, Choice, Text, Weekdays }
