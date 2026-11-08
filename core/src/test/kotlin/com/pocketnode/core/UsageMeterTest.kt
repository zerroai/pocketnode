package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsageMeterTest {
    private var day = "20261023"
    private val kv = InMemoryPackKv()
    private val meter = UsageMeter(kv) { day }

    @Test fun `no limit means never blocked`() {
        meter.add(10_000_000)
        assertNull(meter.blockedReason())
        assertEquals(0L, meter.limit)
    }

    @Test fun `a limit blocks once the day's use reaches it, and says so plainly`() {
        meter.limit = 100_000
        meter.add(60_000)
        assertNull(meter.blockedReason())
        meter.add(40_000)
        val why = assertNotNull(meter.blockedReason())
        assertTrue("100 thousand tokens" in why && "Raise it in Settings" in why)
    }

    @Test fun `the count starts again the next day`() {
        meter.limit = 1_000
        meter.add(5_000)
        assertNotNull(meter.blockedReason())
        day = "20261024"
        assertEquals(0L, meter.usedToday())
        assertNull(meter.blockedReason())
        meter.add(10)
        assertEquals(10L, meter.usedToday())
    }

    @Test fun `the limit and the count survive a restart`() {
        meter.limit = 300_000; meter.add(1234)
        val again = UsageMeter(kv) { day }
        assertEquals(300_000L, again.limit)
        assertEquals(1234L, again.usedToday())
    }

    @Test fun `estimates round up and ignore nothing or negative use`() {
        assertEquals(1L, UsageMeter.estimate(1))
        assertEquals(2L, UsageMeter.estimate(5))
        assertEquals(0L, UsageMeter.estimate(0))
        meter.add(-5); meter.add(0)
        assertEquals(0L, meter.usedToday())
    }

    @Test fun `limits are described in words`() {
        assertEquals("no limit", UsageMeter.describe(0))
        assertEquals("300 thousand tokens", UsageMeter.describe(300_000))
        assertEquals("3 million tokens", UsageMeter.describe(3_000_000))
        assertEquals("500 tokens", UsageMeter.describe(500))
    }
}
