package lab.dawnforge.hermes.core.data.session

import kotlin.test.Test
import kotlin.test.assertEquals
import lab.dawnforge.hermes.core.model.Profile

class ProfileOrderTest {
    private fun profile(name: String, displayName: String? = null) =
        Profile(name, displayName, isDefault = name == Profile.DEFAULT, model = null, provider = null)

    private val listed = listOf(profile("work"), profile(Profile.DEFAULT), profile("alice", "Alice"), profile("bob"))

    @Test
    fun `without an order the default comes first, then by name`() {
        val arranged = ProfileRepository.arrange(listed, order = emptyList(), defaultName = null)
        assertEquals(listOf(Profile.DEFAULT, "alice", "bob", "work"), arranged.map { it.name })
        assertEquals("Hermes", arranged.first().label)
    }

    @Test
    fun `the saved order comes first, and names no longer there drop out`() {
        val arranged = ProfileRepository.arrange(
            listed,
            order = listOf("work", "gone", Profile.DEFAULT),
            defaultName = "Home",
        )
        assertEquals(listOf("work", Profile.DEFAULT, "alice", "bob"), arranged.map { it.name })
        assertEquals("Home", arranged[1].label)
    }
}
