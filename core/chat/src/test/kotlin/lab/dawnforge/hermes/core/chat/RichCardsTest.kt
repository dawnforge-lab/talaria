package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lab.dawnforge.hermes.core.model.ChartCard
import lab.dawnforge.hermes.core.model.ChartKind
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.PlacesCard
import lab.dawnforge.hermes.core.model.PlacesKind
import lab.dawnforge.hermes.core.model.RouteMode
import lab.dawnforge.hermes.core.model.StatusCard
import lab.dawnforge.hermes.core.model.StatusPhase
import lab.dawnforge.hermes.core.model.StepsCard
import lab.dawnforge.hermes.core.model.WeatherCard
import lab.dawnforge.hermes.core.model.WeatherCondition

class RichCardsTest {
    private val vectors: JsonObject = Json.parseToJsonElement(
        RichCardsTest::class.java.getResourceAsStream("/cards/card-vectors.json")!!.use {
            it.readBytes().decodeToString()
        },
    ).jsonObject

    /** The plugin's own examples: the phone must normalize every one exactly as the plugin does. */
    @Test
    fun `every shared vector normalizes as the plugin's`() {
        val cases = vectors["cases"]!!.jsonArray
        assertTrue(cases.size >= 38)
        val failures = cases.mapNotNull { case ->
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val got = JsonArray(RichCards.normalize(case.jsonObject["input"]))
            val want = case.jsonObject["output"]!!
            if (same(got, want)) null else "$name\n  got:  $got\n  want: $want"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `a normalized map reads as places with a route`() {
        val cards = RichCards.fromTool(
            arguments = null,
            result = JsonPrimitive(
                """{"ok":true,"cards":[{"type":"map","title":"Dinner","places":[{"name":"A","lat":1,"lon":2},{"name":"B"}],
                   "route":{"to":1,"from":0,"mode":"Walking"}}]}""",
            ),
        )

        val card = assertIs<PlacesCard>(cards.single())
        assertEquals(PlacesKind.Map, card.kind)
        assertEquals(listOf("A", "B"), card.places.map { it.name })
        assertEquals(1.0, card.places.first().lat)
        assertEquals(RouteMode.Walking, card.route?.mode)
    }

    @Test
    fun `cards come from the arguments when the result went well without them`() {
        val arguments = Json.parseToJsonElement(
            """{"title":"x","cards":[{"type":"chart","chart":{"kind":"bar","labels":["a","b"],"series":[{"values":[1,2]}]}}]}""",
        ).jsonObject

        val chart = assertIs<ChartCard>(RichCards.fromTool(arguments, JsonPrimitive("""{"ok":true}""")).single())
        assertEquals(ChartKind.Bar, chart.kind)
        assertEquals(listOf(1.0, 2.0), chart.series.single().values)
        // A refused call shows nothing.
        assertTrue(RichCards.fromTool(arguments, JsonPrimitive("""{"ok":false,"error":"not_talaria"}""")).isEmpty())
    }

    @Test
    fun `weather and status read their conditions and phases`() {
        val cards = RichCards.fromTool(
            null,
            Json.parseToJsonElement(
                """{"ok":true,"cards":[
                  {"type":"weather","source":{"name":"Open-Meteo"},"weather":{"unit":"C","now":{"temp":21,"condition":"partly_cloudy"},
                   "days":[{"day":"Mon","low":10,"high":18,"condition":"heavy_rain","precip":70}]}},
                  {"type":"status","status":{"state":"Shipped","phase":"done","progress":1,"milestones":[{"label":"Packed","done":true}]}}]}""",
            ),
        )

        val weather = assertIs<WeatherCard>(cards[0])
        assertEquals(WeatherCondition.PartlyCloudy, weather.now?.condition)
        assertEquals(WeatherCondition.HeavyRain, weather.days.single().condition)
        val status = assertIs<StatusCard>(cards[1])
        assertEquals(StatusPhase.Done, status.phase)
        assertTrue(status.milestones.single().done)
    }

    @Test
    fun `a stored talaria_show call comes back with its cards`() {
        val rows = Json.parseToJsonElement(
            """
            [
              {"id": 1, "role": "user", "content": "Where should we eat?"},
              {"id": 2, "role": "assistant", "content": "", "tool_calls": [{"id": "c1", "type": "function",
                "function": {"name": "talaria_show", "arguments": "{\"title\": \"Dinner\", \"cards\": []}"}}]},
              {"id": 3, "role": "tool", "tool_call_id": "c1", "tool_name": "talaria_show",
               "content": "{\"ok\": true, \"cards\": [{\"type\": \"place\", \"places\": [{\"name\": \"Kin Khao\"}]}], \"note\": \"shown\"}"},
              {"id": 4, "role": "assistant", "content": "Kin Khao it is."}
            ]
            """,
        ).jsonArray.map { it.jsonObject }

        val tool = normalizeHistory(rows).filterIsInstance<lab.dawnforge.hermes.core.model.ChatItem.Tool>().single()

        val card = assertIs<PlacesCard>(tool.cards.single())
        assertEquals(PlacesKind.Place, card.kind)
        assertEquals("Kin Khao", card.places.single().name)
    }

    /** JSON equal, numbers compared by value (the plugin writes 4.0 where the phone may write 4). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a[it]!!, b[it]!!) }

        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }

        a is JsonPrimitive && b is JsonPrimitive -> when {
            a == JsonNull || b == JsonNull -> a == b
            a.isString || b.isString -> a.isString == b.isString && a.content == b.content
            a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
            else -> a.doubleOrNull == b.doubleOrNull
        }

        else -> false
    }

    @Test
    fun `cards shown through Hermes' tool_call wrapper are drawn from history`() {
        val rows = Json.parseToJsonElement(
            """[{"id": 1, "role": "assistant", "content": "", "timestamp": 1,
                "tool_calls": [{"id": "c1", "type": "function", "function": {"name": "tool_call",
                  "arguments": "{\"calls\":[{\"name\":\"talaria_show\",\"arguments\":{\"title\":\"t\"}}]}"}}]},
               {"id": 2, "role": "tool", "tool_call_id": "c1", "tool_name": "talaria_show", "timestamp": 2,
                "content": "{\"ok\": true, \"cards\": [{\"type\": \"steps\", \"steps\": [{\"text\": \"Boil\"}]}]}"}]""",
        ).jsonArray.map { it.jsonObject }

        val tool = normalizeHistory(rows).filterIsInstance<ChatItem.Tool>().single()

        assertEquals("tool_call", tool.name)
        assertIs<StepsCard>(tool.cards.single())
        assertEquals(listOf("talaria_show"), ToolCalls.names("tool_call", tool.input?.let(::JsonPrimitive)))
        assertEquals(listOf("terminal"), ToolCalls.names("terminal", null))
    }
}
