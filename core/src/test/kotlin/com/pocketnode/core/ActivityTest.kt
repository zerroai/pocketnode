package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityTest {
    private var clock = 1_000L
    private val log = ActivityLog(JdbcSqlDb(":memory:")) { clock++ }

    @Test
    fun `events come back newest first, can be filtered, counted and cleared`() {
        log.add(ActivityKind.TOOL, "weather.forecast", "Lagos -> sunny")
        log.add(ActivityKind.MODEL, "Model request", "openrouter.ai, 4 messages")
        log.add(ActivityKind.TOOL, "notes.add", "x", problem = true)
        assertEquals(listOf("notes.add", "Model request", "weather.forecast"), log.recent().map { it.subject })
        assertEquals(listOf("notes.add", "weather.forecast"), log.recent(kind = ActivityKind.TOOL).map { it.subject })
        assertTrue(log.recent().first().problem)
        assertEquals(mapOf(ActivityKind.TOOL to 2, ActivityKind.MODEL to 1), log.counts(0))
        assertEquals(emptyMap(), log.counts(10_000))
        log.clear(); assertEquals(emptyList(), log.recent())
    }

    @Test
    fun `old events are dropped so the log stays small, and long text is cut`() {
        repeat(ActivityLog.KEEP + 400) { log.add(ActivityKind.NETWORK, "request $it", "x".repeat(900)) }
        val all = log.recent(limit = 10_000)
        assertTrue(all.size in ActivityLog.KEEP..ActivityLog.KEEP + 250, "kept ${all.size}")
        assertEquals("request ${ActivityLog.KEEP + 399}", all.first().subject, "the newest is kept")
        assertEquals(400, all.first().detail.length)
    }

    @Test
    fun `a bad row never throws into the assistant`() {
        val broken = ActivityLog(object : SqlDb { var n = 0
            override fun <T> transaction(block: (Tx) -> T): T { if (n++ > 0) throw IllegalStateException("disk full"); return block(object : Tx {
                override fun exec(sql: String, vararg args: Any?) {}
                override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> = emptyList()
            }) }
        })
        broken.add(ActivityKind.TOOL, "x")
    }

    @Test
    fun `the same request repeated straight away is one row with a count`() {
        repeat(5) { log.add(ActivityKind.NETWORK, "api.telegram.org", "POST by the Telegram pack: answered 200") }
        log.add(ActivityKind.NETWORK, "api.open-meteo.com", "GET: answered 200")
        log.add(ActivityKind.NETWORK, "api.telegram.org", "POST by the Telegram pack: answered 200")
        assertEquals(listOf(6, 1), log.recent().map { it.times }, "the repeats are folded into the earlier identical row")
        log.add(ActivityKind.TOOL, "echo", "a"); log.add(ActivityKind.TOOL, "echo", "a")
        assertEquals(2, log.recent(kind = ActivityKind.TOOL).size, "tool uses are never merged")
        assertEquals(9, log.counts(0).values.sum(), "counts add the repeats back")
    }
}

class ActivitySearchTest {
    private var clock = 1_000L
    private val log = ActivityLog(JdbcSqlDb(":memory:")) { clock.also { clock += 1000 } }

    private fun fill() {
        log.add(ActivityKind.TOOL, "weather.now", """{"place":"Lagos"}  ->  27 degrees""")
        log.add(ActivityKind.NETWORK, "api.open-meteo.com", "GET by the Weather pack: answered 200")
        log.add(ActivityKind.TOOL, "mail.send", "Refused: you chose not to allow this", problem = true)
        log.add(ActivityKind.MODEL, "openrouter.ai", "3 messages, 56 tools offered")
        log.add(ActivityKind.SYSTEM, "Data exported", "100% of 5_chats saved")
    }

    @Test fun `search finds words in the subject or the details, ignoring case`() {
        fill()
        assertEquals(listOf("weather.now"), log.recent(query = "LAGOS").map { it.subject })
        assertEquals(setOf("weather.now", "api.open-meteo.com"), log.recent(query = "weather").map { it.subject }.toSet())
        assertEquals(emptyList(), log.recent(query = "nothing like this"))
    }

    @Test fun `percent and underscore in a search are plain characters`() {
        fill()
        assertEquals(listOf("Data exported"), log.recent(query = "100%").map { it.subject })
        assertEquals(listOf("Data exported"), log.recent(query = "5_chats").map { it.subject })
        assertEquals(emptyList(), log.recent(query = "5_c%s"))
    }

    @Test fun `problems only, kind and search combine`() {
        fill()
        assertEquals(listOf("mail.send"), log.recent(problemsOnly = true).map { it.subject })
        assertEquals(listOf("mail.send"), log.recent(kind = ActivityKind.TOOL, problemsOnly = true, query = "refused").map { it.subject })
        assertEquals(emptyList(), log.recent(kind = ActivityKind.NETWORK, problemsOnly = true))
    }

    @Test fun `the saved text lists the oldest first, marks problems, and never loses the details`() {
        fill()
        val text = ActivityText.render(log.recent(), 5_000_000L)
        assertTrue(text.indexOf("weather.now") < text.indexOf("Data exported"), "oldest first")
        assertTrue("!  mail.send" in text)
        assertTrue("27 degrees" in text && "Refused: you chose not to allow this" in text)
        assertTrue(text.startsWith("Pocketnode activity record, saved "))
    }
}
