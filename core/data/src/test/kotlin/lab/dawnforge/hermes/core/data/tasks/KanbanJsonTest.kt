package lab.dawnforge.hermes.core.data.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class KanbanJsonTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `the board reads its lanes in workflow order and their tasks`() {
        val board = KanbanJson.board(
            json(
                """{"columns": [
                   {"name": "done", "tasks": [{"id": "t_00000001", "title": "Old", "status": "done"}]},
                   {"name": "ready", "tasks": [{"id": "t_1a2b3c4d", "title": "Write it", "status": "ready",
                     "assignee": "default", "priority": 2, "comment_count": 3,
                     "link_counts": {"parents": 1, "children": 0}, "progress": {"done": 1, "total": 2},
                     "warnings": {"count": 1, "highest_severity": "error"}}, {"title": "no id"}]},
                   {"name": "weird", "tasks": []}],
                   "assignees": ["default"], "latest_event_id": 42, "now": 1000}""",
            ),
        )
        assertEquals(listOf(TaskStatus.Ready, TaskStatus.Done, TaskStatus.Other), board.lanes.map { it.first })
        val task = board.lanes[0].second.single()
        assertEquals("1a2b3c", task.shortId)
        assertEquals(2, task.priority)
        assertEquals(1 to 2, task.progressDone to task.progressTotal)
        assertEquals(1, task.warningCount)
        assertEquals(42L, board.latestEventId)
        assertNull(KanbanJson.board(json("""{"latest_event_id": -1}""")).latestEventId)
    }

    @Test
    fun `lanes say which can be chosen by hand`() {
        assertTrue(TaskStatus.Ready.manual)
        assertFalse(TaskStatus.Running.manual || TaskStatus.Review.manual || TaskStatus.Scheduled.manual)
        assertFalse(TaskStatus.Archived in TaskStatus.MOVABLE)
        assertEquals(TaskStatus.Other, TaskStatus.of("something_new"))
    }

    @Test
    fun `a task's page links its parents and tells what the worker asked`() {
        val detail = KanbanJson.detail(
            json(
                """{"task": {"id": "t_2", "title": "Child", "status": "blocked"},
                   "comments": [{"id": 1, "author": "default", "body": "Done half", "created_at": 10}],
                   "events": [{"id": 5, "kind": "blocked", "payload": {"reason": "Which branch?"}, "created_at": 20},
                              {"id": 4, "kind": "created", "payload": {}, "created_at": 1}],
                   "links": {"parents": ["t_1"], "children": []},
                   "link_tasks": [{"id": "t_1", "title": "Parent", "status": "done"}],
                   "runs": [{"id": 9, "profile": "default", "status": "blocked", "outcome": "blocked",
                             "summary": "Need input", "started_at": 15}]}""",
            ),
        )!!
        assertEquals(listOf(LinkedTask("t_1", "Parent", TaskStatus.Done)), detail.parents)
        assertEquals("Which branch?", detail.question)
        assertEquals("Done half", detail.comments.single().body)
        assertFalse(detail.runs.single().failed)
    }

    @Test
    fun `logs, triage answers and event frames`() {
        assertEquals(
            WorkerLog(true, 12, "hello", false),
            KanbanJson.log(json("""{"exists": true, "size_bytes": 12, "content": "hello", "truncated": false}""")),
        )
        val coloured = KanbanJson.log(
            json("""{"exists": true, "content": "\u001b[2;3mThinking\u001b[0m OK\u001b]0;title\u0007"}"""),
        )
        assertEquals("Thinking OK", coloured.content)
        val triage = KanbanJson.triage(json("""{"ok": false, "reason": "No aux model"}"""))
        assertFalse(triage.ok)
        assertEquals("No aux model", triage.reason)
        assertEquals(
            7L to setOf("t_1", "t_2"),
            KanbanJson.frame(
                json("""{"events": [{"task_id": "t_1"}, {"task_id": "t_2"}, {"task_id": "t_1"}], "cursor": 7}"""),
            ),
        )
    }
}
