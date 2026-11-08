package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoDeleteTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day
    private fun chat(id: String, ageDays: Long, pinned: Boolean = false) = SessionSummary(id, id, "", false, now - ageDays * day, pinned)

    @Test fun `off by default and only the listed choices are kept`() {
        val a = AutoDelete(InMemoryPackKv())
        assertEquals(0, a.days)
        a.days = 30; assertEquals(30, a.days)
        a.days = 12; assertEquals(0, a.days, "an unknown number means off")
        assertEquals(emptyList(), a.expired(listOf(chat("old", 5000)), now), "off deletes nothing")
    }

    @Test fun `chats older than the choice go, newer and pinned ones stay`() {
        val a = AutoDelete(InMemoryPackKv()); a.days = 30
        val chats = listOf(chat("fresh", 3), chat("edge", 29), chat("old", 31), chat("ancient-pinned", 400, pinned = true), chat("ancient", 400))
        assertEquals(listOf("old", "ancient"), a.expired(chats, now).map { it.id })
        assertEquals(listOf("ancient"), a.expired(chats, now, days = 365).map { it.id }, "a different choice can be tried before it is saved")
    }

    @Test fun `the setting survives a restart and is in plain words`() {
        val kv = InMemoryPackKv(); AutoDelete(kv).days = 90
        assertEquals(90, AutoDelete(kv).days)
        assertEquals(listOf("never", "after a week", "after 30 days", "after 90 days", "after a year"), AutoDelete.CHOICES.map { AutoDelete.describe(it) })
    }
}
