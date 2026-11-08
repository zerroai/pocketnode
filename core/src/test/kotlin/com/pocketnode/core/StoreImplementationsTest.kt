package com.pocketnode.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryRunStoreTest : RunStoreContract() {
    override fun newStore(): RunStore = InMemoryRunStore()
}

class SqlRunStoreTest : RunStoreContract() {
    override fun newStore(): RunStore = SqlRunStore(JdbcSqlDb(":memory:"))

    private class FakeSend : Tool {
        override val spec = ToolSpec("send", "send", "{}", idempotent = false)
        override val needs = emptySet<Permission>()
        var calls = 0
        override fun call(args: String): String { calls++; return "sent" }
    }

    /** The real crash case: state written by one process is picked up by a new one from the same file. */
    @Test
    fun `a new process recovers an interrupted run from the database file`() {
        val file = Files.createTempFile("mc-test", ".db").toFile().apply { deleteOnExit() }
        val call = ToolCall("c1", "send", "{}")

        // Process 1: model asked for a tool, tool was marked RUNNING, then the process died.
        JdbcSqlDb(file.path).use { db ->
            val store = SqlRunStore(db)
            val run = store.claimRun("s", "r1", "send hi")
            store.putStep(run, Step(0, StepKind.MODEL, StepStatus.DONE, calls = listOf(call)))
            store.putStep(run, Step(1, StepKind.TOOL, StepStatus.RUNNING, call = call))
        }

        // Process 2: fresh objects, same file.
        JdbcSqlDb(file.path).use { db ->
            val store = SqlRunStore(db)
            val tool = FakeSend()
            val runtime = AgentRuntime(store, { _, _ -> ModelReply("unused") }, listOf(RegisteredTool(tool, "p", emptySet())), PermissionBroker(), Budget.TierA)
            val results = runtime.recover()
            assertEquals(0, tool.calls)
            assertEquals(RunOutcome.NEEDS_CONFIRMATION, results.single().outcome)
            assertTrue(store.unfinishedRuns().isEmpty())
            assertEquals(2, store.history("s").size)
        }
    }
}
