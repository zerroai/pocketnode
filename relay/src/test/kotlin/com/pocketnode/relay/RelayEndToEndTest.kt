package com.pocketnode.relay

import com.pocketnode.core.AgentRuntime
import com.pocketnode.core.Budget
import com.pocketnode.core.ChannelRunner
import com.pocketnode.core.HostNet
import com.pocketnode.core.HostResponse
import com.pocketnode.core.HttpHostNet
import com.pocketnode.core.InMemoryPackKv
import com.pocketnode.core.InMemoryRunStore
import com.pocketnode.core.ModelClient
import com.pocketnode.core.ModelReply
import com.pocketnode.core.PackSandbox
import com.pocketnode.core.Permission
import com.pocketnode.core.PermissionBroker
import com.pocketnode.core.RelayClient
import com.pocketnode.core.RelayCredentials
import com.pocketnode.core.RelayException
import com.pocketnode.core.RelayInboxRunner
import com.pocketnode.core.RelayLink
import com.pocketnode.core.RelaySetup
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A stand-in for Telegram's Bot API. Records what the phone asks of it. */
private class FakeTelegram : HostNet {
    val sent = mutableListOf<String>()
    val webhookCalls = mutableListOf<Pair<String, Map<String, String>>>() // method to arguments
    var failSends = 0

    override fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse {
        val args = Json.parseToJsonElement(body ?: "{}").jsonObject.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.value.toString() }
        return when {
            url.endsWith("/setWebhook") -> { webhookCalls += "set" to args; ok() }
            url.endsWith("/deleteWebhook") -> { webhookCalls += "clear" to args; ok() }
            url.endsWith("/sendMessage") -> if (failSends > 0) { failSends--; HostResponse(200, """{"ok":false,"description":"flood"}""") } else { sent += args.getValue("text"); ok() }
            else -> HostResponse(404, "{}")
        }
    }

    private fun ok() = HostResponse(200, """{"ok":true,"result":true}""")
}

class RelayEndToEndTest {
    private val db = JdbcSqlDb(":memory:")
    private val store = RelayStore(db, Sealer(ByteArray(32) { 7 }))
    private val server = RelayServer(store, NoPush, RelayConfig("https://relay.test"), bind = "127.0.0.1").also { it.start() }
    private val client = RelayClient("http://127.0.0.1:${server.port}", HttpHostNet())
    private val telegram = FakeTelegram()
    private val kv = InMemoryPackKv()
    private val declared = setOf(Permission("net", "api.telegram.org"), Permission("secret", "bot_token"))
    private val sandboxes = mutableListOf<PackSandbox>()

    @AfterTest fun cleanup() { sandboxes.forEach { it.close() }; server.stop(); db.close() }

    private fun sandbox(): PackSandbox {
        val broker = PermissionBroker().also { it.grantDefaults("tg", declared) }
        return PackSandbox("tg", declared, broker, telegram, File("../packs/telegram/pack.js").readText(), kv = kv, secrets = { if (it == "bot_token") "123:ABC" else null }).also { sandboxes += it }
    }

    private fun agent(vararg replies: String): AgentRuntime {
        val q = replies.toMutableList()
        return AgentRuntime(InMemoryRunStore(), ModelClient { _, _ -> ModelReply(q.removeAt(0)) }, emptyList(), PermissionBroker(), Budget.TierA)
    }

    /** What Telegram does: POST the update to the address it was given, with the secret it was given. */
    private fun telegramSends(link: RelayLink, update: String, port: Int = server.port): Int {
        val hook = telegram.webhookCalls.last { it.first == "set" }.second
        val path = URL(hook.getValue("url")).path
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("X-Telegram-Bot-Api-Secret-Token", hook.getValue("secret_token"))
        c.outputStream.use { it.write(update.toByteArray()) }
        return c.responseCode
    }

    private fun update(id: Int, text: String) = """{"update_id":$id,"message":{"chat":{"id":55,"first_name":"Ada"},"text":"$text"}}"""

    private fun runner(sb: PackSandbox, link: RelayLink, rt: AgentRuntime, wait: Int = 3): RelayInboxRunner =
        RelayInboxRunner(client, { link.credentials }, "tg", sb, ChannelRunner("telegram", "tg", sb, rt, kv), kv, waitSeconds = wait)

