package com.pocketnode.core

import java.util.Calendar
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderStoreTest {
    private val lagos = TimeZone.getTimeZone("Africa/Lagos")
    private val newYork = TimeZone.getTimeZone("America/New_York")
    private var clock = 1_000_000_000_000L
    private val store = ReminderStore(JdbcSqlDb(":memory:")) { clock }
    private val min = 60_000L
    private val day = 24 * 60 * min

    private fun at(tz: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = Calendar.getInstance(tz).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test
    fun `reminders are saved in time order, can be read back and cancelled`() {
        val late = store.add("  call   Ada ", clock + 2 * day)
        val soon = store.add("stand up", clock + 5 * min)
        assertEquals("call Ada", late.text, "spaces are tidied")
        assertEquals(listOf("stand up", "call Ada"), store.list().map { it.text })
        assertEquals(soon, store.get(soon.id))
        assertTrue(store.cancel(soon.id)); assertTrue(!store.cancel(soon.id))
        assertEquals(listOf("call Ada"), store.list().map { it.text })
        assertTrue(late.id != soon.id)
    }

    @Test
    fun `due reminders are the ones whose time has come, including ones missed while the phone was off`() {
        val a = store.add("a", clock + 10 * min); val b = store.add("b", clock + 20 * min); store.add("c", clock + 3 * day)
        assertTrue(store.due().isEmpty())
        clock += 30 * min
        assertEquals(listOf(a.id, b.id), store.due().map { it.id })
        clock += 4 * day // the phone was off for days
        assertEquals(3, store.due().size)
    }

    @Test
    fun `a one-off reminder disappears when it fires and a repeating one moves on`() {
        val once = store.add("once", clock + min)
        val daily = store.add("daily", clock + min, "daily")
        clock += 10 * min
        assertNull(store.fired(once.id))
        assertNull(store.get(once.id))
        val moved = store.fired(daily.id)!!
        assertEquals(true, moved.at > clock)
        assertEquals(moved.at, store.get(daily.id)!!.at)
        assertNull(store.fired(9999), "an unknown id is ignored")
    }

    @Test
    fun `bad reminders are refused with a reason an owner can read`() {
        fun why(block: () -> Unit) = assertFailsWith<IllegalArgumentException> { block() }.message!!
        assertTrue(why { store.add("   ", clock + min) }.contains("what the reminder is for"))
        assertTrue(why { store.add("x".repeat(201), clock + min) }.contains("200"))
        assertTrue(why { store.add("old", clock - 10 * min) }.contains("already passed"))
        assertTrue(why { store.add("far", clock + 400 * day) }.contains("a year"))
        assertTrue(why { store.add("x", clock + min, "hourly") }.contains("daily, weekly"))
        store.add("just now is fine", clock - 30_000)
        repeat(ReminderStore.MAX_PENDING - 1) { store.add("r$it", clock + min) }
        assertTrue(why { store.add("one too many", clock + min) }.contains("Cancel some"))
    }

    @Test
    fun `daily and weekly repeats keep the same clock time, across daylight saving`() {
        // New York clocks go forward on 2026-03-08. A 09:00 reminder stays 09:00 local.
        val sat = at(newYork, 2026, 3, 7, 9)
        val next = Recurrence.next(sat, "daily", sat + 1, newYork)!!
        assertEquals(at(newYork, 2026, 3, 8, 9), next)
        assertEquals(23 * 60 * min, next - sat, "that day is an hour shorter")
        assertEquals(at(newYork, 2026, 3, 14, 9), Recurrence.next(sat, "weekly", sat + 1, newYork))
        assertNull(Recurrence.next(sat, null, 0, newYork))
        assertNull(Recurrence.next(sat, "monthly", 0, newYork))
    }

    @Test
    fun `weekday reminders skip the weekend and catch up after a long gap`() {
        val fri = at(lagos, 2026, 10, 16, 8) // a Friday
        assertEquals(at(lagos, 2026, 10, 19, 8), Recurrence.next(fri, "weekdays", fri + 1, lagos), "Monday")
        val mon = at(lagos, 2026, 10, 19, 8)
        assertEquals(at(lagos, 2026, 10, 20, 8), Recurrence.next(mon, "weekdays", mon + 1, lagos))
        // The phone was off for 10 days: the next time is still in the future and on a weekday.
        val now = fri + 10 * day + 3 * 60 * min
        val n = Recurrence.next(fri, "weekdays", now, lagos)!!
        assertTrue(n > now && n - now < 4 * day)
        assertTrue(Calendar.getInstance(lagos).apply { timeInMillis = n }.get(Calendar.DAY_OF_WEEK) !in listOf(Calendar.SATURDAY, Calendar.SUNDAY))
    }

    @Test
    fun `a weekday reminder that starts on a weekend moves to Monday`() {
        val tz = java.util.TimeZone.getDefault()
        val sat = java.util.Calendar.getInstance(tz).apply { set(2026, java.util.Calendar.OCTOBER, 17, 9, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val first = Recurrence.firstOccurrence(sat, "weekdays", tz)
        assertEquals(java.util.Calendar.MONDAY, java.util.Calendar.getInstance(tz).apply { timeInMillis = first }.get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals(sat, Recurrence.firstOccurrence(sat, "daily", tz))
    }

    @Test
    fun `a clock time starts at the next such time`() {
        val tz = java.util.TimeZone.getDefault()
        fun at(d: Int, h: Int, m: Int = 0) = java.util.Calendar.getInstance(tz).apply { set(2026, java.util.Calendar.OCTOBER, d, h, m, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        fun day(t: Long) = java.util.Calendar.getInstance(tz).apply { timeInMillis = t }.get(java.util.Calendar.DAY_OF_MONTH)
        assertEquals(at(16, 9), Recurrence.nextAtClock("9:00", "weekdays", at(15, 22, 58), tz))
        assertEquals(at(15, 23), Recurrence.nextAtClock("23:00", "daily", at(15, 22, 58), tz))
        assertEquals(19, day(Recurrence.nextAtClock("09:00", "weekdays", at(16, 10), tz)))
        assertTrue(runCatching { Recurrence.nextAtClock("25:00", "daily", 0, tz) }.isFailure)
    }
}
