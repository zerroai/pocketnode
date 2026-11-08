package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Behavior every [RunStore] must have. */
abstract class RunStoreContract {
    abstract fun newStore(): RunStore

    @Test
    fun `claim records the user message and increments the fence`() {
        val s = newStore()
        val a = s.claimRun("s", "r1", "one")
        val b = s.claimRun("s", "r2", "two")
        assertEquals(1, a.fence)
        assertEquals(2, b.fence)
        assertEquals(listOf(Msg.User("one"), Msg.User("two")), s.history("s"))
    }

    @Test
    fun `finish appends the assistant message and closes the run`() {
        val s = newStore()
        val r = s.claimRun("s", "r1", "hi")
        assertEquals(listOf(r), s.unfinishedRuns())
        s.finishRun(r, RunOutcome.ANSWERED, "hello")
        assertEquals(listOf(Msg.User("hi"), Msg.Assistant("hello")), s.history("s"))
        assertTrue(s.unfinishedRuns().isEmpty())
    }

    @Test
    fun `steps round-trip and are ordered, and putting an index again replaces it`() {
        val s = newStore()
        val r = s.claimRun("s", "r1", "go")
        val call = ToolCall("c1", "echo", """{"q":"a \"b\""}""")
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.PLANNED, call = call))
        s.putStep(r, Step(0, StepKind.MODEL, StepStatus.DONE, text = "thinking", calls = listOf(call)))
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.DONE, text = "result ✓", call = call))
        s.putStep(r, Step(2, StepKind.MODEL, StepStatus.PLANNED, toolFree = true))
        assertEquals(
            listOf(
                Step(0, StepKind.MODEL, StepStatus.DONE, text = "thinking", calls = listOf(call)),
                Step(1, StepKind.TOOL, StepStatus.DONE, text = "result ✓", call = call),
                Step(2, StepKind.MODEL, StepStatus.PLANNED, toolFree = true),
            ),
            s.steps("r1"),
        )
    }

    @Test
    fun `a superseded run cannot put steps or finish`() {
        val s = newStore()
        val old = s.claimRun("s", "r1", "first")
        s.claimRun("s", "r2", "second")
        assertFailsWith<StaleFenceException> { s.putStep(old, Step(0, StepKind.MODEL, StepStatus.PLANNED)) }
        assertFailsWith<StaleFenceException> { s.finishRun(old, RunOutcome.ANSWERED, "late") }
        assertTrue(s.steps("r1").isEmpty())
        assertEquals(listOf(Msg.User("first"), Msg.User("second")), s.history("s"))
    }

    @Test
    fun `sessions are independent`() {
        val s = newStore()
        s.claimRun("a", "r1", "x")
        val b = s.claimRun("b", "r2", "y")
        assertEquals(1, b.fence)
        assertEquals(listOf(Msg.User("y")), s.history("b"))
    }

    @Test
    fun `sessions list the last message, title and order by recent activity`() {
        val s = newStore()
        s.claimRun("a", "r1", "first chat")
        Thread.sleep(5)
        val b = s.claimRun("b", "r2", "second chat")
        s.setSessionTitle("b", "Amaka")
        s.finishRun(b, RunOutcome.ANSWERED, "reply to amaka")
        val list = s.sessions()
        assertEquals(listOf("b", "a"), list.map { it.id })
        assertEquals("Amaka", list[0].title)
        assertEquals("reply to amaka", list[0].lastText)
        assertEquals(false, list[0].lastIsUser)
        assertEquals("", list[1].title)
        assertEquals(true, list[1].lastIsUser)
        Thread.sleep(5)
        s.claimRun("a", "r3", "back to first")
        assertEquals(listOf("a", "b"), s.sessions().map { it.id })
    }

    @Test
    fun `deleting all sessions leaves an empty store that still works`() {
        val s = newStore()
        val r = s.claimRun("a", "r1", "hello")
        s.putStep(r, Step(0, StepKind.MODEL, StepStatus.PLANNED))
        s.deleteAllSessions()
        assertTrue(s.sessions().isEmpty())
        assertTrue(s.history("a").isEmpty())
        assertTrue(s.unfinishedRuns().isEmpty())
        assertTrue(s.steps("r1").isEmpty())
        assertEquals(1, s.claimRun("a", "r2", "again").fence)
    }

    @Test
    fun `finished tool steps appear in the timeline but never in the history the model sees`() {
        val s = newStore()
        val r = s.claimRun("s", "r1", "take a photo")
        val ok = ToolCall("c1", "camera.look", "{}"); val bad = ToolCall("c2", "weather.now", "{}")
        s.putStep(r, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(ok, bad)))
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.PLANNED, call = ok))
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.RUNNING, call = ok))
        assertEquals(listOf<Msg>(Msg.User("take a photo")), s.timeline("s"), "a tool that is still running leaves no row yet")
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.DONE, text = """{"image":"mc-image:0123456789abcdef"}""", call = ok))
        s.putStep(r, Step(2, StepKind.TOOL, StepStatus.FAILED, text = "Error: offline", call = bad))
        s.finishRun(r, RunOutcome.ANSWERED, "Done.")
        assertEquals(
            listOf(Msg.User("take a photo"), Msg.ToolResult("", "camera.look", """{"image":"mc-image:0123456789abcdef"}"""), Msg.ToolResult("", "weather.now", "Error: offline"), Msg.Assistant("Done.")),
            s.timeline("s"),
        )
        assertEquals(listOf(Msg.User("take a photo"), Msg.Assistant("Done.")), s.history("s"))
        assertEquals("Done.", s.sessions().single().lastText)
    }

    @Test
    fun `a run that ended with a model error can be reopened and continues, keeping its steps`() {
        val s = newStore()
        val r = s.claimRun("s", "r1", "look")
        val call = ToolCall("c1", "camera.look", "{}")
        s.putStep(r, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(call)))
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.DONE, text = "photo", call = call))
        s.putStep(r, Step(2, StepKind.MODEL, StepStatus.PLANNED))
        s.finishRun(r, RunOutcome.FAILED, MODEL_ERROR + "Could not reach the model.")
        assertTrue(s.unfinishedRuns().isEmpty())

        val again = s.reopenFailed("s")!!
        assertEquals("r1", again.runId)
        assertTrue(again.fence > r.fence)
        assertEquals(listOf(again), s.unfinishedRuns())
        assertEquals(3, s.steps("r1").size, "the work already done is kept")
        assertEquals(listOf(Msg.User("look")), s.history("s"), "the error message is taken off the screen")
        assertFailsWith<StaleFenceException> { s.putStep(r, Step(2, StepKind.MODEL, StepStatus.DONE, text = "late")) }
        s.finishRun(again, RunOutcome.ANSWERED, "A cat.")
        assertEquals(listOf(Msg.User("look"), Msg.Assistant("A cat.")), s.history("s"))
    }

    @Test
    fun `only a model error can be reopened`() {
        val s = newStore()
        assertEquals(null, s.reopenFailed("nothing"))
        val r = s.claimRun("s", "r1", "hi")
        assertEquals(null, s.reopenFailed("s"), "a run still in progress")
        s.finishRun(r, RunOutcome.ANSWERED, "hello")
        assertEquals(null, s.reopenFailed("s"), "an answered run")
        val r2 = s.claimRun("s", "r2", "again")
        s.finishRun(r2, RunOutcome.FAILED, "I could not produce an answer.")
        assertEquals(null, s.reopenFailed("s"), "a failure that is not a model error")
    }

    @Test
    fun `one conversation can be deleted without touching the others`() {
        val s = newStore()
        val a = s.claimRun("a", "r1", "first")
        s.putStep(a, Step(0, StepKind.MODEL, StepStatus.DONE, text = "x"))
        s.finishRun(a, RunOutcome.ANSWERED, "answer a")
        s.claimRun("b", "r2", "second")
        assertTrue(s.deleteSession("a"))
        assertEquals(listOf("b"), s.sessions().map { it.id })
        assertTrue(s.history("a").isEmpty())
        assertTrue(s.steps("r1").isEmpty())
        assertTrue(!s.deleteSession("a"), "a second delete finds nothing")
        assertEquals(listOf(Msg.User("second")), s.history("b"))
    }

    @Test
    fun `pinned conversations come first and unpinning puts them back`() {
        val s = newStore()
        s.claimRun("old", "r1", "old one")
        Thread.sleep(5)
        s.claimRun("new", "r2", "new one")
        assertEquals(listOf("new", "old"), s.sessions().map { it.id })
        s.setPinned("old", true)
        assertEquals(listOf("old", "new"), s.sessions().map { it.id })
        assertTrue(s.sessions().first().pinned)
        s.setPinned("old", false)
        assertEquals(listOf("new", "old"), s.sessions().map { it.id })
    }

    @Test
    fun `search finds a title or any question or answer, ignoring case, and treats percent and underscore as plain characters`() {
        val s = newStore()
        val a = s.claimRun("a", "r1", "What is the weather in LAGOS today?")
        s.finishRun(a, RunOutcome.ANSWERED, "It is warm.")
        val b = s.claimRun("b", "r2", "Save 50% off my invoice_total")
        s.finishRun(b, RunOutcome.ANSWERED, "Done.")
        s.setSessionTitle("c", "Packing list")
        s.claimRun("c", "r3", "what to bring")
        assertEquals(listOf("a"), s.searchSessions("lagos").map { it.session.id })
        assertTrue("LAGOS" in s.searchSessions("lagos").first().snippet)
        assertEquals(listOf("b"), s.searchSessions("50%").map { it.session.id })
        assertEquals(listOf("b"), s.searchSessions("invoice_total").map { it.session.id })
        assertEquals(emptyList(), s.searchSessions("50_"), "an underscore is not a wildcard")
        assertEquals(listOf("c"), s.searchSessions("PACKING").map { it.session.id })
        assertEquals(emptyList(), s.searchSessions("   "))
        assertEquals(emptyList(), s.searchSessions("nothing like this"))
    }

    @Test
    fun `search does not look inside tool results`() {
        val s = newStore()
        val r = s.claimRun("a", "r1", "go")
        s.putStep(r, Step(1, StepKind.TOOL, StepStatus.DONE, text = "secret-result", call = ToolCall("c1", "t", "{}")))
        s.finishRun(r, RunOutcome.ANSWERED, "ok")
        assertEquals(emptyList(), s.searchSessions("secret-result"))
    }

    @Test
    fun `an imported conversation is listed at its own time, keeps every message, and never overwrites one that exists`() {
        val s = newStore()
        s.claimRun("mine", "r1", "kept")
        val msgs = listOf(Msg.User("q"), Msg.ToolResult("", "clock.now", "12:00"), Msg.Assistant("a"))
        assertTrue(s.importSession("old", "Old chat", msgs, 1_000L))
        assertEquals(listOf(Msg.User("q"), Msg.Assistant("a")), s.history("old"))
        assertEquals(3, s.timeline("old").size)
        assertEquals("Old chat", s.sessions().first { it.id == "old" }.title)
        assertEquals("mine", s.sessions().first().id, "a recent chat is above an old import")
        assertTrue(!s.importSession("mine", "Other", listOf(Msg.User("different")), 5L))
        assertEquals(listOf(Msg.User("kept")), s.history("mine"))
    }
}
