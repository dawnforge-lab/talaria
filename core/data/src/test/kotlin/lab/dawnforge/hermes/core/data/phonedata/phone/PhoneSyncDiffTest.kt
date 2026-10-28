package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine.Companion.comparableStatus
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine.Companion.diff
import lab.dawnforge.hermes.core.data.phonedata.phone.PhoneSyncEngine.Companion.hash

class PhoneSyncDiffTest {
    private fun event(id: String, title: String) = buildJsonObject {
        put("id", id)
        put("title", title)
    }

    @Test
    fun `only new and changed events go, and the ones gone are deleted`() {
        val first = diff(listOf(event("e_a", "Standup"), event("e_b", "Lunch")), emptyMap(), full = false)
        assertEquals(2, first.upsert.size)

        val second = diff(listOf(event("e_a", "Standup"), event("e_c", "Gym")), first.hashes, full = false)
        assertEquals(listOf("e_c"), second.upsert.map { (it["id"] as JsonPrimitive).content })
        assertEquals(listOf("e_b"), second.delete)
    }

    @Test
    fun `a whole send carries everything and no deletions, and so does a cut list`() {
        val sent = diff(listOf(event("e_a", "Standup")), emptyMap(), full = false).hashes
        val whole = diff(listOf(event("e_a", "Standup")), sent + ("e_gone" to "x"), full = true)
        assertEquals(1, whole.upsert.size)
        assertEquals(emptyList(), whole.delete)
        assertEquals(emptyList(), diff(emptyList(), sent, full = false, truncated = true).delete)
    }

    @Test
    fun `hashes don't depend on key order`() {
        val a = JsonObject(mapOf("x" to JsonPrimitive(1), "y" to JsonPrimitive(2)))
        val b = JsonObject(mapOf("y" to JsonPrimitive(2), "x" to JsonPrimitive(1)))
        assertEquals(hash(a), hash(b))
    }

    @Test
    fun `status compares in 5 percent battery steps above 20 percent, without its time`() {
        fun status(battery: Double, at: String) = buildJsonObject {
            put("at", at)
            put("battery", battery)
        }
        assertEquals(hash(comparableStatus(status(0.83, "a"))), hash(comparableStatus(status(0.81, "b"))))
        assertEquals(false, hash(comparableStatus(status(0.15, "a"))) == hash(comparableStatus(status(0.14, "a"))))
    }
}
