package lab.dawnforge.hermes.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RestartMonitorTest {
    private fun health(
        running: Boolean = true,
        state: String? = "running",
        stamp: String? = "t0",
        shared: List<String> = emptyList(),
    ) = GatewayHealth(running, state, "0.22.2", stamp, null, shared, 0)

    private val before = health()

    @Test
    fun `a gateway seen down and running again is back`() {
        val monitor = RestartMonitor(before, startedAtMs = 0)
        assertEquals(RestartStage.Stopping, monitor.next(1_500, ActionStatus(true, null, listOf("Stopping…")), before))
        assertEquals(
            RestartStage.Starting,
            monitor.next(3_000, ActionStatus(true, null, emptyList()), health(false, "stopped")),
        )
        assertEquals(RestartStage.Starting, monitor.next(4_500, null, health(true, "starting")))
        assertEquals(RestartStage.Done(emptyList()), monitor.next(6_000, null, health(stamp = "t0")))
    }

    @Test
    fun `a new heartbeat counts as restarted, an old one only after a while`() {
        val renewed = RestartMonitor(before, startedAtMs = 0)
        assertEquals(
            RestartStage.Done(emptyList()),
            renewed.next(1_500, ActionStatus(true, null, emptyList()), health(stamp = "t1")),
        )

        val same = RestartMonitor(before, startedAtMs = 0)
        assertEquals(RestartStage.Stopping, same.next(1_500, ActionStatus(true, null, emptyList()), before))
        assertEquals(
            RestartStage.Done(emptyList()),
            same.next(RestartMonitor.SETTLE_MS, ActionStatus(true, null, emptyList()), before),
        )
    }

    @Test
    fun `draining waits, a failed command fails, profiles sharing the gateway are named`() {
        val monitor = RestartMonitor(before, startedAtMs = 0)
        assertEquals(
            RestartStage.Draining,
            monitor.next(1_500, ActionStatus(true, null, emptyList()), health(state = "draining")),
        )
        val failed = RestartMonitor(before, startedAtMs = 0)
        val stage = failed.next(
            1_500,
            ActionStatus(false, 1, listOf("=== restart ===", "Gateway is locked", "")),
            before,
        )
        assertEquals(RestartStage.Failed("Gateway is locked"), stage)

        val shared = RestartMonitor(before, startedAtMs = 0)
        assertEquals(
            RestartStage.Done(listOf("default", "work")),
            shared.next(1_500, ActionStatus(false, 0, emptyList()), health(shared = listOf("default", "work"))),
        )
    }

    @Test
    fun `it gives up after the command ends, saying whether the gateway failed to start`() {
        val monitor = RestartMonitor(before, startedAtMs = 0)
        monitor.next(1_500, ActionStatus(false, 0, emptyList()), health(false, "startup_failed"))
        assertEquals(RestartStage.Starting, monitor.next(50_000, null, health(false, "startup_failed")))
        assertEquals(
            RestartStage.DidntStart,
            monitor.next(1_500 + RestartMonitor.AFTER_EXIT_MS, null, health(false, "startup_failed")),
        )

        val slow = RestartMonitor(before, startedAtMs = 0)
        assertIs<RestartStage.NotBackYet>(
            slow.next(RestartMonitor.OVERALL_MS, ActionStatus(true, null, emptyList()), null),
        )
    }

    @Test
    fun `status is read from the dashboard's reply`() {
        val reply = Json.parseToJsonElement(
            """{"gateway_running": true, "gateway_state": "running", "version": "0.22.2",
               "gateway_updated_at": "2026-10-22T13:00:00Z", "gateway_shared_with": null, "active_agents": 2}""",
        ).jsonObject
        assertEquals(
            GatewayHealth(true, "running", "0.22.2", "2026-10-22T13:00:00Z", null, emptyList(), 2),
            reply.toHealth(),
        )
    }
}
