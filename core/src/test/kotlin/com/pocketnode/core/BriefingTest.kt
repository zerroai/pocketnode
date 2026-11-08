package com.pocketnode.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BriefingTest {
    private val tz = TimeZone.getTimeZone("Africa/Lagos")
    private fun at(d: Int, h: Int, m: Int = 0) = Calendar.getInstance(tz).apply { clear(); set(2026, Calendar.OCTOBER, d, h, m) }.timeInMillis

    @Test
    fun `the briefing is set for today if the time is still ahead, otherwise tomorrow`() {
        assertEquals(at(16, 7, 30), Briefing.nextAt(7, 30, at(15, 22, 0), tz))
        assertEquals(at(15, 7, 30), Briefing.nextAt(7, 30, at(15, 6, 59), tz))
        assertEquals(at(16, 7, 30), Briefing.nextAt(7, 30, at(15, 7, 30), tz), "exactly now means the next one")
        assertTrue(runCatching { Briefing.nextAt(24, 0, 0, tz) }.isFailure)
    }

    @Test
    fun `the request only names tools that work in the background, and the place is tidied`() {
        val p = Briefing.prompt("  Lagos,   Nigeria ")
        assertTrue("weather in Lagos, Nigeria now" in p)
        assertTrue("Do not use the calendar, contacts, photos, camera or email" in p)
        assertTrue("weather" !in Briefing.prompt("").substringBefore("Use only"), "no place, no weather line")
        assertEquals("7:05", BriefingSettings(hour = 7, minute = 5).timeText())
    }
}
