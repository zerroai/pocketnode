package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementTest {
    private val day = Entitlement.DAY

    @Test
    fun `a new trial lasts fourteen days and counts down in whole days rounded up`() {
        val t = Entitlement.startTrial(1_000)
        assertTrue(t.isActive(1_000))
        assertEquals(14, t.daysLeft(1_000))
        assertEquals(14, t.daysLeft(1_000 + day - 1)) // a partial day still counts as a day
        assertEquals(13, t.daysLeft(1_000 + day))
        assertEquals(1, t.daysLeft(1_000 + 13 * day + 5))
    }

    @Test
    fun `a trial ends exactly at its end time and never shows negative days`() {
        val t = Entitlement.startTrial(0)
        val end = 14 * day
        assertTrue(t.isActive(end - 1))
        assertFalse(t.isActive(end))
        assertEquals(0, t.daysLeft(end))
        assertEquals(0, t.daysLeft(end + 100 * day))
    }

    @Test
    fun `no plan is never active and a plan without an end date never expires`() {
        assertFalse(Entitlement(Plan.None).isActive(0))
        val forever = Entitlement(Plan.Personal, startedAtMillis = 5)
        assertTrue(forever.isActive(Long.MAX_VALUE / 2))
        assertNull(forever.daysLeft(10))
    }

    @Test
    fun `the store round-trips an entitlement`() {
        val s = InMemoryEntitlementStore()
        assertEquals(Plan.None, s.load().plan)
        s.save(Entitlement.startTrial(42))
        assertEquals(Entitlement.startTrial(42), s.load())
    }
}
