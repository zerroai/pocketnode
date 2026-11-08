package com.pocketnode.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fake of the Telegram Bot API: queues updates, records sends, honors the offset. */
private class FakeTelegram : HostNet {
    val updates = mutableListOf<Pair<Long, String>>() // update_id to text
    val sent = mutableListOf<String>()
    var failSends = 0
    var requestedUrls = mutableListOf<String>()
    var lastOffset = 0L

    override fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse {
        requestedUrls += url
        val payload = Json.parseToJsonElement(body ?: "{}").jsonObject
        return when {
            url.endsWith("/getUpdates") -> {
                lastOffset = payload["offset"]!!.jsonPrimitive.content.toLong()
                val result = updates.filter { it.first >= lastOffset }.joinToString(",") {
                    """{"update_id":${it.first},"message":{"chat":{"id":777,"first_name":"Ada","last_name":"Obi"},"text":${kotlinx.serialization.json.JsonPrimitive(it.second)}}}"""
                }
                HostResponse(200, """{"ok":true,"result":[$result]}""")
            }
            url.endsWith("/getMe") -> HostResponse(200, """{"ok":true,"result":{"username":"text_p2p_bot"}}""")
            url.endsWith("/sendMessage") -> {
                if (failSends > 0) { failSends--; HostResponse(200, """{"ok":false,"description":"flood"}""") }
                else { sent += payload["text"]!!.jsonPrimitive.content; HostResponse(200, """{"ok":true,"result":{}}""") }
            }
            else -> HostResponse(404, "{}")
        }
    }
}

class TelegramPackTest {
    private val packJs = File("../packs/telegram/pack.js").readText()
    private val declared = setOf(Permission("net", "api.telegram.org"), Permission("secret", "bot_token"))
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterTest fun cleanup() = closeables.forEach { it.close() }

    private fun sandbox(net: HostNet, kv: PackKv, token: String? = "123:ABC"): PackSandbox {
        val broker = PermissionBroker().also { it.grantDefaults("tg", declared) }
        return PackSandbox("tg", declared, broker, net, packJs, kv = kv, secrets = { if (it == "bot_token") token else null }).also { closeables += it }
    }

    private fun runtime(replies: List<String>): AgentRuntime {
        val q = replies.toMutableList()
        return AgentRuntime(InMemoryRunStore(), { _, _ -> ModelReply(q.removeAt(0)) }, emptyList(), PermissionBroker(), Budget.TierA)
    }

