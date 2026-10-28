package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How much of the calendar Hermes gets, as iOS's `PhoneCalendarAccess`. */
@Serializable
enum class CalendarAccess(val wire: String) {
    /** Reads events and asks to add or change them. */
    Full("full"),

    /** Can ask to add events, never reads them. */
    WriteOnly("write_only"),
    Off("off"),
}

/** Where the phone's data goes: one Hermes profile on one dashboard. */
@Serializable
data class PhoneDataDestination(val dashboard: String, val profile: String)

/**
 * The user's choices for the calendar and phone, as iOS's `PhoneSyncPreferences`: each its own
 * opt-in, kept on the phone (no calendar data in them). Reminders aren't offered: Android has no
 * system reminders store.
 */
@Serializable
data class PhoneSharing(
    val destination: PhoneDataDestination? = null,
    val calendar: CalendarAccess = CalendarAccess.Off,
    /** Event notes, attendees and links (off by default). */
    val details: Boolean = false,
    val attendeeEmails: Boolean = false,
    val status: Boolean = false,
    val focus: Boolean = false,
    /** New events Hermes asks for go in without asking; changes and deletions always ask. */
    val autoAdd: Boolean = false,
    val futureDays: Int = DEFAULT_FUTURE_DAYS,
    /** Calendars the user left out, by provider id. */
    val hiddenCalendars: Set<String> = emptySet(),
    /** Hermes may set alarms and timers, which this app rings. */
    val alarms: Boolean = false,
    /** The people the user picked go to Hermes. */
    val contacts: Boolean = false,
) {
    val sharesAnything: Boolean
        get() = calendar != CalendarAccess.Off || status || focus || alarms || contacts

    val isOn: Boolean get() = destination != null && sharesAnything

    /** What decides the shape of what's sent: a change sends everything again. */
    val fingerprint: String
        get() = listOf(calendar.wire, details, attendeeEmails, futureDays, hiddenCalendars.sorted().joinToString(","))
            .joinToString("|")

    /**
     * The `sharing` every upload carries, as it really is: a calendar the phone can't read is
     * shared as add-only or off, so the host drops what it can no longer have.
     */
    fun wire(canRead: Boolean, canWrite: Boolean): JsonObject {
        val access = when {
            calendar == CalendarAccess.Off -> CalendarAccess.Off
            calendar == CalendarAccess.Full && canRead -> CalendarAccess.Full
            canWrite -> CalendarAccess.WriteOnly
            else -> CalendarAccess.Off
        }
        return buildJsonObject {
            put("calendar", access.wire)
            put("reminders", false)
            put("details", details)
            put("attendee_emails", details && attendeeEmails && access == CalendarAccess.Full)
            put("status", status)
            put("focus", focus)
            put("auto_add", autoAdd)
            put("alarms", alarms)
            put("contacts", contacts)
            put("past_days", PAST_DAYS)
            put("future_days", futureDays)
        }
    }

    companion object {
        const val PAST_DAYS = 7
        const val DEFAULT_FUTURE_DAYS = 60
        val FUTURE_DAYS = listOf(30, 60, 180)
    }
}
