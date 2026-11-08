package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A synthetic big history, to catch an operation that gets slow or breaks as the data grows. Bounds are generous so a busy machine does not fail them. */
class LargeHistoryTest {
    private val chats = 1_000
    private val perChat = 20

    private fun timed(label: String, block: () -> Unit): Long {
        val t0 = System.nanoTime(); block(); val ms = (System.nanoTime() - t0) / 1_000_000
        println("LARGE $label: $ms ms")
        return ms
    }

    @Test fun `a thousand chats of twenty messages still list, search, open and export in good time`() {
        val db = JdbcSqlDb(":memory:")
        val store = SqlRunStore(db)
        ReminderStore(db); ActivityLog(db); SqlPackKv(db) // the wipe expects these tables, as in the app
        val filler = "The quick brown fox jumps over the lazy dog. ".repeat(6)
        timed("import $chats chats x $perChat messages") {
            for (c in 0 until chats) {
                val msgs = (0 until perChat).map { i -> if (i % 2 == 0) Msg.User("question $c-$i needle${if (c == 777 && i == 4) "UNIQUE" else ""} $filler") else Msg.Assistant("answer $c-$i $filler", emptyList()) }
                assertTrue(store.importSession("local:big$c", "Chat $c", msgs, 1_700_000_000_000L + c * 1000L))
            }
        }
        val list = ArrayList<SessionSummary>()
        assertTrue(timed("list sessions") { list += store.sessions() } < 3_000)
        assertEquals(chats, list.size)
        val hits = ArrayList<SessionHit>()
        assertTrue(timed("search one word") { hits += store.searchSessions("needleUNIQUE") } < 5_000)
        assertEquals(1, hits.size); assertEquals("local:big777", hits.single().session.id)
        assertTrue(timed("search a common word") { store.searchSessions("quick") } < 8_000)
        assertTrue(timed("open the newest chat") { assertEquals(perChat, store.timeline("local:big${chats - 1}").size) } < 1_000)
        var zip = ByteArray(0)
        assertTrue(timed("export everything") { zip = DataExport.zip(store.sessions().map { s -> ExportChat(s.id, s.title, s.lastActivityMillis, store.timeline(s.id)) }, emptyList(), null, 1_700_000_000_000L) } < 30_000)
        println("LARGE export size: ${zip.size / 1024} KB")
        assertTrue(zip.size > 100_000)
        assertTrue(timed("erase all") { DataWipe(db).erase() } < 5_000)
        assertEquals(0, store.sessions().size)
    }
}