    @Test
    fun `enabling the relay points Telegram at the relay with a secret`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        val call = telegram.webhookCalls.single()
        assertEquals("set", call.first)
        assertEquals("https://relay.test/hook/telegram/${link.routeId}", call.second.getValue("url"))
        assertEquals(link.hookSecret, call.second.getValue("secret_token"))
        assertEquals(1, store.routeCount(link.credentials.deviceId))
    }

    @Test
    fun `a message sent by Telegram is answered on the phone and then forgotten by the relay`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        assertEquals(200, telegramSends(link, update(41, "hello relay")))
        assertEquals(1, store.pending(link.credentials.deviceId))

        assertEquals(1, runner(sb, link, agent("hi from the phone")).pollOnce())
        assertEquals(listOf("hi from the phone"), telegram.sent)
        assertEquals(0, store.pending(link.credentials.deviceId)) // acknowledged, so deleted
    }

    @Test
    fun `messages that arrive while the phone is away are all there when it comes back`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        telegramSends(link, update(1, "one")); telegramSends(link, update(2, "two")); telegramSends(link, update(3, "three"))
        assertEquals(3, runner(sb, link, agent("a", "b", "c")).pollOnce())
        assertEquals(listOf("a", "b", "c"), telegram.sent)
    }

    @Test
    fun `a waiting phone gets a message straight away`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        val r = runner(sb, link, agent("quick"), wait = 20)
        var handled = -1
        val t = Thread { handled = r.pollOnce() }
        t.start()
        Thread.sleep(400)
        val started = System.nanoTime()
        telegramSends(link, update(9, "now"))
        t.join(6_000)
        assertEquals(1, handled)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 4_000)
    }

    @Test
    fun `a failed send is retried without asking the model again and nothing is lost`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        telegramSends(link, update(5, "question"))
        telegram.failSends = 1
        val r = runner(sb, link, agent("the answer")) // the model has only one reply to give
        assertFailsWith<Exception> { r.pollOnce() }
        assertEquals(1, store.pending(link.credentials.deviceId)) // still on the relay
        assertEquals(1, r.pollOnce())
        assertEquals(listOf("the answer"), telegram.sent)
        assertEquals(0, store.pending(link.credentials.deviceId))
    }

    @Test
    fun `updates that are not text messages are acknowledged and ignored`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        telegramSends(link, """{"update_id":70,"message":{"chat":{"id":55},"photo":[{"file_id":"x"}]}}""")
        telegramSends(link, """{"update_id":71,"edited_message":{"text":"x"}}""")
        assertEquals(2, runner(sb, link, agent()).pollOnce())
        assertTrue(telegram.sent.isEmpty())
        assertEquals(0, store.pending(link.credentials.deviceId))
    }

    @Test
    fun `turning the relay off clears the webhook and removes the route`() {
        val sb = sandbox()
        val setup = RelaySetup(client)
        val link = setup.enable(null, sb)
        setup.disable(link, sb)
        assertEquals(listOf("set", "clear"), telegram.webhookCalls.map { it.first })
        assertEquals(0, store.routeCount(link.credentials.deviceId))
        assertEquals(404, telegramSends(link, update(1, "late")))
    }

    @Test
    fun `if pointing Telegram at the relay fails the route is not left behind`() {
        val broken = object : HostNet {
            override fun fetch(url: String, method: String, headers: Map<String, String>, body: String?) = HostResponse(200, """{"ok":false,"description":"Bad Request: bad webhook"}""")
        }
        val broker = PermissionBroker().also { it.grantDefaults("tg", declared) }
        val sb = PackSandbox("tg", declared, broker, broken, File("../packs/telegram/pack.js").readText(), kv = kv, secrets = { "123:ABC" }).also { sandboxes += it }
        val creds = client.register()
        assertFailsWith<Exception> { RelaySetup(client).enable(creds, sb) }
        assertEquals(0, store.routeCount(creds.deviceId))
    }

    @Test
    fun `a phone the relay has forgotten registers again instead of failing`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(RelayCredentials("gone", "gone"), sb)
        assertTrue(link.credentials.deviceId != "gone")
        assertEquals(1, store.routeCount(link.credentials.deviceId))
    }

    @Test
    fun `a replaced relay that starts counting from one again still delivers`() {
        val sb = sandbox()
        val link = RelaySetup(client).enable(null, sb)
        telegramSends(link, update(1, "a")); telegramSends(link, update(2, "b")); telegramSends(link, update(3, "c"))
        assertEquals(3, runner(sb, link, agent("1", "2", "3")).pollOnce()) // the phone has now read up to number 3

        // The relay is replaced by a fresh one that knows nothing, and the phone registers again.
        val db2 = JdbcSqlDb(":memory:")
        val server2 = RelayServer(RelayStore(db2, Sealer(ByteArray(32) { 9 })), NoPush, RelayConfig("https://relay.test"), bind = "127.0.0.1").also { it.start() }
        try {
            val client2 = RelayClient("http://127.0.0.1:${server2.port}", HttpHostNet())
            val link2 = RelaySetup(client2).enable(link.credentials, sb)
            assertTrue(link2.credentials.deviceId != link.credentials.deviceId)
            assertEquals(200, telegramSends(link2, update(4, "d"), server2.port)) // this is number 1 on the new relay
            val r2 = RelayInboxRunner(client2, { link2.credentials }, "tg", sb, ChannelRunner("telegram", "tg", sb, agent("4"), kv), kv, waitSeconds = 3)
            assertEquals(1, r2.pollOnce())
            assertEquals("4", telegram.sent.last())
        } finally { server2.stop(); db2.close() }
    }

    @Test
    fun `an unreachable relay is reported plainly`() {
        val e = assertFailsWith<RelayException> { RelayClient("http://127.0.0.1:1", HttpHostNet()).register() }
        assertTrue(e.message!!.contains("Could not reach"))
    }
}
