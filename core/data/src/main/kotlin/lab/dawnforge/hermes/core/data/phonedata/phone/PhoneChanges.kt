package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What Hermes can ask the phone to do (reminders aren't shared on Android). */
enum class PhoneChangeKind(val wire: String) {
    AddEvent("add_event"),
    UpdateEvent("update_event"),
    DeleteEvent("delete_event"),
    SetAlarm("set_alarm"),
    SetTimer("set_timer"),
    CancelAlarm("cancel_alarm"),
    ;

    /** New items can go in without asking, when the user chose that; changes and deletions always ask. */
    val addsWithoutAsking: Boolean get() = this == AddEvent || this == SetAlarm || this == SetTimer

    companion object {
        fun of(wire: String?): PhoneChangeKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A change Hermes asked for (`/phone/exchange` answer's `changes[]`), as iOS reads it: the event or
 * alarm it's about ([target]), what to set ([fields]), and whether the host lets it go in without
 * asking ([auto]).
 */
data class PhoneChange(
    val id: String,
    val kind: PhoneChangeKind,
    val auto: Boolean,
    val reason: String?,
    val sessionTitle: String?,
    val target: JsonObject,
    val fields: JsonObject,
) {
    val eventId: String? get() = target.text("event_id")
    val alarmId: String? get() = target.text("alarm_id")

    /** `future` changes this and every later occurrence; anything else, this one. */
    val span: String get() = fields.text("span") ?: target.text("span") ?: "this"

    fun text(key: String): String? = fields.text(key)

    fun flag(key: String): Boolean? = (fields[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        /** A change from the host's answer; null for one this phone can't do (an unknown kind, a reminder). */
        fun parse(json: JsonObject): PhoneChange? {
            val id = json.text("id")?.takeIf { it.length in 1..MAX_ID } ?: return null
            val kind = PhoneChangeKind.of(json.text("kind")) ?: return null
            return PhoneChange(
                id = id,
                kind = kind,
                auto = (json["auto"] as? JsonPrimitive)?.booleanOrNull == true,
                reason = json.text("reason")?.take(MAX_REASON),
                sessionTitle = json.text("session_title")?.take(MAX_SESSION_TITLE),
                target = json["target"] as? JsonObject ?: JsonObject(emptyMap()),
                fields = json["fields"] as? JsonObject ?: JsonObject(emptyMap()),
            )
        }

        private const val MAX_ID = 80
        private const val MAX_REASON = 200
        private const val MAX_SESSION_TITLE = 80
    }
}

/** How a change went, reported on the next exchange. */
@Serializable
data class PhoneChangeResult(
    val id: String,
    val outcome: String,
    val itemId: String? = null,
    val note: String? = null,
) {
    fun wire(): JsonObject = buildJsonObject {
        put("id", id)
        put("outcome", outcome)
        itemId?.let { put("item_id", it) }
        note?.let { put("note", it.take(MAX_NOTE)) }
    }

    companion object {
        const val APPLIED = "applied"
        const val DISMISSED = "dismissed"
        const val FAILED = "failed"
        private const val MAX_NOTE = 200
    }
}

/** Why a change couldn't be applied: the note Hermes reads. */
class PhoneChangeError(message: String) : Exception(message)

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
