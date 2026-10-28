package lab.dawnforge.hermes.core.data.gateway

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionHoldsTest {
    private val holds = ConnectionHolds()

    @Test
    fun `the connection is held until every hold is released`() {
        assertFalse(holds.held.value)
        val conversation = holds.hold()
        val call = holds.hold()
        assertTrue(holds.held.value)
        conversation()
        assertTrue(holds.held.value)
        call()
        assertFalse(holds.held.value)
    }

    @Test
    fun `releasing a hold twice releases it once`() {
        val first = holds.hold()
        val second = holds.hold()
        first()
        first()
        assertTrue(holds.held.value)
        second()
        assertFalse(holds.held.value)
    }
}
