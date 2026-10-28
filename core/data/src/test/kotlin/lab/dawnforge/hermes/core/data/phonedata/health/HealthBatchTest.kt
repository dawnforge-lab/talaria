package lab.dawnforge.hermes.core.data.phonedata.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The night and batch rules, with iOS's own cases (HealthSyncTests). */
class HealthBatchTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun at(text: String) = Instant.parse(text)

    private fun segment(id: String, stage: HealthSleepStage, from: String, to: String, source: String, watch: Boolean) =
        HealthSleepSegment(id, stage, at(from), at(to), source, watch)

    @Test
    fun `a night picks the watch and is named by its morning`() {
        val watch = listOf(
            segment("w1", HealthSleepStage.Core, "2026-10-12T23:00:00Z", "2026-10-13T02:00:00Z", "Watch", true),
            segment("w2", HealthSleepStage.Awake, "2026-10-13T02:00:00Z", "2026-10-13T02:10:00Z", "Watch", true),
            segment("w3", HealthSleepStage.Deep, "2026-10-13T02:10:00Z", "2026-10-13T03:10:00Z", "Watch", true),
            segment("w4", HealthSleepStage.Rem, "2026-10-13T03:10:00Z", "2026-10-13T06:10:00Z", "Watch", true),
        )
        // More time asleep, but the watch wins.
        val other =
            listOf(
                segment(
                    "o1",
                    HealthSleepStage.Asleep,
                    "2026-10-12T22:00:00Z",
                    "2026-10-13T08:00:00Z",
                    "Sleep App",
                    false,
                ),
            )
        val nights = HealthSleepNights.summaries(other + watch, utc)
        assertEquals(1, nights.size)
        val night = nights.first()
        assertEquals("2026-10-13", night.date)
        assertEquals("Watch", night.source)
        assertEquals(HealthValue.Number(420.0), night.data["asleep"])
        assertEquals(HealthValue.Number(60.0), night.data["deep"])
        assertEquals(HealthValue.Number(180.0), night.data["rem"])
        assertEquals(HealthValue.Number(180.0), night.data["core"])
        assertEquals(HealthValue.Number(10.0), night.data["awake"])
        assertEquals(HealthValue.Number(1.0), night.data["wake_count"])
        assertEquals(HealthValue.Number(430.0), night.data["in_bed"], "no in-bed segments: first to last")
        assertEquals(HealthValue.Text("2026-10-12T23:00:00Z"), night.data["bedtime"])
        assertNull(night.data["unspecified"])

        // Without a watch, the source with the most sleep.
        val phoneOnly = HealthSleepNights.summaries(
            other +
                segment("p1", HealthSleepStage.Asleep, "2026-10-13T00:00:00Z", "2026-10-13T05:00:00Z", "Phone", false),
            utc,
        )
        assertEquals("Sleep App", phoneOnly.first().source)
        assertEquals(HealthValue.Number(600.0), phoneOnly.first().data["unspecified"])

        // A nap after noon belongs to the next morning's night.
        assertEquals(LocalDate.parse("2026-10-14"), HealthSleepNights.nightEnding(at("2026-10-13T13:00:00Z"), utc))
        val window = HealthSleepNights.window(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-13"), utc)
        assertEquals(at("2026-10-09T12:00:00Z") to at("2026-10-12T12:00:00Z"), window)
    }

    @Test
    fun `splitting keeps each window whole`() {
        val bucket = HealthBucket(at("2026-10-13T00:00:00Z"), at("2026-10-13T01:00:00Z"), sum = 12.0)
        val batch = HealthBatch(
            computedAt = at("2026-10-13T12:00:00Z"),
            deviceName = "Phone",
            zone = utc,
            categories = listOf(HealthCategory.Activity),
            detailed = emptyList(),
            windows = (0 until 3).map { index ->
                HealthWindow(
                    "steps",
                    HealthGrain.Hour,
                    "count",
                    at("2026-10-13T00:00:00Z"),
                    at("2026-10-14T00:00:00Z"),
                    List(4 + index) {
                        bucket
                    },
                )
            },
            deleted = listOf("a", "b"),
        )
        val parts = batch.split(limit = 8)
        assertEquals(
            listOf(listOf(4), listOf(5), listOf(6)),
            parts.map { part ->
                part.windows.map { it.buckets.size }
            },
        )
        assertEquals(listOf("a", "b"), parts.flatMap { it.deleted })
        assertTrue(parts.all { it.itemCount <= 8 })

        val json = parts[0].wire()
        assertEquals(listOf("activity"), json["categories"]!!.jsonArray.map { it.jsonPrimitive.content })
        val window = json["windows"]!!.jsonArray.first().jsonObject
        assertEquals("2026-10-13T00:00:00Z", window["from"]!!.jsonPrimitive.content)
        assertEquals(12.0, window["buckets"]!!.jsonArray.first().jsonObject["sum"]!!.jsonPrimitive.double)
        // The zone's own id: "Z" for UTC here, "Europe/London" and the like on a phone.
        assertEquals("Z", json["device"]!!.jsonObject["time_zone"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a window's print changes with what it carries and with what's shared`() {
        val window =
            HealthWindow(
                "steps",
                HealthGrain.Day,
                "count",
                at("2026-10-13T00:00:00Z"),
                at("2026-10-14T00:00:00Z"),
                emptyList(),
            )
        val batch = HealthBatch(
            computedAt = at("2026-10-13T12:00:00Z"),
            deviceName = "Phone",
            zone = utc,
            categories = listOf(HealthCategory.Activity),
            detailed = emptyList(),
            windows = listOf(window),
        )
        val print = batch.windowPrints().single()
        assertEquals("steps|day|2026-10-13T00:00:00Z|2026-10-14T00:00:00Z", print.first)
        // A new computation time or batch id says nothing new.
        assertEquals(
            print,
            batch.copy(computedAt = at("2026-10-13T13:00:00Z"), batchId = "other").windowPrints().single(),
        )
        val filled = window.copy(
            buckets = listOf(HealthBucket(at("2026-10-13T00:00:00Z"), at("2026-10-14T00:00:00Z"), sum = 5.0)),
        )
        assertTrue(print.second != batch.copy(windows = listOf(filled)).windowPrints().single().second)
        assertTrue(
            print.second != batch.copy(detailed = listOf(HealthCategory.Activity)).windowPrints().single().second,
        )
    }
}
