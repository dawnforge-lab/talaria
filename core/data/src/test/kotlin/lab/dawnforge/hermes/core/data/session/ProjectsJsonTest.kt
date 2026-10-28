package lab.dawnforge.hermes.core.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ProjectsJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val tree = json(
        """{"projects":[
          {"id":"p_1a2b3c4d","label":"Skunkworks","path":"D:\\work\\skunk","color":"#aa3300","isAuto":false,
           "isNoProject":false,"sessionCount":2,"sessionIds":["s1","s2"],
           "previewSessions":[{"id":"s1","title":"Plan","last_active":1792689000,"message_count":4}]},
          {"id":"__no_project__","label":"Home","path":null,"isNoProject":true,"isAuto":false,"sessionCount":1,
           "previewSessions":[]},
          {"id":"auto:D:\\hermes","label":"hermes","isAuto":true,"sessionCount":9,
           "repos":[{"id":"r","label":"hermes","path":"D:\\hermes"}]},
          {"id":"x_legacy","name":"Old","session_count":"2","folders":[{"path":"C:\\old"}]},
          {"label":"no id"}]}""",
    )

    @Test
    fun `the tree lists Home first, then the busiest, then by name`() {
        val projects = ProjectsJson.tree(tree)

        assertEquals(listOf("Home", "hermes", "Old", "Skunkworks"), projects.map { it.title })
        assertTrue(projects.first().isHome)
        assertNull(projects.first().primaryPath)
    }

    @Test
    fun `a project reads its folder, colour, chats and whether it can be edited`() {
        val projects = ProjectsJson.tree(tree).associateBy { it.title }

        val made = projects.getValue("Skunkworks")
        assertEquals("D:\\work\\skunk", made.primaryPath)
        assertEquals("#aa3300", made.color)
        assertEquals(setOf("s1", "s2"), made.sessionIds)
        assertEquals("Plan", made.previewSessions.single().title)
        assertTrue(made.editable)
        assertTrue(made.canTakeChats)

        // A found project gets its folder from its repository, and can't be edited.
        val found = projects.getValue("hermes")
        assertEquals("D:\\hermes", found.primaryPath)
        assertFalse(found.editable)

        // Without the flag, an id not made by Hermes' projects store counts as found.
        val legacy = projects.getValue("Old")
        assertTrue(legacy.isAuto)
        assertEquals(2, legacy.sessionCount)
        assertEquals("C:\\old", legacy.primaryPath)

        val home = projects.getValue("Home")
        assertFalse(home.editable)
        assertFalse(home.canTakeChats)
    }

    @Test
    fun `a project's chats come by folder, named by repository when there are several`() {
        val detail = ProjectsJson.detail(
            json(
                """{"project":{"id":"p_1","label":"Skunk","repos":[
                  {"label":"api","groups":[
                    {"id":"g1","label":"main","path":"D:\\api","sessions":[{"id":"a"}]},
                    {"id":"g2","label":"empty","path":"D:\\api\\x","sessions":[]}]},
                  {"label":"web","groups":[
                    {"id":"g3","label":"D:\\web","path":"D:\\web","sessions":[{"id":"b"},{"id":"c"}]}]}]}}""",
            ),
        )!!

        assertEquals(listOf("api · main", "web · D:\\web"), detail.lanes.map { it.title })
        assertEquals("D:\\api", detail.lanes[0].path)
        // A lane named by its own path doesn't show the path twice.
        assertNull(detail.lanes[1].path)
        assertEquals(listOf("b", "c"), detail.lanes[1].sessions.map { it.id })
    }

    @Test
    fun `a project without folders shows its newest chats as Recent`() {
        val detail = ProjectsJson.detail(
            json("""{"project":{"id":"p_1","label":"Skunk","repos":[],"previewSessions":[{"id":"a"}]}}"""),
        )!!

        val lane = detail.lanes.single()
        assertNull(lane.title)
        assertEquals("a", lane.sessions.single().id)
    }

    @Test
    fun `an unknown project is null`() {
        assertNull(ProjectsJson.detail(json("""{"project":null}""")))
        assertNull(ProjectsJson.project(json("""{"label":"x"}""").jsonObject))
    }
}
