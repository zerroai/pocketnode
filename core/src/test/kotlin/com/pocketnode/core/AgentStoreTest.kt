package com.pocketnode.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentStoreTest {
    private val z = ZoneId.of("UTC")
    private var clock = java.time.Instant.parse("2026-10-23T08:00:00Z").toEpochMilli()
    private val store = AgentStore(JdbcSqlDb(":memory:")) { clock }
    private fun def(id: String = "a1", t: AgentTrigger = AgentTrigger("daily", 7, 30)) = AgentDef(id, "Rain", t, listOf(AgentStep("notify", tool = "Hi", text = "there")))

    @Test fun saveReadBackAndPlanNextStart() {
        val s = store.save(def(), z)
        assertEquals("Rain", s.def.name)
        assertEquals(java.time.Instant.parse("2026-10-24T07:30:00Z").toEpochMilli(), s.nextAt)
        assertEquals(1, store.list().size)
        assertNull(store.save(def(t = AgentTrigger()), z).let { if (it.nextAt == 0L) null else it })
    }

    @Test fun dueOnlyWhenTimeHasComeAndSwitchedOn() {
        store.save(def(), z)
        assertTrue(store.due(clock).isEmpty())
        assertEquals(1, store.due(clock + 24 * 3600_000L).size)
        store.setEnabled("a1", false, z)
        assertTrue(store.due(clock + 48 * 3600_000L).isEmpty())
    }

    @Test fun finishedMovesOnAndThreeFailuresPause() {
        store.save(def(), z)
        clock += 24 * 3600_000L
        val ok = store.finished("a1", AgentRun(AgentOutcome.DONE, listOf("Step 1: notified")), z)!!
        assertEquals("DONE", ok.lastOutcome)
        assertTrue(ok.nextAt > clock)
        repeat(3) { store.finished("a1", AgentRun(AgentOutcome.FAILED, listOf("Step 1 failed")), z) }
        val p = store.get("a1")!!
        assertTrue(p.state.paused)
        assertEquals(0L, p.nextAt)
        assertTrue(store.due(clock + 10 * 24 * 3600_000L).isEmpty())
        store.setEnabled("a1", true, z)
        assertFalse(store.get("a1")!!.state.paused)
        assertTrue(store.get("a1")!!.nextAt > 0)
    }

    @Test fun savingAgainReplacesAndClearsPause() {
        store.save(def(), z)
        repeat(3) { store.finished("a1", AgentRun(AgentOutcome.FAILED, emptyList()), z) }
        store.save(def().copy(name = "Rain 2"), z)
        assertEquals("Rain 2", store.get("a1")!!.def.name)
        assertFalse(store.get("a1")!!.state.paused)
        assertEquals(1, store.list().size)
    }

    @Test fun removeAndLimit() {
        store.save(def(), z); store.remove("a1"); assertTrue(store.list().isEmpty())
        repeat(AgentStore.MAX_AGENTS) { store.save(def("x$it"), z) }
        assertFailsWith<IllegalArgumentException> { store.save(def("one-too-many"), z) }
        store.save(def("x0").copy(name = "same id is fine"), z)
    }
}
