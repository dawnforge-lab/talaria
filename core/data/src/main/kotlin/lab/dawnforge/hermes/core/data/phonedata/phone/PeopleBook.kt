package lab.dawnforge.hermes.core.data.phonedata.phone

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PersonValue(val value: String, val label: String? = null)

/** Someone the user picked to tell Hermes about, as iOS's `PhonePerson`. */
@Serializable
data class PhonePerson(
    val id: String,
    val name: String,
    val nickname: String? = null,
    /** Who they are to the user ("my sister"), typed here. */
    val relation: String? = null,
    val organization: String? = null,
    val phones: List<PersonValue> = emptyList(),
    val emails: List<PersonValue> = emptyList(),
    /** `YYYY-MM-DD`, or `--MM-DD` without a year. */
    val birthday: String? = null,
) {
    fun wire(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        nickname?.let { put("nickname", it) }
        relation?.let { put("relation", it) }
        organization?.let { put("organization", it) }
        if (phones.isNotEmpty()) put("phones", JsonArray(phones.map { it.wire("number") }))
        if (emails.isNotEmpty()) put("emails", JsonArray(emails.map { it.wire("address") }))
        birthday?.let { put("birthday", it) }
    }

    private fun PersonValue.wire(key: String) = buildJsonObject {
        put(key, value)
        label?.let { put("label", it.take(PERSON_LABEL_CHARS)) }
    }
}

/**
 * The people the user picks to tell Hermes about, as iOS's: read only from the contacts picked, kept
 * on this phone, and sent whole to the host when they change. On Android 17 and later they come
 * from the system contact picker, which shares just the picked people's fields without any
 * permission; earlier, from the classic picker, whose contact can be read with contacts access.
 */
