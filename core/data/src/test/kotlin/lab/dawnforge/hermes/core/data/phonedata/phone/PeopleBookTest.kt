package lab.dawnforge.hermes.core.data.phonedata.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What of a picked contact the host takes, as iOS sends it. */
class PeopleBookTest {
    @Test
    fun `a dialable number is kept, tidied`() {
        assertEquals("+44 20 7946 0958", PeopleBook.cleanPhone("  +44  20\t7946 0958 "))
        assertEquals("(555) 010-0199", PeopleBook.cleanPhone("(555) 010–0199"))
        assertEquals("*31#", PeopleBook.cleanPhone("*31#"))
    }

    @Test
    fun `a number that can't be dialled is dropped`() {
        assertNull(PeopleBook.cleanPhone("call me"))
        assertNull(PeopleBook.cleanPhone("+-()"))
        assertNull(PeopleBook.cleanPhone("1".repeat(41)))
    }

    @Test
    fun `a birthday is kept with or without its year`() {
        assertEquals("1990-04-12", PeopleBook.birthday("1990-04-12"))
        assertEquals("--04-12", PeopleBook.birthday("--04-12"))
        assertNull(PeopleBook.birthday("12 April"))
        assertNull(PeopleBook.birthday("1990-4-12"))
    }

    @Test
    fun `a person goes to the host without empty fields, and labels are capped`() {
        val person = PhonePerson(
            id = "p_0011223344556677",
            name = "Ana",
            phones = listOf(PersonValue("+40 721 000 000", "x".repeat(50))),
        )
        val label = "x".repeat(30)
        val phones = """[{"number":"+40 721 000 000","label":"$label"}]"""
        assertEquals("""{"id":"p_0011223344556677","name":"Ana","phones":$phones}""", person.wire().toString())
    }
}
