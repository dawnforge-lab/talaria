package lab.dawnforge.hermes.core.data.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.model.SessionSearchHit
import lab.dawnforge.hermes.core.model.SessionSummary

// Hermes 0.22.2 shapes, read leniently: flags are sometimes booleans, sometimes 0/1, and times are
// float epoch seconds.

internal fun JsonElement.profiles(): List<Profile> =
    ((this as? JsonObject)?.get("profiles") as? JsonArray).orEmpty().mapNotNull { entry ->
        when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.let { Profile(it, null, it == Profile.DEFAULT, null, null) }

            is JsonObject -> {
                val name = entry.string("name") ?: return@mapNotNull null
                Profile(
                    name = name,
                    displayName = entry.string("display_name"),
                    isDefault = entry.flag("is_default") ?: (name == Profile.DEFAULT),
                    model = entry.string("model"),
                    provider = entry.string("provider"),
                )
            }

            else -> null
        }
    }

internal fun JsonElement.sessionRows(): List<SessionSummary> =
    ((this as? JsonObject)?.get("sessions") as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonObject)?.toSessionSummary()
    }

internal fun JsonElement.searchHits(): List<SessionSearchHit> =
    ((this as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = row.string("session_id") ?: row.string("id") ?: return@mapNotNull null
        SessionSearchHit(
            sessionId = id,
            title = row.string("title"),
            preview = row.string("preview"),
            snippet = row.string("snippet"),
            lastActiveAtMs = row.epochMs("last_active") ?: row.epochMs("session_started"),
            archived = row.flag("archived") ?: false,
            source = row.string("source"),
        )
    }

internal fun JsonObject.toSessionSummary(): SessionSummary? {
    val id = string("id") ?: return null
    return SessionSummary(
        id = id,
        title = string("title"),
        preview = string("preview"),
        model = string("model"),
        source = string("source"),
        startedAtMs = epochMs("started_at"),
        lastActiveAtMs = epochMs("last_active") ?: epochMs("started_at"),
        messageCount = (this["message_count"] as? JsonPrimitive)?.intOrNull ?: 0,
        archived = flag("archived") ?: false,
        pinned = flag("pinned") ?: false,
        unread = flag("unread") ?: false,
        profile = string("profile") ?: string("profile_name"),
        parentSessionId = string("parent_session_id"),
        // Hermes marks a chat unread by setting its read watermark to 0.
        markedUnread = (this["last_read_at"] as? JsonPrimitive)?.doubleOrNull == 0.0,
    )
}

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)

private fun JsonObject.flag(key: String): Boolean? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.booleanOrNull ?: value.intOrNull?.let { it != 0 }
}

private fun JsonObject.epochMs(key: String): Long? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
