package lab.dawnforge.hermes.core.data.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import lab.dawnforge.hermes.core.data.phonedata.location.SavedPlace

class MapsDirectionsTest {
    private val home = SavedPlace(id = "p1", name = "Home", latitude = 51.5014, longitude = -0.1419)

    @Test
    fun `a saved place's name wins and goes as its coordinates`() {
        val destination = MapsDirections.destination(" home ", listOf(home))
        assertEquals(MapsDestination.Point(51.5014, -0.1419, "Home"), destination)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=51.501400%2C-0.141900&travelmode=driving",
            MapsDirections.link(destination!!, MapsMode.Driving),
        )
        assertEquals("Home", MapsDirections.label(destination))
    }

    @Test
    fun `words go as they are for Maps to find, with the mode`() {
        val destination = MapsDirections.destination("Heathrow Terminal 5", emptyList())
        assertEquals(MapsDestination.Text("Heathrow Terminal 5"), destination)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=Heathrow+Terminal+5&travelmode=bicycling",
            MapsDirections.link(destination!!, MapsMode.Cycling),
        )
    }

    @Test
    fun `coordinates in range are a point, out of range they're words`() {
        assertEquals(MapsDestination.Point(48.8584, 2.2945), MapsDirections.destination("48.8584, 2.2945", emptyList()))
        assertEquals(MapsDestination.Text("95.0,10.0"), MapsDirections.destination("95.0,10.0", emptyList()))
    }

    @Test
    fun `me and place ids aren't sent to Maps`() {
        val me = MapsDirections.destination("Current location", emptyList())
        assertEquals(MapsDestination.Me, me)
        assertNull(MapsDirections.link(me!!, MapsMode.Walking))
        val id = MapsDirections.destination("place_id: ChIJ123", emptyList())
        assertEquals(MapsDestination.PlaceId("ChIJ123"), id)
        assertNull(MapsDirections.link(id!!, MapsMode.Walking))
        assertNull(MapsDirections.destination("place_id:", emptyList()))
        assertNull(MapsDirections.destination(" \u200b ", emptyList()))
    }

    @Test
    fun `a navigate prompt reads its target, and an unknown mode drops it`() {
        val reply = Json.parseToJsonElement(
            """{"ok":true,"prompts":[
                {"id":"a","title":"Directions to Home?","maps":{"to":"Home","mode":"walking"}},
                {"id":"b","title":"Directions?","maps":{"to":"Home","mode":"hovercraft"}},
                {"id":"c","title":"Directions?","maps":{"to":"Home"}},
                {"id":"d","title":"Send it?"}
            ]}""",
        )
        val prompts = PresentJson.prompts(reply)!!.associateBy { it.id }
        assertEquals(MapsTarget("Home", MapsMode.Walking), prompts.getValue("a").maps)
        assertNull(prompts.getValue("b").maps)
        assertEquals(MapsTarget("Home", MapsMode.Driving), prompts.getValue("c").maps)
        assertNull(prompts.getValue("d").maps)
        assertEquals("maps-navigate", PresentJson.FEATURES.first())
    }

    @Test
    fun `only a navigate that was sent names its prompt`() {
        val sent = """{"ok":true,"action":"navigate","status":"sent","prompt_id":"pr_1","to":"Home"}"""
        assertEquals("pr_1", MapsDirections.navigatePromptId(JsonPrimitive(sent)))
        assertEquals("pr_1", MapsDirections.navigatePromptId(Json.parseToJsonElement(sent)))
        val failed = """{"ok":false,"action":"navigate","error":"not_set_up"}"""
        assertNull(MapsDirections.navigatePromptId(JsonPrimitive(failed)))
        assertNull(MapsDirections.navigatePromptId(JsonPrimitive("""{"ok":true,"action":"search"}""")))
        assertNull(MapsDirections.navigatePromptId(JsonPrimitive("not json")))
        assertNull(MapsDirections.navigatePromptId(null))
    }
}