    @Test
    fun `a message is answered, sent back and confirmed so it is not seen again`() {
        val tg = FakeTelegram().apply { updates += 10L to "hello" }
        val kv = InMemoryPackKv()
        val runner = ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(listOf("hi there")), kv)
        assertEquals(1, runner.pollOnce())
        assertEquals(listOf("hi there"), tg.sent)
        assertEquals(0, runner.pollOnce())
        assertEquals(11L, tg.lastOffset)
        assertTrue(tg.requestedUrls.all { it.startsWith("https://api.telegram.org/bot123:ABC/") })
    }

    @Test
    fun `a stranger gets no assistant, is told once, and is listed for approval`() {
        val tg = FakeTelegram().apply { updates += 10L to "what are my notes?"; updates += 11L to "hello?" }
        val kv = InMemoryPackKv()
        val access = ChannelAccess(kv, "tg")
        var modelCalls = 0
        val rt = AgentRuntime(InMemoryRunStore(), { _, _ -> modelCalls++; ModelReply("secret answer") }, emptyList(), PermissionBroker(), Budget.TierA)
        val told = mutableListOf<String>()
        val runner = ChannelRunner("telegram", "tg", sandbox(tg, kv), rt, kv, access, onUnapproved = { ch, who -> told += "$ch:$who" })
        assertEquals(2, runner.pollOnce())
        assertEquals(0, modelCalls, "the model is never called for a stranger")
        assertEquals(listOf(ChannelAccess.MESSAGE_FOR_STRANGERS), tg.sent, "told once, not twice")
        assertEquals(1, access.pending().size); assertEquals("what are my notes?", access.pending().single().firstMessage)
        assertEquals(1, told.size)
        assertEquals(0, runner.pollOnce(), "both messages were confirmed, so they are not seen again")
    }

    @Test
    fun `an approved chat is answered, and approval can be taken away`() {
        val tg = FakeTelegram().apply { updates += 20L to "hi" }
        val kv = InMemoryPackKv()
        val access = ChannelAccess(kv, "tg")
        access.noteUnknown("777", "Ada", "hi"); access.approve("777")
        assertTrue(access.isAllowed("777")); assertEquals(0, access.pending().size)
        val runner = ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(listOf("hello Ada")), kv, access)
        runner.pollOnce()
        assertEquals(listOf("hello Ada"), tg.sent)
        access.revoke("777"); assertTrue(!access.isAllowed("777"))
        access.noteUnknown("7", "Bo", "x"); access.dismiss("7")
        assertEquals(0, access.pending().size, "an ignored person can ask again later")
    }

    @Test
    fun `chats that existed before approval are approved once`() {
        val kv = InMemoryPackKv(); val access = ChannelAccess(kv, "tg")
        access.grandfather(listOf(ChannelPerson("42", "Ada")))
        access.revoke("42")
        access.grandfather(listOf(ChannelPerson("42", "Ada")))
        assertTrue(!access.isAllowed("42"), "a removed approval stays removed")
        assertTrue(ChannelAccess(InMemoryPackKv(), "tg").also { it.grandfather(listOf(ChannelPerson("9"))) }.isAllowed("9"))
    }

    @Test
    fun `the chat name reaches the UI callback and the bot check returns the username`() {
        val tg = FakeTelegram().apply { updates += 2L to "hi" }
        val kv = InMemoryPackKv()
        val names = mutableListOf<Pair<String, String>>()
        val sb = sandbox(tg, kv)
        ChannelRunner("telegram", "tg", sb, runtime(listOf("r")), kv) { id, name -> names += id to name }.pollOnce()
        assertEquals(listOf("telegram:777" to "Ada Obi"), names)
        assertEquals("""{"username":"text_p2p_bot"}""", sb.callTool("channel.check", "{}"))
    }

    @Test
    fun `a failed send leaves the message unconfirmed and the retry reuses the saved reply`() {
        val tg = FakeTelegram().apply { updates += 5L to "q"; failSends = 1 }
        val kv = InMemoryPackKv()
        // Only one model reply is available: a retry that re-ran the agent would run out and fail.
        val runner = ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(listOf("answer")), kv)
        assertFailsWith<Exception> { runner.pollOnce() }
        assertTrue(tg.sent.isEmpty())
        assertEquals(1, runner.pollOnce())
        assertEquals(listOf("answer"), tg.sent)
    }

    @Test
    fun `after a restart an already-sent reply is not sent twice`() {
        val tg = FakeTelegram().apply { updates += 1L to "ping" }
        val kv = InMemoryPackKv()
        // Simulate a crash after the send and its marker, before the ack.
        kv.set("tg", "core.reply:1", "pong")
        kv.set("tg", "core.sent:1", "1")
        val runner = ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(emptyList()), kv)
        assertEquals(1, runner.pollOnce())
        assertTrue(tg.sent.isEmpty())
        assertEquals(0, runner.pollOnce()) // acked now
    }

    @Test
    fun `the pack offset survives in pack-private storage across sandboxes`() {
        val tg = FakeTelegram().apply { updates += 3L to "a" }
        val kv = InMemoryPackKv()
        ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(listOf("r")), kv).pollOnce()
        val again = ChannelRunner("telegram", "tg", sandbox(tg, kv), runtime(emptyList()), kv)
        assertEquals(0, again.pollOnce())
        assertEquals("4", kv.get("tg", "offset"))
    }

    @Test
    fun `without a bot token the pack fails with a clear message and sends nothing`() {
        val tg = FakeTelegram()
        val e = assertFailsWith<Exception> { ChannelRunner("telegram", "tg", sandbox(tg, InMemoryPackKv(), token = null), runtime(emptyList()), InMemoryPackKv()).pollOnce() }
        assertTrue(e.message!!.contains("token"), e.message)
        assertTrue(tg.requestedUrls.isEmpty())
    }

    @Test
    fun `the pack cannot read a secret it did not declare`() {
        val broker = PermissionBroker().also { it.grantDefaults("tg", declared) }
        val s = PackSandbox("tg", declared, broker, FakeTelegram(), "pack = { tools: { s: async () => await secret.get('other_secret') } };", secrets = { "leak" }).also { closeables += it }
        val e = assertFailsWith<Exception> { s.callTool("s", "{}") }
        assertTrue(e.message!!.contains("did not declare"), e.message)
    }

    @Test
    fun `sql pack storage keeps packs apart`() {
        val kv = SqlPackKv(JdbcSqlDb(":memory:"))
        kv.set("a", "k", "1")
        assertEquals(null, kv.get("b", "k"))
        kv.set("a", "k", "2")
        assertEquals("2", kv.get("a", "k"))
        kv.delete("a", "k")
        assertEquals(null, kv.get("a", "k"))
    }
}
