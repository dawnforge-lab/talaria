package lab.dawnforge.hermes.core.data.phonedata.location

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The precision rules and the batch, as iOS's `LocationSharingTests`, with its fixtures. */
class LocationRulesTest {
    private val at = 1_760_430_000_000L
    private val london = LocationTime(ZoneId.of("Europe/London"))
    private val home =
        SavedPlace("p_home", "Home", PlaceKind.Home, latitude = 51.5321, longitude = -0.0755, radius = 150.0)
    private val work =
        SavedPlace("p_work", "Work", PlaceKind.Work, latitude = 51.5155, longitude = -0.0922, radius = 200.0)
    private val area = LocationArea("Shoreditch", "London", "England", "United Kingdom", "GB")

    private fun fix(lat: Double, lon: Double, accuracy: Double = 12.0, source: FixSource = FixSource.Message) =
        FixRecord("f_test", source, RawFix(lat, lon, accuracy, at, speed = 1.23, course = 87.6), area)

    private fun wire(
        fix: FixRecord,
        precision: LocationPrecision,
        places: List<SavedPlace> = listOf(home, work),
        approximate: Boolean = false,
    ) = LocationRules.wire(fix, precision, places, approximate, london)

    private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.content

    @Test
    fun `area names fit what the host takes`() {
        val long = "Ä".repeat(120)
        val wired = LocationArea(long, "London", long + "🇬🇧", "United Kingdom", "GB").wire()
        for (key in listOf("name", "region")) {
            val value = wired.text(key)!!
            assertEquals(80, value.codePointCount(0, value.length), key)
            assertTrue(long.startsWith(value), key)
        }
        assertEquals("London", wired.text("locality"))
        assertEquals("GB", wired.text("country_code"))
    }

    @Test
    fun `places precision names the place and nothing else`() {
        val json = wire(fix(51.5322, -0.0754), LocationPrecision.Places)
        assertEquals("p_home", json.text("place_id"))
        assertEquals("places", json.text("precision"))
        assertEquals("message", json.text("source"))
        for (key in listOf("lat", "lon", "accuracy_m", "area", "distances_m", "speed_mps", "course")) {
            assertNull(json[key], key)
        }
        val away = wire(fix(51.60, -0.20), LocationPrecision.Places)
        assertNull(away["place_id"])
        assertNull(away["lat"])
    }

    @Test
    fun `area precision rounds to about a kilometre`() {
        val json = wire(fix(51.54367, -0.08123, accuracy = 30.0), LocationPrecision.Area)
        assertEquals(51.54, json["lat"]!!.jsonPrimitive.double)
        assertEquals(-0.08, json["lon"]!!.jsonPrimitive.double)
        assertEquals(1_000, json["accuracy_m"]!!.jsonPrimitive.int)
        assertNull(json["speed_mps"])
        assertNull(json["course"])
        assertNull(json["place_id"])
        assertEquals("London", json["area"]!!.jsonObject.text("locality"))
        val distances = json["distances_m"]!!.jsonObject
        assertEquals(setOf("p_home", "p_work"), distances.keys)
        distances.values.forEach { assertEquals(0, it.jsonPrimitive.int % 100) }

        // A poor measurement keeps its own, larger, accuracy.
        val coarse = wire(fix(51.54367, -0.08123, accuracy = 1_800.0), LocationPrecision.Area)
        assertEquals(1_800, coarse["accuracy_m"]!!.jsonPrimitive.int)
    }

    @Test
    fun `precise precision keeps the measurement`() {
        val json = wire(fix(51.5322, -0.0754), LocationPrecision.Precise)
        assertEquals(51.5322, json["lat"]!!.jsonPrimitive.double)
        assertEquals(-0.0754, json["lon"]!!.jsonPrimitive.double)
        assertEquals(12, json["accuracy_m"]!!.jsonPrimitive.int)
        assertEquals("p_home", json.text("place_id"))
        assertEquals(1.2, json["speed_mps"]!!.jsonPrimitive.double)
        assertEquals(88.0, json["course"]!!.jsonPrimitive.double)
        val distances = json["distances_m"]!!.jsonObject
        distances.values.forEach { assertEquals(0, it.jsonPrimitive.int % 10) }
        assertTrue(distances["p_home"]!!.jsonPrimitive.int < 50)
    }