class PeopleBook(
    private val context: Context,
    private val store: DataStore<Preferences>,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    val people: Flow<List<PhonePerson>> = store.data.map { read(it[KEY]) }

    suspend fun wire(): List<JsonObject> = read(store.data.first()[KEY]).map(PhonePerson::wire)

    /**
     * Adds the people [picked] in a contact picker: the system picker's session (any number of
     * people), or the classic picker's one contact. One picked again is refreshed, keeping its
     * relation. Returns how many were read.
     */
    suspend fun add(picked: Uri): Int {
        val people = withContext(Dispatchers.IO) {
            runCatching {
                if (isSession(picked)) readSession(picked) else listOfNotNull(readContact(picked))
            }.getOrDefault(emptyList())
        }
        if (people.isEmpty()) return 0
        store.edit { prefs ->
            var list = read(prefs[KEY])
            for (person in people) {
                val kept = list.firstOrNull { it.id == person.id }
                list = if (kept != null) {
                    list.map { if (it.id == person.id) person.copy(relation = kept.relation) else it }
                } else {
                    (list + person).take(MAX_PEOPLE)
                }
            }
            prefs[KEY] = json.encodeToString(LIST, list)
        }
        return people.size
    }

    suspend fun setRelation(id: String, relation: String) = edit { list ->
        list.map { if (it.id == id) it.copy(relation = relation.trim().take(RELATION_CHARS).ifEmpty { null }) else it }
    }

    suspend fun remove(id: String) = edit { list -> list.filterNot { it.id == id } }

    private suspend fun edit(change: (List<PhonePerson>) -> List<PhonePerson>) {
        store.edit { prefs -> prefs[KEY] = json.encodeToString(LIST, change(read(prefs[KEY]))) }
    }

    private fun read(text: String?): List<PhonePerson> =
        text?.let { runCatching { json.decodeFromString(LIST, it) }.getOrNull() }.orEmpty()

    /** The classic picker's contact: its name, then its own entries (which need contacts access). */
    private fun readContact(contact: Uri): PhonePerson? {
        val resolver = context.contentResolver
        var lookup: String? = null
        var name: String? = null
        resolver.query(contact, arrayOf(Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) {
                lookup = it.getString(0)
                name = it.getString(1)
            }
        }
        val person = PersonRows(lookup ?: contact.lastPathSegment ?: return null, name)
        val data = Uri.withAppendedPath(contact, Contacts.Entity.CONTENT_DIRECTORY)
        val columns =
            arrayOf(Contacts.Entity.MIMETYPE, Contacts.Entity.DATA1, Contacts.Entity.DATA2, Contacts.Entity.DATA3)
        resolver.query(data, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) person.add(cursor.row(0, 1, 2, 3))
        }
        return person.build()
    }

    /**
     * The system picker's session: one row per shared field, in `ContactsContract.Data`'s schema, for
     * every person picked (it takes no selection of its own).
     */
    private fun readSession(session: Uri): List<PhonePerson> {
        val columns = arrayOf(
            Contacts.LOOKUP_KEY,
            Contacts.DISPLAY_NAME_PRIMARY,
            Data.MIMETYPE,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
        )
        val people = LinkedHashMap<String, PersonRows>()
        context.contentResolver.query(session, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val lookup = cursor.getString(0) ?: continue
                people.getOrPut(lookup) { PersonRows(lookup, cursor.getString(1)) }.add(cursor.row(2, 3, 4, 5))
            }
        }
        return people.values.mapNotNull(PersonRows::build)
    }

    private fun Cursor.row(mime: Int, value: Int, type: Int, custom: Int) = Row(
        mime = getString(mime),
        value = getString(value)?.trim().orEmpty(),
        type = if (isNull(type)) 0 else getInt(type),
        custom = getString(custom),
    )

    private class Row(val mime: String?, val value: String, val type: Int, val custom: String?)

    /** One person's fields as they're read, row by row. */
    private inner class PersonRows(private val key: String, name: String?) {
        private val displayName = name?.trim()?.take(NAME_CHARS)?.takeIf(String::isNotEmpty)
        private var nickname: String? = null
        private var organization: String? = null
        private var birthday: String? = null
        private val phones = mutableListOf<PersonValue>()
        private val emails = mutableListOf<PersonValue>()

        fun add(row: Row) {
            val value = row.value
            if (value.isEmpty()) return
            when (row.mime) {
                Phone.CONTENT_ITEM_TYPE -> cleanPhone(value)?.let {
                    if (phones.size < MAX_VALUES && phones.none { known -> known.value == it }) {
                        phones +=
                            PersonValue(it, Phone.getTypeLabel(context.resources, row.type, row.custom)?.toString())
                    }
                }

                Email.CONTENT_ITEM_TYPE -> if ('@' in value && ' ' !in value && value.length <= EMAIL_CHARS &&
                    emails.size < MAX_VALUES && emails.none { it.value == value }
                ) {
                    emails +=
                        PersonValue(value, Email.getTypeLabel(context.resources, row.type, row.custom)?.toString())
                }

                Nickname.CONTENT_ITEM_TYPE -> nickname = value.take(NICKNAME_CHARS)

                Organization.CONTENT_ITEM_TYPE -> organization = value.take(NAME_CHARS)

                Event.CONTENT_ITEM_TYPE -> if (row.type == Event.TYPE_BIRTHDAY) birthday = birthday(value)
            }
        }

        fun build(): PhonePerson? {
            val name = displayName ?: return null
            return PhonePerson(
                id = "p_" + MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(ID_BYTES)
                    .joinToString("") { "%02x".format(it) },
                name = name,
                nickname = nickname,
                organization = organization?.takeIf { it != name },
                phones = phones,
                emails = emails,
                birthday = birthday,
            )
        }
    }

    companion object {
        /** The fields asked of Android 17's contact picker: what Hermes is told about a person. */
        val PICKED_FIELDS = arrayListOf(
            Phone.CONTENT_ITEM_TYPE,
            Email.CONTENT_ITEM_TYPE,
            Event.CONTENT_ITEM_TYPE,
            Nickname.CONTENT_ITEM_TYPE,
            Organization.CONTENT_ITEM_TYPE,
        )

        /** `ContactsPickerSessionContract.AUTHORITY`, named here so older Android needn't load the class. */
        private const val SESSION_AUTHORITY = "com.android.contacts.picker.sessions"

        /** The system picker's session: its authority can carry the user it's for (`0@…`). */
        private fun isSession(picked: Uri) = picked.authority?.substringAfterLast('@') == SESSION_AUTHORITY
        private val KEY = stringPreferencesKey("phonedata.people")
        private val LIST = ListSerializer(PhonePerson.serializer())
        const val MAX_PEOPLE = 200
        const val RELATION_CHARS = 40
        private const val NAME_CHARS = 120
        private const val NICKNAME_CHARS = 60
        private const val EMAIL_CHARS = 254
        private const val MAX_VALUES = 10
        private const val ID_BYTES = 8
        private const val PHONE_CHARS = 40
        private val DIALABLE = Regex("^[0-9+()*#.,;/ -]{1,$PHONE_CHARS}$")

        /** A number kept only when it can be dialled: 1–40 of `0-9+()*#.,;/ -`, with a digit. */
        fun cleanPhone(raw: String): String? {
            val cleaned = raw.replace(Regex("\\s+"), " ").replace(Regex("[‐-—]"), "-").trim()
            return cleaned.takeIf { DIALABLE.matches(it) && it.any(Char::isDigit) }
        }

        /** A birthday as the host takes it: `YYYY-MM-DD`, or `--MM-DD` without a year. */
        fun birthday(raw: String): String? = when {
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(raw) -> raw
            Regex("^--\\d{2}-\\d{2}$").matches(raw) -> raw
            else -> null
        }
    }
}

private const val PERSON_LABEL_CHARS = 30
