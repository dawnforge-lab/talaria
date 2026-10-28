package lab.dawnforge.hermes.core.data.phonedata.phone

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's calendars and events, as the plugin takes them (iOS's `EventKitPhoneSource`): read
 * from Android's calendar provider, each event occurrence its own item, with ids hashed so the
 * phone's own never leave it. Reminders have no Android store, so they are never shared.
 */
class CalendarSource(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    val canRead: Boolean
        get() = granted(Manifest.permission.READ_CALENDAR)

    val canWrite: Boolean
        get() = granted(Manifest.permission.WRITE_CALENDAR)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A calendar as the settings page lists it, by its provider id. */
    data class Calendar(val id: Long, val title: String, val account: String, val color: Int, val writable: Boolean)

    /** The calendars on this phone, for the settings page's list of what to leave out. */
    fun calendars(): List<Calendar> {
        if (!canRead) return emptyList()
        return query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_COLUMNS) { cursor ->
            Calendar(
                id = cursor.getLong(0),
                title = cursor.getString(1).orEmpty(),
                account = cursor.getString(2).orEmpty(),
                color = cursor.getInt(3),
                writable = cursor.getInt(4) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
            )
        }.take(MAX_CALENDARS)
    }

    /** The calendars as the host takes them, leaving out [hidden] ones (by provider id). */
    fun calendarsJson(hidden: Set<String>): List<JsonObject> {
        val all = calendars().filter { it.id.toString() !in hidden }
        val defaultId = all.firstOrNull { it.writable }?.id
        return all.map { calendar ->
            buildJsonObject {
                put("id", calendarId(calendar.id))
                put("title", calendar.title.take(TITLE_CHARS))
                put("kind", "event")
                put("color", "#%06x".format(calendar.color and 0xFFFFFF))
                put("source", calendar.account.take(SOURCE_CHARS))
                put("writable", calendar.writable)
                // The provider doesn't say whether a calendar is shared with others.
                put("shared", false)
                put("default", calendar.id == defaultId)
            }
        }
    }

    /**
     * The event occurrences between [fromMs] and [toMs] in the calendars not [hidden], sorted by
     * start, at most [limit]. With [details], notes and attendees ([emails] adds their addresses).
     */
    fun events(
        fromMs: Long,
        toMs: Long,
        hidden: Set<String>,
        details: Boolean,
        emails: Boolean,
        limit: Int,
    ): List<JsonObject> {
        if (!canRead) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val rows = query(uri, INSTANCE_COLUMNS, sort = "${CalendarContract.Instances.BEGIN} ASC") { it.toInstance() }
            .filter { it.calendarId.toString() !in hidden }
        val result = ArrayList<JsonObject>(minOf(rows.size, limit))
        for (row in rows) {
            if (result.size >= limit) break
            result += row.json(details, emails)
        }
        return result
    }

    private data class Instance(
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String?,
        val allDay: Boolean,
        val timeZone: String?,
        val availability: Int,
        val status: Int?,
        val recurring: Boolean,
        val isOrganizer: Boolean,
        val selfStatus: Int,
        val location: String?,
        val description: String?,
        val calendarId: Long,
        val hasAlarm: Boolean,
        val hasAttendees: Boolean,
    )

    private fun Cursor.toInstance() = Instance(
        eventId = getLong(0),
        begin = getLong(1),
        end = getLong(2),
        title = getString(3),
        allDay = getInt(4) == 1,
        timeZone = getString(5),
        availability = getInt(6),
        status = if (isNull(7)) null else getInt(7),
        recurring = !getString(8).isNullOrEmpty() || !getString(9).isNullOrEmpty(),
        isOrganizer = getString(10) == "1" || getString(10).equals("true", ignoreCase = true),
        selfStatus = getInt(11),
        location = getString(12),
        description = getString(13),
        calendarId = getLong(14),
        hasAlarm = getInt(15) == 1,
        hasAttendees = getInt(16) == 1,
    )

    private fun Instance.json(details: Boolean, emails: Boolean): JsonObject {
        val zone = if (allDay) ZoneId.systemDefault() else zoneOf(timeZone)
        val attendees = if (details || hasAttendees) attendees(eventId) else emptyList()
        return buildJsonObject {
            put("id", PhoneWire.hashed("e_", eventId.toString(), (begin / MILLIS).toString()))
            put("calendar_id", calendarId(calendarId))
            put("title", title.orEmpty().take(TITLE_CHARS))
            put("start", if (allDay) allDayStart(begin) else PhoneWire.format(begin, zone))
            put("end", if (allDay) allDayStart(end) else PhoneWire.format(end, zone))
            put("all_day", allDay)
            put("time_zone", zone.id)
            put("availability", availabilityName(availability))
            put("status", statusName(status))
            put("recurring", recurring)
            // An event without attendees is the user's own.
            put("organizer_is_me", isOrganizer || attendees.isEmpty())
            put("my_status", participationName(selfStatus))
            location?.takeIf(String::isNotBlank)?.let { put("location", it.take(LOCATION_CHARS)) }
            if (hasAlarm) {
                val minutes = alarmMinutes(eventId)
                if (minutes.isNotEmpty()) {
                    put(
                        "alarms_min",
                        JsonArray(
                            minutes.take(MAX_ALARMS).map {
                                kotlinx.serialization.json.JsonPrimitive(it)
                            },
                        ),
                    )
                }
            }
            if (details) {
                description?.takeIf(String::isNotBlank)?.let { put("notes", it.take(NOTES_CHARS)) }
                if (attendees.isNotEmpty()) {
                    put(
                        "attendees",
                        buildJsonArray {
                            attendees.take(MAX_ATTENDEES).forEach { person ->
                                add(
                                    buildJsonObject {
                                        put("name", person.name.take(NAME_CHARS))
                                        put("status", participationName(person.status))
                                        put("role", roleName(person.type, person.relationship))
                                        if (emails &&
                                            person.email.isNotBlank()
                                        ) {
                                            put("email", person.email.take(EMAIL_CHARS))
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    /** An all-day event's day, as local midnight: the provider keeps it as UTC midnight. */
    private fun allDayStart(utcMidnightMs: Long): String {
        val day = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate()
        return PhoneWire.format(
            LocalDate.of(
                day.year,
                day.month,
                day.dayOfMonth,
            ).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
    }

    private fun zoneOf(id: String?): ZoneId =
        id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    private data class Attendee(
        val name: String,
        val email: String,
        val status: Int,
        val type: Int,
        val relationship: Int,
    )

    private fun attendees(eventId: Long): List<Attendee> = query(
        CalendarContract.Attendees.CONTENT_URI,
        ATTENDEE_COLUMNS,
        "${CalendarContract.Attendees.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
    ) { Attendee(it.getString(0).orEmpty(), it.getString(1).orEmpty(), it.getInt(2), it.getInt(3), it.getInt(4)) }

    private fun alarmMinutes(eventId: Long): List<Int> = query(
        CalendarContract.Reminders.CONTENT_URI,
        arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
        "${CalendarContract.Reminders.EVENT_ID} = ?",
        arrayOf(eventId.toString()),
    ) { cursor -> cursor.getInt(0).takeIf { cursor.getInt(1) != CalendarContract.Reminders.METHOD_EMAIL } }
        .filterNotNull()
        .map { if (it < 0) 0 else it }

    private fun <T> query(
        uri: android.net.Uri,
        columns: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        sort: String? = null,
        read: (Cursor) -> T,
    ): List<T> = runCatching {
        resolver.query(uri, columns, selection, args, sort)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        /** The host's id of a calendar, by its provider id. */
        fun calendarId(providerId: Long): String = PhoneWire.hashed("c_", providerId.toString())

        private const val MILLIS = 1000L
        private const val MAX_CALENDARS = 100
        private const val TITLE_CHARS = 200
        private const val SOURCE_CHARS = 60
        private const val LOCATION_CHARS = 300
        private const val NOTES_CHARS = 2000
        private const val NAME_CHARS = 100
        private const val EMAIL_CHARS = 200
        private const val MAX_ATTENDEES = 30
        private const val MAX_ALARMS = 5

        private val CALENDAR_COLUMNS = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )

        private val INSTANCE_COLUMNS = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE,
            CalendarContract.Instances.IS_ORGANIZER,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.HAS_ALARM,
            CalendarContract.Instances.HAS_ATTENDEE_DATA,
        )

        private val ATTENDEE_COLUMNS = arrayOf(
            CalendarContract.Attendees.ATTENDEE_NAME,
            CalendarContract.Attendees.ATTENDEE_EMAIL,
            CalendarContract.Attendees.ATTENDEE_STATUS,
            CalendarContract.Attendees.ATTENDEE_TYPE,
            CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
        )

        fun availabilityName(value: Int): String = when (value) {
            CalendarContract.Events.AVAILABILITY_BUSY -> "busy"
            CalendarContract.Events.AVAILABILITY_FREE -> "free"
            CalendarContract.Events.AVAILABILITY_TENTATIVE -> "tentative"
            else -> "none"
        }

        fun statusName(value: Int?): String = when (value) {
            CalendarContract.Events.STATUS_CONFIRMED -> "confirmed"
            CalendarContract.Events.STATUS_TENTATIVE -> "tentative"
            CalendarContract.Events.STATUS_CANCELED -> "canceled"
            else -> "none"
        }

        fun participationName(value: Int): String = when (value) {
            CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED -> "accepted"
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED -> "declined"
            CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE -> "tentative"
            CalendarContract.Attendees.ATTENDEE_STATUS_INVITED -> "pending"
            else -> "none"
        }

        fun roleName(type: Int, relationship: Int): String = when {
            relationship == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER -> "chair"
            type == CalendarContract.Attendees.TYPE_REQUIRED -> "required"
            type == CalendarContract.Attendees.TYPE_OPTIONAL -> "optional"
            type == CalendarContract.Attendees.TYPE_RESOURCE -> "non_participant"
            else -> "unknown"
        }
    }
}