    @Test
    fun `a private place sends only its id`() {
        val private = home.copy(isPrivate = true)
        for (precision in LocationPrecision.entries) {
            val json = wire(fix(51.5322, -0.0754), precision, places = listOf(private, work))
            assertEquals("p_home", json.text("place_id"), precision.wire)
            assertEquals(setOf("id", "at", "source", "precision", "place_id"), json.keys, precision.wire)
        }
        // Elsewhere, no distance points at the private place.
        val away = wire(fix(51.54, -0.08), LocationPrecision.Precise, places = listOf(private, work))
        assertNull(away["distances_m"]!!.jsonObject["p_home"])
        assertNotNull(away["distances_m"]!!.jsonObject["p_work"])

        val listed = LocationRules.placesWire(listOf(private, work))
        assertEquals(LocationRules.PRIVATE_PLACE_NAME, listed.first().jsonObject.text("name"))
        assertEquals("true", listed.first().jsonObject.text("private"))
        assertEquals("other", listed.first().jsonObject.text("kind"))
        assertEquals("work", listed.last().jsonObject.text("kind"))
        assertEquals("Work", listed.last().jsonObject.text("name"))
    }

    @Test
    fun `approximate only names no place and is never precise`() {
        val json = wire(fix(51.5322, -0.0754), LocationPrecision.Precise, approximate = true)
        assertEquals("area", json.text("precision"))
        assertNull(json["place_id"])
        assertEquals(51.53, json["lat"]!!.jsonPrimitive.double)

        val places = wire(fix(51.5322, -0.0754), LocationPrecision.Places, places = listOf(work), approximate = true)
        assertEquals("places", places.text("precision"))
        assertEquals(setOf("id", "at", "source", "precision"), places.keys)
    }

    @Test
    fun `a private place is hidden even in a rough fix`() {
        val private = home.copy(isPrivate = true)
        for ((accuracy, approximate) in listOf(12.0 to true, 3_000.0 to false, 3_000.0 to true)) {
            val json = wire(fix(51.5322, -0.0754, accuracy), LocationPrecision.Area, listOf(private, work), approximate)
            assertEquals("p_home", json.text("place_id"))
            assertEquals(setOf("id", "at", "source", "precision", "place_id"), json.keys)
        }
    }

    @Test
    fun `halves round away from zero, as on iOS`() {
        assertEquals(-0.08, LocationGeo.rounded(-0.075, 2))
        assertEquals(0.08, LocationGeo.rounded(0.075, 2))
        assertEquals(200, LocationGeo.roundedDistance(150.0, 100.0))
    }

