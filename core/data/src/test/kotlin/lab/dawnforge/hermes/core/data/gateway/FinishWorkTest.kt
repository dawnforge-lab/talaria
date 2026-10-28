package lab.dawnforge.hermes.core.data.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class FinishWorkTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val inFront = MutableStateFlow(true)
    private val work = WorkOnItsWay()
    private var starts = 0

    init {
        FinishWork(scope.backgroundScope, inFront, work.held) { starts++ }.follow()
        scope.runCurrent()
    }

    @Test
    fun `leaving the app with a send out finishes it out of sight`() {
        val send = work.hold()
        inFront.value = false
        assertEquals(1, starts)
        send()
    }

    @Test
    fun `leaving with nothing on its way starts nothing`() {
        inFront.value = false
        assertEquals(0, starts)
    }

    @Test
    fun `work that begins out of sight isn't covered, as Android allows no start then`() {
        inFront.value = false
        work.hold()
        assertEquals(0, starts)
    }

    @Test
    fun `each time the app leaves with work held, it starts again`() {
        val turn = work.hold()
        inFront.value = false
        inFront.value = true
        inFront.value = false
        assertEquals(2, starts)
        turn()
        inFront.value = true
        inFront.value = false
        assertEquals(2, starts)
    }

    @Test
    fun `work is held until every hold is released, once each`() {
        val send = work.hold()
        val turn = work.hold()
        send()
        send()
        assertEquals(true, work.held.value)
        turn()
        assertEquals(false, work.held.value)
    }
}
