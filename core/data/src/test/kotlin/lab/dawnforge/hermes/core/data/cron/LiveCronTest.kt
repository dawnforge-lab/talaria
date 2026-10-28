package lab.dawnforge.hermes.core.data.cron

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.data.chat.HistoryPage
import lab.dawnforge.hermes.core.model.CronDraft
import lab.dawnforge.hermes.core.model.CronJob
import lab.dawnforge.hermes.core.model.CronRun
import lab.dawnforge.hermes.core.model.CronState
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.ConnectionFailure
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * Scheduled jobs against a real Hermes, through the app's own repository. Runs only with
 * `-Phermes.live.file=<path>` and `-Phermes.live.cron=true`: it runs one real model turn.
 *
 * It creates its own job on a schedule that won't come round during the test, and deletes the job
 * and the chats of its runs at the end.
 */
class LiveCronTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `a job is created, edited, paused, run once and deleted`() = runBlocking {
        assumeTrue(LiveHermes.config != null && System.getProperty("hermes.live.cron") == "true")
        val live = LiveHermes.connect(scope)
        val cron = live.cron
        val profile = Profile.DEFAULT
        val marker = UUID.randomUUID().toString().take(8)
        var jobId: String? = null
        val runs = mutableListOf<CronRun>()
        try {
            val refused = cron.create(live.id, profile, CronDraft("Android test $marker", "x", "sometimes"))
            assertIs<ConnectionFailure.Invalid>(assertIs<NetResult.Failure>(refused).failure)

            val created = cron.create(
                live.id,
                profile,
                CronDraft(
                    name = "Android test $marker",
                    prompt = "Reply with the single word pong. (Test job from the Android client, safe to delete.)",
                    // 03:00 on 1 January: never during the test.
                    schedule = "0 3 1 1 *",
                ),
            )
            val job = assertNotNull(assertIs<NetResult.Success<CronJob?>>(created).value)
            jobId = job.id
            println("created ${job.id}: ${job.schedule} next=${job.nextRunAtMs} state=${job.state}")
            assertEquals("0 3 1 1 *", job.scheduleInput)
            assertEquals("local", job.deliver)

            val listed = assertIs<NetResult.Success<List<CronJob>>>(cron.jobs(live.id, profile)).value
            assertTrue(listed.any { it.id == job.id && it.state == CronState.Scheduled }, listed.toString())

            val edited = cron.update(live.id, profile, job.id, CronDraft(name = "Android test $marker (edited)"))
            val afterEdit = assertNotNull(assertIs<NetResult.Success<CronJob?>>(edited).value)
            assertEquals("Android test $marker (edited)", afterEdit.name)
            assertEquals("0 3 1 1 *", afterEdit.scheduleInput)

            val rescheduled = cron.update(live.id, profile, job.id, CronDraft(schedule = "every 720m"))
            assertEquals(
                "every 720m",
                assertIs<NetResult.Success<CronJob?>>(rescheduled).value?.scheduleInput,
            )
            cron.update(live.id, profile, job.id, CronDraft(schedule = "0 3 1 1 *"))

            val paused = assertIs<NetResult.Success<CronJob?>>(cron.pause(live.id, profile, job.id)).value
            assertEquals(CronState.Paused, paused?.state ?: reread(live, job.id)?.state)
            val resumed = assertIs<NetResult.Success<CronJob?>>(cron.resume(live.id, profile, job.id)).value
            assertEquals(CronState.Scheduled, resumed?.state ?: reread(live, job.id)?.state)

            val trigger = assertIs<NetResult.Success<CronTrigger>>(cron.trigger(live.id, profile, job.id)).value
            println("trigger: $trigger")
            // A run still going when the phone stops waiting shows in the runs once stored.
            repeat(RUN_POLLS) {
                val found = assertIs<NetResult.Success<List<CronRun>>>(cron.runs(live.id, profile, job.id)).value
                runs.clear()
                runs += found
                if (found.any(CronRun::opensAsChat) && reread(live, job.id)?.lastRunAtMs != null) return@repeat
                delay(RUN_POLL_MS)
            }
            println("runs: $runs")
            val run = assertNotNull(runs.firstOrNull(CronRun::opensAsChat), runs.toString())
            val history = live.chats.history(live.id, run.profile ?: profile, run.id)
            val page = assertIs<NetResult.Success<HistoryPage>>(history).value
            println("run ${run.id}: ${page.rows.size} rows")
            assertTrue(page.rows.isNotEmpty())

            val after = assertNotNull(reread(live, job.id))
            println("after run: status=${after.lastStatus} error=${after.lastError} last=${after.lastRunAtMs}")
            assertNotNull(after.lastRunAtMs)
        } finally {
            jobId?.let { id ->
                println("delete job: ${cron.delete(live.id, profile, id)}")
                for (run in runs.filter(CronRun::opensAsChat)) {
                    println("delete run ${run.id}: ${live.sessions.delete(live.id, run.profile ?: profile, run.id)}")
                }
                val left = (cron.jobs(live.id, profile) as? NetResult.Success)?.value.orEmpty()
                assertTrue(left.none { it.id == id }, "job $id still listed")
            }
            live.gateway.stop()
        }
        Unit
    }

    private suspend fun reread(live: LiveHermes.Live, jobId: String): CronJob? =
        (live.cron.job(live.id, Profile.DEFAULT, jobId) as? NetResult.Success)?.value

    private companion object {
        const val RUN_POLLS = 36
        const val RUN_POLL_MS = 5_000L
    }
}