    @Test
    fun `the batch's shape`() {
        val settings = LocationSettings(talking = true, background = true, retentionDays = 30)
        val batch = LocationBatch(
            sentAtMs = at,
            settings = settings,
            time = london,
            places = listOf(home, work),
            knownPlaces = listOf(home, work),
            fix = fix(51.5322, -0.0754, source = FixSource.Locate),
            events = listOf(PlaceEvent("e_1", arrive = false, placeId = "p_home", atMs = at, isPrivate = false)),
            locateId = "l_0123456789abcdef",
        )
        val json = batch.wire()
        assertEquals(batch.batchId, json.text("batch_id"))
        assertTrue(Regex("^[A-Za-z0-9_-]{8,64}$").matches(batch.batchId))
        assertEquals("2025-10-14T09:20:00+01:00", json.text("sent_at"))
        assertEquals("l_0123456789abcdef", json.text("locate_id"))

        val sharing = json["sharing"]!!.jsonObject
        assertEquals("true", sharing.text("talking"))
        assertEquals("precise", sharing.text("talking_precision"))
        assertEquals("area", sharing.text("background_precision"))
        assertEquals(30, sharing["retention_days"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, sharing["paused_until"])
        assertEquals("Europe/London", sharing.text("time_zone"))
        assertNull(sharing["features"])

        val places = json["places"]!!.jsonArray
        assertEquals(2, places.size)
        places.forEach { assertEquals(setOf("id", "name", "kind", "private"), it.jsonObject.keys) }

        // A locate answers at the background precision.
        val sentFix = json["fix"]!!.jsonObject
        assertEquals("locate", sentFix.text("source"))
        assertEquals("area", sentFix.text("precision"))
        assertEquals(51.53, sentFix["lat"]!!.jsonPrimitive.double)

        val event = json["events"]!!.jsonArray.first().jsonObject
        assertEquals("leave", event.text("kind"))
        assertEquals("p_home", event.text("place_id"))
        assertEquals("false", event.text("private"))
    }

    @Test
    fun `a settings-only batch carries no location`() {
        assertEquals(
            setOf("batch_id", "sent_at", "sharing"),
            LocationBatch(sentAtMs = at, settings = LocationSettings()).wire().keys,
        )
    }

    @Test
    fun `pause and layers decide what is shared`() {
        var settings = LocationSettings()
        assertFalse(settings.shares(FixSource.Message, at))
        assertFalse(settings.shares(FixSource.Place, at))

        settings = settings.copy(talking = true)
        assertTrue(settings.shares(FixSource.Message, at))
        assertTrue(settings.shares(FixSource.Call, at))
        assertFalse(settings.shares(FixSource.Significant, at))
        assertFalse(settings.shares(FixSource.Locate, at))

        settings = settings.copy(background = true)
        assertTrue(settings.shares(FixSource.Locate, at))
        assertEquals(LocationPrecision.Precise, settings.precision(FixSource.Message))
        assertEquals(LocationPrecision.Area, settings.precision(FixSource.Significant))

        val hour = 3_600_000L
        settings = settings.copy(pausedUntil = at + hour)
        assertTrue(settings.isPaused(at))
        assertFalse(settings.shares(FixSource.Message, at))
        val utc = LocationTime(ZoneId.of("UTC"))
        assertEquals(utc.format(at + hour), settings.wire(at, utc).text("paused_until"))

        // A pause that has run out shares again and says so.
        assertTrue(settings.shares(FixSource.Message, at + hour + 1_000))
        assertEquals(JsonNull, settings.wire(at + hour + 1_000, utc)["paused_until"])

        settings = settings.copy(pausedUntil = LocationSettings.FOREVER)
        assertTrue(settings.isPaused(at + 10 * 365 * 86_400_000L))
    }

    @Test
    fun `retention is one of the host's choices`() {
        for (days in LocationSettings.RETENTION_CHOICES) {
            assertEquals(
                days,
                LocationSettings(retentionDays = days).wire(at, london)["retention_days"]!!.jsonPrimitive.int,
            )
        }
        assertEquals(7, LocationSettings(retentionDays = 3).wire(at, london)["retention_days"]!!.jsonPrimitive.int)
    }

    @Test
    fun `unsent keeps the latest fix and the last fifty events`() {
        val newer = fix(51.5, -0.1)
        val older = fix(51.4, -0.2).let { it.copy(id = "f_older", raw = it.raw.copy(timeMs = at - 600_000)) }
        var unsent = LocationUnsent().keep(newer, emptyList()).keep(older, emptyList())
        assertEquals("f_test", unsent.fix?.id)

        val events = (0 until 60).map {
            PlaceEvent("e_$it", arrive = true, placeId = "p_home", atMs = at + it, isPrivate = false)
        }
        unsent = unsent.keep(null, events)
        assertEquals(LocationUnsent.MAX_EVENTS, unsent.events.size)
        assertEquals("e_10", unsent.events.first().id)
        assertEquals("e_59", unsent.events.last().id)

        unsent = unsent.remove("f_test", unsent.events.take(10).map { it.id }.toSet())
        assertNull(unsent.fix)
        assertEquals(40, unsent.events.size)
    }

    @Test
    fun `the places digest changes with what the host keeps, not with where they are`() {
        val digest = LocationRules.placesDigest(listOf(home, work))
        assertEquals(digest, LocationRules.placesDigest(listOf(home.copy(latitude = 0.0, radius = 400.0), work)))
        assertFalse(digest == LocationRules.placesDigest(listOf(home.copy(name = "House"), work)))
        assertFalse(digest == LocationRules.placesDigest(listOf(home.copy(isPrivate = true), work)))
    }
}
