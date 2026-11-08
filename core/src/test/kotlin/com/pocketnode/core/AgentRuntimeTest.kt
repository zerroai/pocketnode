package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ScriptedModel(private val replies: MutableList<ModelReply>) : ModelClient {
    val seenToolCounts = mutableListOf<Int>()
    val seenMessages = mutableListOf<List<Msg>>()
    override fun complete(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        seenToolCounts += tools.size
        seenMessages += messages
        return replies.removeAt(0)
    }
}

private class FakeTool(
    name: String,
    idempotent: Boolean,
    override val needs: Set<Permission> = emptySet(),
    private val body: (String) -> String = { "ok" },
) : Tool {
    override val spec = ToolSpec(name, "test tool", "{}", idempotent)
    var calls = 0
    override fun call(args: String): String {
        calls++
        return body(args)
    }
}

class AgentRuntimeTest {
    private val pack = "test.pack"

    private fun runtime(store: RunStore, model: ModelClient, vararg tools: FakeTool, broker: PermissionBroker = PermissionBroker(), budget: Budget = Budget.TierA) =
        AgentRuntime(store, model, tools.map { RegisteredTool(it, pack, it.needs) }, broker, budget)

    @Test
    fun `answers directly without tools`() {
        val store = InMemoryRunStore()
        val r = runtime(store, ScriptedModel(mutableListOf(ModelReply("hi")))).run("s", "hello")
        assertEquals(RunOutcome.ANSWERED, r.outcome)
        assertEquals("hi", r.text)
        assertEquals(listOf(Msg.User("hello"), Msg.Assistant("hi")), store.history("s"))
    }

