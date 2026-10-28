package lab.dawnforge.hermes.core.data.phonedata.phone

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Applies Hermes' calendar changes to Android's calendar provider, as iOS's
 * `EventKitPhoneSource.apply`: an event added to the calendar named (or the default one), and a
 * change or deletion found by its hashed occurrence id, for this occurrence or (`future`) this and
 * every later one. Only the fields Hermes sent change; nothing is cleared.
 */
class CalendarWriter(private val context: Context, private val source: CalendarSource) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Adds the event; its occurrence id when the phone can read the calendar, else null. */
    fun add(change: PhoneChange): String? {
        if (!source.canWrite) throw PhoneChangeError(DENIED)
        val calendar = calendarFor(change.text("calendar_id"))
        val allDay = change.flag("all_day") ?: false
        val start = time(change.text("start"), allDay) ?: throw PhoneChangeError(BAD_TIME)
        val end = time(change.text("end"), allDay) ?: start + if (allDay) DAY_MS else HOUR_MS
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events.TITLE, change.text("title").orEmpty())
            putTimes(start, end, allDay)
            change.text("location")?.let { put(Events.EVENT_LOCATION, it) }
            description(change, null)?.let { put(Events.DESCRIPTION, it) }
        }
        val uri = resolver.insert(Events.CONTENT_URI, values) ?: throw PhoneChangeError(FAILED)
        val eventId = ContentUris.parseId(uri)
        alarms(change)?.let { setAlarms(eventId, it) }
        return if (source.canRead) occurrenceId(eventId, start) else null
    }

    /** Changes the event; its occurrence id afterwards (it changes when the start moves). */
    fun update(change: PhoneChange): String {
        val found = find(change.eventId)
        val event = event(found.eventId)
        change.text("calendar_id")?.let { wanted ->
            if (calendarFor(wanted) != event.calendarId) throw PhoneChangeError(NO_MOVE)
        }
        val allDay = change.flag("all_day") ?: found.allDay
        val start = change.text("start")?.let { time(it, allDay) ?: throw PhoneChangeError(BAD_TIME) } ?: found.begin
        val length = if (change.text("start") != null && change.text("end") == null) {
            found.end - found.begin
        } else {
            null
        }
        val end = change.text("end")?.let { time(it, allDay) ?: throw PhoneChangeError(BAD_TIME) }
            ?: length?.let { start + it }
            ?: found.end
        val values = ContentValues().apply {
            change.text("title")?.let { put(Events.TITLE, it) }
            change.text("location")?.let { put(Events.EVENT_LOCATION, it) }
            description(change, event.description)?.let { put(Events.DESCRIPTION, it) }
        }
        val changesTime = change.text("start") != null || change.text("end") != null || change.flag("all_day") != null
        val id = when {
            !event.recurring -> {
                if (changesTime) values.putTimes(start, end, allDay)
                update(event.id, values)
                event.id
            }

            change.span == "future" -> splitSeries(event, found, values, start, end, allDay, changesTime)

            else -> {
                // One occurrence of a repeating event: an exception for it.
                values.put(Events.ORIGINAL_INSTANCE_TIME, found.begin)
                values.putTimes(start, end, allDay)
                values.put(Events.STATUS, Events.STATUS_CONFIRMED)
                exception(event.id, values)
            }
        }
        alarms(change)?.let { setAlarms(id, it) }
        return occurrenceId(id, start)
    }

    /** Deletes the event (this occurrence, or this and every later one); the id it had. */
    fun delete(change: PhoneChange): String {
        val found = find(change.eventId)
        val event = event(found.eventId)
        when {
            !event.recurring -> resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), null, null)

            change.span == "future" && found.begin <= event.start -> {
                resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), null, null)
            }

            change.span == "future" -> truncate(event, found.begin)

            else -> exception(
                event.id,
                ContentValues().apply {
                    put(Events.ORIGINAL_INSTANCE_TIME, found.begin)
                    put(Events.STATUS, Events.STATUS_CANCELED)
                },
            )
        }
        return change.eventId.orEmpty()
    }

    /** Removes an event this phone just added (Undo), by the id [add] gave. */
    fun removeAdded(occurrenceId: String) {
        val found = runCatching { findOccurrence(occurrenceId) }.getOrNull() ?: return
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, found.eventId), null, null)
    }

    // ---- Finding --------------------------------------------------------------------------------

    private data class Occurrence(val eventId: Long, val begin: Long, val end: Long, val allDay: Boolean)

    private data class Event(
        val id: Long,
        val calendarId: Long,
        val start: Long,
        val recurring: Boolean,
        val rrule: String?,
        val description: String?,
        val values: ContentValues,
    )

    private fun find(id: String?): Occurrence {
        if (!source.canRead) throw PhoneChangeError(WRITE_ONLY)
        return findOccurrence(id ?: throw PhoneChangeError(NOT_FOUND))
    }

    /** The occurrence with this hashed id, looked for well beyond what's shared, as iOS does. */
    private fun findOccurrence(id: String): Occurrence {
        val now = System.currentTimeMillis()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - SEARCH_BACK_MS)
            ContentUris.appendId(it, now + SEARCH_AHEAD_MS)
        }.build()
        val columns = arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY)
        resolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val begin = cursor.getLong(1)
                if (occurrenceId(eventId, begin) == id) {
                    return Occurrence(eventId, begin, cursor.getLong(2), cursor.getInt(3) == 1)
                }
            }
        }
        throw PhoneChangeError(NOT_FOUND)
    }

    private fun event(id: Long): Event {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
        resolver.query(uri, EVENT_COLUMNS, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val calendarId = cursor.getLong(cursor.getColumnIndexOrThrow(Events.CALENDAR_ID))
                if (!writable(calendarId)) throw PhoneChangeError(READ_ONLY)
                val rrule = cursor.text(Events.RRULE)
                return Event(
                    id = id,
                    calendarId = calendarId,
                    start = cursor.getLong(cursor.getColumnIndexOrThrow(Events.DTSTART)),
                    recurring = !rrule.isNullOrEmpty() || !cursor.text(Events.RDATE).isNullOrEmpty(),
                    rrule = rrule,
                    description = cursor.text(Events.DESCRIPTION),
                    values = ContentValues().also { copy -> EVENT_COLUMNS.forEach { copy.copyFrom(cursor, it) } },
                )
            }
        }
        throw PhoneChangeError(NOT_FOUND)
    }

    // ---- Repeating events -----------------------------------------------------------------------

    /**
     * This and every later occurrence: the series ends before this one, and a new series starts
     * here with the change. From the first occurrence, that's the whole series.
     */
    private fun splitSeries(
        event: Event,
        found: Occurrence,
        changed: ContentValues,
        start: Long,
        end: Long,
        allDay: Boolean,
        changesTime: Boolean,
    ): Long {
        if (found.begin <= event.start) {
            if (changesTime) {
                changed.put(Events.DTSTART, start)
                changed.put(Events.DURATION, duration(end - start, allDay))
                changed.put(Events.ALL_DAY, if (allDay) 1 else 0)
            }
            update(event.id, changed)
            return event.id
        }
        val remaining = event.rrule?.let { remainingRule(event, found.begin) }
        truncate(event, found.begin)
        val copy = ContentValues(event.values).apply {
            remove(Events._ID)
            putAll(changed)
            put(Events.DTSTART, start)
            remove(Events.DTEND)
            put(Events.DURATION, duration(end - start, allDay))
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            remaining?.let { put(Events.RRULE, it) }
        }
        val uri = resolver.insert(Events.CONTENT_URI, copy) ?: throw PhoneChangeError(FAILED)
        return ContentUris.parseId(uri)
    }

    /** Ends the series just before [instanceBegin]. */
    private fun truncate(event: Event, instanceBegin: Long) {
        val rule = event.rrule ?: throw PhoneChangeError(FAILED)
        val until = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .format(Instant.ofEpochMilli(instanceBegin - 1000).atOffset(ZoneOffset.UTC))
        val ended = rule.split(';').filterNot { it.startsWith("UNTIL=") || it.startsWith("COUNT=") }
            .plus("UNTIL=$until").joinToString(";")
        update(event.id, ContentValues().apply { put(Events.RRULE, ended) })
    }

    /** The rule for the occurrences from [instanceBegin] on: a counted series keeps the count left. */
    private fun remainingRule(event: Event, instanceBegin: Long): String {
        val rule = event.rrule.orEmpty()
        val count = rule.split(';').firstOrNull { it.startsWith("COUNT=") }?.removePrefix("COUNT=")?.toIntOrNull()
            ?: return rule
        val before = countInstances(event.id, event.start, instanceBegin - 1)
        return rule.split(';').map { if (it.startsWith("COUNT=")) "COUNT=${(count - before).coerceAtLeast(1)}" else it }
            .joinToString(";")
    }

    private fun countInstances(eventId: Long, from: Long, to: Long): Int {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        return resolver.query(
            uri,
            arrayOf(Instances._ID),
            "${Instances.EVENT_ID} = ?",
            arrayOf(eventId.toString()),
            null,
        )
            ?.use(Cursor::getCount) ?: 0
    }

    private fun exception(eventId: Long, values: ContentValues): Long {
        val uri = resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values)
            ?: throw PhoneChangeError(FAILED)
        return ContentUris.parseId(uri)
    }

    // ---- Writing --------------------------------------------------------------------------------

    private fun update(eventId: Long, values: ContentValues) {
        if (values.size() == 0) return
        val rows = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
        if (rows == 0) throw PhoneChangeError(NOT_FOUND)
    }

    private fun ContentValues.putTimes(start: Long, end: Long, allDay: Boolean) {
        put(Events.DTSTART, start)
        put(Events.DTEND, end)
        put(Events.ALL_DAY, if (allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else ZoneId.systemDefault().id)
    }

    /** The notes, with the link after them (Android's calendar has no link field). */
    private fun description(change: PhoneChange, existing: String?): String? {
        val notes = change.text("notes")
        val url = change.text("url")
        if (notes == null && url == null) return null
        val text = notes ?: existing.orEmpty()
        return if (url == null) text else listOf(text, url).filter(String::isNotBlank).joinToString("\n\n")
    }

    /** Up to three alarms, minutes before; null when Hermes didn't send any. */
    private fun alarms(change: PhoneChange): List<Int>? {
        val list = change.fields["alarms_min"] ?: return null
        if (list is JsonNull) return null
        return (list as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in 0..MAX_ALARM_MIN }
            ?.take(MAX_ALARMS)
    }

    private fun setAlarms(eventId: Long, minutes: List<Int>) {
        resolver.delete(Reminders.CONTENT_URI, "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        minutes.forEach { min ->
            resolver.insert(
                Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(Reminders.EVENT_ID, eventId)
                    put(Reminders.MINUTES, min)
                    put(Reminders.METHOD, Reminders.METHOD_ALERT)
                },
            )
        }
    }

    /** The calendar a hashed id names, or the default one; refuses one the phone can't write to. */
    private fun calendarFor(hashed: String?): Long {
        val calendars = source.calendars()
        val chosen =
            hashed?.let { id ->
                calendars.firstOrNull { CalendarSource.calendarId(it.id) == id }
                    ?: throw PhoneChangeError(NO_CALENDAR)
            }
                ?: calendars.firstOrNull { it.writable }
                ?: throw PhoneChangeError(NO_CALENDAR)
        if (!chosen.writable) throw PhoneChangeError(READ_ONLY)
        return chosen.id
    }

    private fun writable(calendarId: Long): Boolean =
        source.calendars().firstOrNull { it.id == calendarId }?.writable ?: false

    private fun occurrenceId(eventId: Long, begin: Long) =
        PhoneWire.hashed("e_", eventId.toString(), (begin / 1000).toString())

    private fun Cursor.text(column: String): String? = getColumnIndex(column).takeIf {
        it >= 0
    }?.let { if (isNull(it)) null else getString(it) }

    private fun ContentValues.copyFrom(cursor: Cursor, column: String) {
        val index = cursor.getColumnIndex(column)
        if (index < 0 || cursor.isNull(index)) return
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> put(column, cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> put(column, cursor.getDouble(index))
            else -> put(column, cursor.getString(index))
        }
    }

    companion object {
        const val DENIED = "Calendar access is off on the phone."
        const val NOT_FOUND = "That event isn't on the phone's calendar any more."
        const val READ_ONLY = "That calendar can't be changed on the phone."
        const val WRITE_ONLY = "The phone only lets Hermes add events, not read or change them."
        const val NO_CALENDAR = "The phone has no calendar Hermes can add to."
        const val NO_MOVE = "Android can't move an event to another calendar."
        const val BAD_TIME = "The time wasn't one the phone could read."
        const val FAILED = "The phone's calendar didn't take the change."

        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val SEARCH_BACK_MS = 40 * DAY_MS
        private const val SEARCH_AHEAD_MS = 220 * DAY_MS
        private const val MAX_ALARMS = 3
        private const val MAX_ALARM_MIN = 10_080

        private val EVENT_COLUMNS = arrayOf(
            Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE,
            Events.RDATE, Events.EXRULE, Events.EXDATE, Events.AVAILABILITY, Events.HAS_ALARM,
        )

        /** A time Hermes sent: ISO with an offset, or a `YYYY-MM-DD` day for an all-day event (UTC midnight, as Android keeps those). */
        fun time(text: String?, allDay: Boolean): Long? {
            text ?: return null
            if (allDay || text.length == DAY_CHARS) {
                runCatching { LocalDate.parse(text.take(DAY_CHARS)) }.getOrNull()?.let {
                    return it.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            }
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        }

        /** An RFC 5545 duration for a series: whole days for an all-day event. */
        fun duration(lengthMs: Long, allDay: Boolean): String =
            if (allDay) "P${(lengthMs / DAY_MS).coerceAtLeast(1)}D" else "P${(lengthMs / 1000).coerceAtLeast(1)}S"

        private const val DAY_CHARS = 10
    }
}
