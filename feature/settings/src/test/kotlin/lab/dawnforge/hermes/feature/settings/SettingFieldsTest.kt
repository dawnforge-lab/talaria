package lab.dawnforge.hermes.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.data.settings.ConfigSchema

class SettingFieldsTest {
    @Test
    fun `fields this Hermes lacks are left out, then empty cards`() {
        val schema = ConfigSchema(mapOf("approvals.mode" to ConfigSchema.Field("select", emptyList())))
        val advanced = HermesPage.Advanced.cards.shown(schema)
        // Every Advanced field needs the schema, and this one lists none of them.
        assertTrue(advanced.isEmpty())
        val safety = HermesPage.Workspace.cards.shown(schema).flatMap(Card::fields).map(Field::key)
        assertTrue("approvals.mode" in safety)
        assertTrue("security.redact_secrets" in safety)
        assertTrue("approvals.timeout" !in safety)
    }

    @Test
    fun `without a schema every field shows`() {
        HermesPage.entries.forEach { page -> assertEquals(page.cards, page.cards.shown(ConfigSchema())) }
    }

    @Test
    fun `each key is on one page once`() {
        val keys = HermesPage.entries.flatMap { page -> page.cards.flatMap(Card::fields).map(Field::key) }
        assertEquals(keys.size, keys.toSet().size)
    }
}
