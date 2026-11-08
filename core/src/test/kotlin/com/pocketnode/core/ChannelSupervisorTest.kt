package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A waiter that records requested waits and returns immediately, so tests run without real time. */
private class FakeWaiter(private val onAwait: (Long) -> Unit = {}) : Waiter {
    val waits = mutableListOf<Long>()
    override fun await(ms: Long): Boolean { waits += ms; onAwait(ms); return false }
    override fun wake() {}
}

class ChannelSupervisorTest {
    @Test
    fun `does not poll while offline and polls as soon as the network is back`() {
        var online = false
        var polls = 0
        val states = mutableListOf<SupervisorState>()
        lateinit var sup: ChannelSupervisor
        val waiter = FakeWaiter { if (it == 60_000L && polls == 0) online = true } // the network returns during the first wait
        sup = ChannelSupervisor({ online }, { polls++; if (polls == 2) sup.stop() }, { states += it }, waiter)
        sup.run()
        assertEquals(2, polls)
        assertEquals(SupervisorState.WaitingForNetwork, states.first())
        assertEquals(SupervisorState.Running, states[1]) // reported before the first poll finishes
        assertEquals(listOf(60_000L), waiter.waits)
        assertTrue(states.last() is SupervisorState.Running)
    }

    @Test
    fun `backs off by doubling up to the maximum and starts over after a success`() {
        var calls = 0
        lateinit var sup: ChannelSupervisor
        val waiter = FakeWaiter()
        val outcomes = listOf(false, false, false, false, true, false, false)
        sup = ChannelSupervisor({ true }, {
            val ok = outcomes[calls++]
            if (calls == outcomes.size) sup.stop()
            if (!ok) error("down")
        }, {}, waiter, minBackoffMs = 1_000, maxBackoffMs = 4_000)
        sup.run()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L, 1_000L), waiter.waits.dropLast(0).take(5))
    }

    @Test
    fun `a failure is reported as a problem with the message and a later success clears it`() {
        var calls = 0
        val states = mutableListOf<SupervisorState>()
        lateinit var sup: ChannelSupervisor
        sup = ChannelSupervisor({ true }, { calls++; if (calls == 1) error("flood") else if (calls == 2) sup.stop() }, { states += it }, FakeWaiter())
        sup.run()
        assertEquals(SupervisorState.Problem("flood"), states[0])
        assertEquals(SupervisorState.Running, states[1])
    }

    @Test
    fun `stop ends the loop even while it is waiting`() {
        val sup = ChannelSupervisor({ false }, {}, {})
        val t = Thread { sup.run() }
        t.start()
        Thread.sleep(50)
        sup.stop()
        t.join(2_000)
        assertTrue(!t.isAlive)
    }

    @Test
    fun `wake cuts a real wait short`() {
        val w = MonitorWaiter()
        var woken: Boolean? = null
        val t = Thread { woken = w.await(10_000) }
        t.start()
        Thread.sleep(50)
        w.wake()
        t.join(2_000)
        assertEquals(true, woken)
        assertEquals(false, MonitorWaiter().await(20)) // times out when nobody wakes it
    }
}