    @Test
    fun `runs a tool then answers with its result in context`() {
        val tool = FakeTool("echo", idempotent = true) { "got $it" }
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "echo", "x"))), ModelReply("done")))
        val r = runtime(InMemoryRunStore(), model, tool).run("s", "go")
        assertEquals("done", r.text)
        assertEquals(1, tool.calls)
        assertTrue(model.seenMessages[1].contains(Msg.ToolResult("c1", "echo", "got x")))
    }

    @Test
    fun `a stop request ends the run before any tool starts, and says why`() {
        val tool = FakeTool("echo", idempotent = true)
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "echo", "x"))), ModelReply("never")))
        var stop: String? = null
        val db = JdbcSqlDb(":memory:"); val log = ActivityLog(db)
        val rt = AgentRuntime(InMemoryRunStore(), model, { listOf(RegisteredTool(tool, pack, emptySet())) }, PermissionBroker(), Budget.TierA, { stop }, log)
        // The model has answered with a tool call; the owner presses stop before the tool starts.
        val first = ModelClient { m, t -> stop = "Stopped by you."; model.complete(m, t) }
        val r = AgentRuntime(InMemoryRunStore(), first, { listOf(RegisteredTool(tool, pack, emptySet())) }, PermissionBroker(), Budget.TierA, { stop }, log).run("s", "go")
        assertEquals(RunOutcome.FAILED, r.outcome); assertEquals("Stopped by you.", r.text); assertEquals(0, tool.calls)
        assertEquals("Run stopped", log.recent().first().subject)
        assertTrue(rt.run("t", "hi").text == "Stopped by you.", "a paused assistant answers nothing new")
        stop = null
    }

    @Test
    fun `every tool call is recorded in the activity log`() {
        val log = ActivityLog(JdbcSqlDb(":memory:"))
        val ok = FakeTool("echo", idempotent = true) { "got $it" }
        val bad = FakeTool("boom", idempotent = true) { error("no network") }
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "echo", "{\"a\":1}"), ToolCall("c2", "boom", "{}"))), ModelReply("done")))
        AgentRuntime(InMemoryRunStore(), model, { listOf(RegisteredTool(ok, pack, emptySet()), RegisteredTool(bad, pack, emptySet())) }, PermissionBroker(), Budget.TierA, RunGuard.None, log).run("s", "go")
        val rows = log.recent().reversed()
        assertEquals(listOf("echo", "boom"), rows.map { it.subject })
        assertTrue("{\"a\":1}" in rows[0].detail && !rows[0].problem)
        assertTrue("no network" in rows[1].detail && rows[1].problem)
        assertTrue(rows.all { it.detail.startsWith("For \u201cgo\u201d: ") }, "each step says which message it was for: ${rows.map { it.detail }}")
    }

    @Test
    fun `the activity record names the message a tool ran for, shortened, without attachment references`() {
        val log = ActivityLog(JdbcSqlDb(":memory:"))
        val ok = FakeTool("echo", idempotent = true) { "got $it" }
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "echo", "{}"))), ModelReply("done")))
        val long = "Please remind me about the dentist on Tuesday and also check what the weather will be like in Lagos that day " + "x".repeat(40)
        AgentRuntime(InMemoryRunStore(), model, { listOf(RegisteredTool(ok, pack, emptySet())) }, PermissionBroker(), Budget.TierA, RunGuard.None, log).run("s", long)
        val d = log.recent().single().detail
        assertTrue(d.startsWith("For \u201cPlease remind me about the dentist on Tuesday"), d)
        assertTrue("\u2026\u201d: " in d && d.length <= 400, d)
    }

    @Test
    fun `permission denial is reported to the model and the tool does not run`() {
        val tool = FakeTool("send", idempotent = false, needs = setOf(Permission("net", "api.telegram.org")))
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "send", "{}"))), ModelReply("sorry")))
        // Declared but never granted.
        val r = runtime(InMemoryRunStore(), model, tool).run("s", "send it")
        assertEquals(0, tool.calls)
        assertEquals("sorry", r.text)
        val result = model.seenMessages[1].filterIsInstance<Msg.ToolResult>().single()
        assertTrue(result.text.startsWith("Denied"))
    }

    @Test
    fun `tool results are truncated to the budget`() {
        val tool = FakeTool("big", idempotent = true) { "x".repeat(100) }
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "big", ""))), ModelReply("ok")))
        runtime(InMemoryRunStore(), model, tool, budget = Budget(5, 10, 10)).run("s", "go")
        val text = model.seenMessages[1].filterIsInstance<Msg.ToolResult>().single().text
        assertTrue(text.startsWith("xxxxxxxxxx\n[truncated 90 chars]"))
    }

    @Test
    fun `forces a tool-free answer when the model returns nothing after tools`() {
        val tool = FakeTool("t", idempotent = true)
        val model = ScriptedModel(mutableListOf(ModelReply("", listOf(ToolCall("c1", "t", ""))), ModelReply(""), ModelReply("final")))
        val r = runtime(InMemoryRunStore(), model, tool).run("s", "go")
        assertEquals("final", r.text)
        assertEquals(listOf(1, 1, 0), model.seenToolCounts)
    }

    @Test
    fun `stops looping at the model-call budget and asks for a plain answer`() {
        val tool = FakeTool("t", idempotent = true)
        val loop = ModelReply("", listOf(ToolCall("c", "t", "")))
        val model = ScriptedModel(mutableListOf(loop, loop, ModelReply("wrapped up")))
        val r = runtime(InMemoryRunStore(), model, tool, budget = Budget(2, 100, 10)).run("s", "go")
        assertEquals("wrapped up", r.text)
        assertEquals(0, model.seenToolCounts.last())
    }

    @Test
    fun `recovery reruns an idempotent tool that was interrupted`() {
        val store = InMemoryRunStore()
        val tool = FakeTool("read", idempotent = true)
        val run = store.claimRun("s", "r1", "go")
        store.putStep(run, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(ToolCall("c1", "read", ""))))
        store.putStep(run, Step(1, StepKind.TOOL, StepStatus.RUNNING, call = ToolCall("c1", "read", "")))

        val results = runtime(store, ScriptedModel(mutableListOf(ModelReply("recovered"))), tool).recover()
        assertEquals(1, tool.calls)
        assertEquals("recovered", results.single().text)
    }

    @Test
    fun `recovery does not repeat a non-idempotent tool that may have already run`() {
        val store = InMemoryRunStore()
        val tool = FakeTool("send", idempotent = false)
        val run = store.claimRun("s", "r1", "send hi")
        store.putStep(run, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(ToolCall("c1", "send", ""))))
        store.putStep(run, Step(1, StepKind.TOOL, StepStatus.RUNNING, call = ToolCall("c1", "send", "")))

        val results = runtime(store, ScriptedModel(mutableListOf()), tool).recover()
        assertEquals(0, tool.calls)
        assertEquals(RunOutcome.NEEDS_CONFIRMATION, results.single().outcome)
        assertTrue(store.unfinishedRuns().isEmpty())
    }

    @Test
    fun `recovery runs a planned tool that never started even if not idempotent`() {
        val store = InMemoryRunStore()
        val tool = FakeTool("send", idempotent = false)
        val run = store.claimRun("s", "r1", "send hi")
        store.putStep(run, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(ToolCall("c1", "send", ""))))
        store.putStep(run, Step(1, StepKind.TOOL, StepStatus.PLANNED, call = ToolCall("c1", "send", "")))

        val results = runtime(store, ScriptedModel(mutableListOf(ModelReply("sent"))), tool).recover()
        assertEquals(1, tool.calls)
        assertEquals("sent", results.single().text)
    }

    @Test
    fun `a superseded run cannot write`() {
        val store = InMemoryRunStore()
        val old = store.claimRun("s", "r1", "first")
        store.claimRun("s", "r2", "second")
        assertFailsWith<StaleFenceException> { store.putStep(old, Step(0, StepKind.MODEL, StepStatus.PLANNED)) }
        assertFailsWith<StaleFenceException> { store.finishRun(old, RunOutcome.ANSWERED, "late") }
    }

    @Test
    fun `model failure ends the run as failed with a message`() {
        val failing = ModelClient { _, _ -> error("offline") }
        val r = runtime(InMemoryRunStore(), failing).run("s", "hi")
        assertEquals(RunOutcome.FAILED, r.outcome)
        assertTrue(r.text.contains("offline"))
    }

    @Test
    fun `retrying after a dropped connection continues from the failed step and does not run the tool again`() {
        val tool = FakeTool("camera.look", idempotent = false) { """{"image":"mc-image:0123456789abcdef"}""" }
        var calls = 0
        val model = ModelClient { messages, _ ->
            calls++
            when (calls) {
                1 -> ModelReply("", listOf(ToolCall("c1", "camera.look", "{}")))
                2 -> throw java.io.IOException("connection reset")
                else -> {
                    assertTrue(messages.any { it is Msg.ToolResult && it.text.contains("mc-image:") }, "the photo already taken is still in the conversation")
                    ModelReply("It is a cat.")
                }
            }
        }
        val store = InMemoryRunStore()
        val rt = AgentRuntime(store, model, listOf(RegisteredTool(tool, pack, emptySet())), PermissionBroker(), Budget.TierA)
        val first = rt.run("s", "look at this")
        assertEquals(RunOutcome.FAILED, first.outcome)
        assertTrue(first.text.startsWith(MODEL_ERROR) && first.text.contains("Could not reach the model"))
        assertEquals(1, tool.calls)

        val second = rt.retry("s")!!
        assertEquals(RunOutcome.ANSWERED, second.outcome)
        assertEquals("It is a cat.", second.text)
        assertEquals(1, tool.calls, "the camera was not opened a second time")
        assertEquals(listOf<Class<*>>(Msg.User::class.java, Msg.ToolResult::class.java, Msg.Assistant::class.java), store.timeline("s").map { it::class.java })
        assertEquals(null, rt.retry("s"), "nothing left to retry")
    }
}
