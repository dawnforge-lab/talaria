package lab.dawnforge.hermes.core.data.phonedata.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneChange
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneChangeError
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneWire

/** An alarm or timer this app rings, as Hermes set it. */
@Serializable
data class PhoneAlarm(
    val id: String,
    /** `alarm` or `timer`. */
    val kind: String,
    val label: String? = null,
    /** A one-off ring (an `at` alarm, or a timer), epoch ms. */
    val at: Long? = null,
    /** A clock alarm's time, phone-local, with the days it repeats on (`mon`…`sun`); none rings once. */
    val hour: Int? = null,
    val minute: Int? = null,
    val repeats: List<String>? = null,
    /** Ringing now. */
    val alerting: Boolean = false,
)

/**
 * The alarms and timers Hermes sets on this phone, which this app rings (Android's clock app can
 * set them but can't list or cancel them). As iOS's AlarmKit ones: they ring through Do Not Disturb,
 * with Stop; a repeating alarm sets itself again, a one-off goes once it rang. Hermes sees only
 * these.
 */
class AlarmBook(
    private val context: Context,
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val editing = Mutex()

    val alarms: Flow<List<PhoneAlarm>> = store.data.map { read(it[KEY]) }

    /** Whether Android lets this app ring at exact times ("Alarms & reminders" special access). */
    val canSchedule: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** `set_alarm`: at a moment (`at`), or at a clock time (`time`) repeating on [PhoneAlarm.repeats]. */
    suspend fun setAlarm(change: PhoneChange): String {
        if (!canSchedule) throw PhoneChangeError(NOT_ALLOWED)
        val label = change.text("label")?.take(MAX_LABEL)
        val alarm = change.text("at")?.let { at ->
            val time = runCatching { OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
                ?: throw PhoneChangeError(BAD_TIME)
            if (time <= clock()) throw PhoneChangeError(PAST)
            PhoneAlarm(newId(), "alarm", label, at = time)
        } ?: change.text("time")?.let { text ->
            val time = runCatching { LocalTime.parse(text) }.getOrNull() ?: throw PhoneChangeError(BAD_TIME)
            val days = (change.fields["repeats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.filter { it in WEEKDAYS }?.takeIf { it.isNotEmpty() }
            val repeating = PhoneAlarm(newId(), "alarm", label, hour = time.hour, minute = time.minute, repeats = days)
            // Without repeats it rings once, at the next such time: fixed now, so a phone off then doesn't ring a day late.
            if (days ==
                null
            ) {
                repeating.copy(
                    hour = null,
                    minute = null,
                    at = nextRing(repeating.copy(repeats = WEEKDAYS), clock()),
                )
            } else {
                repeating
            }
        } ?: throw PhoneChangeError(BAD_TIME)
        add(alarm)
        return alarm.id
    }

    /** `set_timer`: rings [seconds] from now. */
    suspend fun setTimer(change: PhoneChange): String {
        if (!canSchedule) throw PhoneChangeError(NOT_ALLOWED)
        val seconds = (change.fields["seconds"] as? JsonPrimitive)?.intOrNull?.takeIf { it in MIN_TIMER_S..MAX_TIMER_S }
            ?: throw PhoneChangeError(BAD_TIME)
        val alarm = PhoneAlarm(
            newId(),
            "timer",
            change.text("label")?.take(MAX_LABEL),
            at =
                clock() + seconds * SECOND_MS,
        )
        add(alarm)
        return alarm.id
    }

    /** `cancel_alarm`, or Undo: the alarm goes; an id this app doesn't have fails. */
    suspend fun cancel(id: String) {
        val removed = editing.withLock {
            val list = read(store.data.first()[KEY])
            val alarm = list.firstOrNull { it.id == id } ?: return@withLock null
            write(list - alarm)
            alarm
        } ?: throw PhoneChangeError(NOT_FOUND)
        unschedule(removed.id)
        AlarmRinging.stop(context, removed.id)
    }

    /** The alarm rings: it shows as alerting until stopped. */
    suspend fun ringing(id: String): PhoneAlarm? = editing.withLock {
        val list = read(store.data.first()[KEY])
        val alarm = list.firstOrNull { it.id == id } ?: return@withLock null
        write(list.map { if (it.id == id) it.copy(alerting = true) else it })
        alarm
    }

    /** Stop: a repeating alarm sets itself for its next day, a one-off goes. */
    suspend fun stop(id: String) {
        val next = editing.withLock {
            val list = read(store.data.first()[KEY])
            val alarm = list.firstOrNull { it.id == id } ?: return@withLock null
            if (alarm.repeats.isNullOrEmpty()) {
                write(list - alarm)
                null
            } else {
                val again = alarm.copy(alerting = false)
                write(list.map { if (it.id == id) again else it })
                again
            }
        }
        AlarmRinging.stop(context, id)
        next?.let(::schedule)
    }

    /** After a restart or a clock change: every alarm is set again (Android forgets them). */
    suspend fun rescheduleAll() {
        val now = clock()
        val kept = editing.withLock {
            val list = read(store.data.first()[KEY])
            // A one-off that should have rung while the phone was off is gone.
            val alive = list.filter { it.alerting || nextRing(it, now) != null }
            if (alive != list) write(alive)
            alive
        }
        kept.filterNot { it.alerting }.forEach(::schedule)
    }

    /** The alarms as the host takes them: the next ring, and whether one is ringing. */
    suspend fun wire(): List<JsonObject> {
        val now = clock()
        return read(store.data.first()[KEY]).take(MAX_LISTED).map { alarm ->
            buildJsonObject {
                put("id", alarm.id)
                put("kind", alarm.kind)
                alarm.label?.let { put("label", it) }
                nextRing(alarm, now)?.let { put("fires_at", PhoneWire.format(it)) }
                alarm.repeats?.let { days -> put("repeats", JsonArray(days.map(::JsonPrimitive))) }
                put(
                    "state",
                    when {
                        alarm.alerting -> "alerting"
                        alarm.kind == "timer" -> "countdown"
                        else -> "scheduled"
                    },
                )
            }
        }
    }

    private suspend fun add(alarm: PhoneAlarm) {
        editing.withLock {
            val list = read(store.data.first()[KEY])
            if (list.size >= MAX_LISTED) throw PhoneChangeError(TOO_MANY)
            write(list + alarm)
        }
        schedule(alarm)
    }

    private fun schedule(alarm: PhoneAlarm) {
        val at = nextRing(alarm, clock()) ?: return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            AlarmRinging.screen(context, alarm.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ring(alarm.id)) }
    }

    private fun unschedule(id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(ring(id))
    }

    private fun ring(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(
            context,
            AlarmReceiver::class.java,
        ).setAction(AlarmReceiver.ACTION_RING).putExtra(AlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun write(list: List<PhoneAlarm>) {
        store.edit { it[KEY] = json.encodeToString(LIST, list) }
    }

    private fun read(text: String?): List<PhoneAlarm> =
        text?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }.orEmpty()

    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        const val NOT_ALLOWED = "Alarms are off for this app. Allow \"Alarms & reminders\" in the phone's settings."
        const val PAST = "That alarm time has already passed."
        const val NOT_FOUND = "That alarm isn't on the phone any more."
        const val BAD_TIME = "The time wasn't one the phone could read."
        const val TOO_MANY = "The phone has as many alarms from Hermes as it keeps."

        private val KEY = stringPreferencesKey("phonedata.alarms")
        private val LIST = ListSerializer(PhoneAlarm.serializer())
        private const val MAX_LABEL = 60
        private const val MAX_LISTED = 50
        private const val MIN_TIMER_S = 5
        private const val MAX_TIMER_S = 86_400
        private const val SECOND_MS = 1000L
        val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

        /** When [alarm] rings next after [nowMs]; null for a one-off already past. */
        fun nextRing(alarm: PhoneAlarm, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
            alarm.at?.let { return it.takeIf { at -> at > nowMs } }
            val hour = alarm.hour ?: return null
            val minute = alarm.minute ?: return null
            val days = alarm.repeats.orEmpty().mapNotNull { day ->
                WEEKDAYS.indexOf(day).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }
            }
            if (days.isEmpty()) return null
            val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            for (offset in 0..WEEK) {
                val candidate = start.toLocalDate().plusDays(offset.toLong()).atTime(hour, minute).atZone(zone)
                if (candidate.toInstant().toEpochMilli() <= nowMs) continue
                if (candidate.dayOfWeek in days) return candidate.toInstant().toEpochMilli()
            }
            return null
        }

        private const val WEEK = 7
    }
}
