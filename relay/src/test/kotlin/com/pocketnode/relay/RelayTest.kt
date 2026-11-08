package com.pocketnode.relay

import com.pocketnode.core.Hex
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class Reply(val code: Int, val body: String) {
    val json get() = Json.parseToJsonElement(body).jsonObject
    fun str(k: String) = json.getValue(k).jsonPrimitive.content
}

class RelayTest {
    private var clock = 1_000_000L
    private val key = ByteArray(32) { it.toByte() }
    private val db = JdbcSqlDb(":memory:")
    private val store = RelayStore(db, Sealer(key), now = { clock })
    private val pushed = java.util.Collections.synchronizedList(mutableListOf<Device>())
    private val pusher = PushSender { d -> pushed += d; PushResult.Sent }
    private val server = RelayServer(store, pusher, RelayConfig("https://relay.test", hookPerMinute = 5, registerPerHour = 3, maxRoutesPerDevice = 2, nodePerMinute = 300), bind = "127.0.0.1", now = { clock }).also { it.start() }
    private val base = "http://127.0.0.1:${server.port}"

    @AfterTest fun stop() { server.stop(); db.close() }

    private fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): Reply {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Reply(code, text)
    }

    private fun register(): Pair<String, Map<String, String>> {
        val r = call("POST", "/v1/devices", "{}")
        assertEquals(201, r.code)
        return r.str("deviceId") to mapOf("Authorization" to "Bearer ${r.str("deviceId")}.${r.str("secret")}")
    }

    private fun route(auth: Map<String, String>): Triple<String, String, String> {
        val r = call("POST", "/v1/routes", """{"channel":"telegram"}""", auth)
        assertEquals(201, r.code)
        return Triple(r.str("routeId"), r.str("hookUrl"), r.str("secret"))
    }

    private fun hook(routeId: String, secret: String, body: String = """{"update_id":1}""") =
        call("POST", "/hook/telegram/$routeId", body, mapOf("X-Telegram-Bot-Api-Secret-Token" to secret))

    @Test
    fun `health check answers`() = assertEquals(200, call("GET", "/healthz").code)

    @Test
    fun `a device registers, and every other call needs its credentials`() {
        val (_, auth) = register()
        assertEquals(401, call("GET", "/v1/inbox").code)
        assertEquals(401, call("GET", "/v1/inbox", headers = mapOf("Authorization" to "Bearer nope.nope")).code)
        assertEquals(401, call("POST", "/v1/routes", "{}", mapOf("Authorization" to "Bearer garbage")).code)
        assertEquals(200, call("GET", "/v1/inbox", headers = auth).code)
        // The secret is not stored in the clear.
        val stored = db.transaction { tx -> tx.query("SELECT secret_hash FROM devices") { it.string(0) } }.single()
        assertEquals(64, stored.length)
    }

    @Test
    fun `a webhook with the right secret reaches the phone and is gone after the acknowledgement`() {
        val (_, auth) = register()
        val (routeId, hookUrl, secret) = route(auth)
        assertEquals("https://relay.test/hook/telegram/$routeId", hookUrl)
        assertEquals(200, hook(routeId, secret, """{"update_id":7,"message":{"text":"hello"}}""").code)

        val inbox = call("GET", "/v1/inbox?after=0", headers = auth).json.getValue("items").jsonArray
        assertEquals(1, inbox.size)
        val item = inbox[0].jsonObject
        assertEquals(routeId, item.getValue("route").jsonPrimitive.content)
        assertTrue(item.getValue("payload").jsonPrimitive.content.contains("hello"))

        val seq = item.getValue("seq").jsonPrimitive.content.toLong()
        assertEquals(1, call("GET", "/v1/inbox?after=0", headers = auth).json.getValue("items").jsonArray.size) // not removed until acknowledged
        assertEquals(204, call("POST", "/v1/inbox/ack", """{"upTo":$seq}""", auth).code)
        assertEquals(0, call("GET", "/v1/inbox?after=0", headers = auth).json.getValue("items").jsonArray.size)
        assertEquals(0, store.pending(call("POST", "/v1/devices", "{}").str("deviceId")))
    }

    @Test
    fun `a wrong secret or a wrong address is refused the same way and stores nothing`() {
        val (deviceId, auth) = register()
        val (routeId, _, secret) = route(auth)
        assertEquals(404, hook(routeId, "wrong-secret").code)
        assertEquals(404, hook("no-such-route", secret).code)
        assertEquals(404, call("POST", "/hook/telegram/$routeId", "{}").code) // no header
        assertEquals(0, store.pending(deviceId))
    }

    @Test
    fun `stored messages are encrypted on disk and tampering is detected`() {
        val (deviceId, auth) = register()
        val (routeId, _, secret) = route(auth)
        hook(routeId, secret, """{"message":{"text":"my secret words"}}""")
        val raw = db.transaction { tx -> tx.query("SELECT payload FROM inbox") { it.string(0) } }.single()
        assertTrue(!raw.contains("secret words"))
        // Moving the stored value to another device or route must not decrypt.
        assertNull(Sealer(key).open(raw, "someone-else|$routeId"))
        db.transaction { it.exec("UPDATE inbox SET payload = ?", raw.dropLast(4) + "AAAA") }
        assertEquals(0, store.fetch(deviceId, 0).size)
    }

    @Test
    fun `a waiting phone is answered the moment a message arrives`() {
        val (_, auth) = register()
        val (routeId, _, secret) = route(auth)
        var reply: Reply? = null
        val t = Thread { reply = call("GET", "/v1/inbox?after=0&wait=20", headers = auth, timeoutMs = 25_000) }
        t.start()
        Thread.sleep(300)
        val started = System.nanoTime()
        hook(routeId, secret)
        t.join(5_000)
        assertEquals(200, reply?.code)
        assertEquals(1, reply!!.json.getValue("items").jsonArray.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000, "should not wait for the timeout")
    }

    @Test
    fun `a long poll with nothing to deliver returns an empty list when the wait ends`() {
        val (_, auth) = register()
        val started = System.nanoTime()
        val r = call("GET", "/v1/inbox?after=0&wait=1", headers = auth)
        assertEquals(0, r.json.getValue("items").jsonArray.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 900)
    }

    @Test
    fun `one phone cannot read or remove another phone's messages or routes`() {
        val (_, authA) = register()
        val (_, authB) = register()
        val (routeA, _, secretA) = route(authA)
        hook(routeA, secretA)
        assertEquals(0, call("GET", "/v1/inbox?after=0", headers = authB).json.getValue("items").jsonArray.size)
        assertEquals(404, call("DELETE", "/v1/routes/$routeA", headers = authB).code)
        assertEquals(1, call("GET", "/v1/inbox?after=0", headers = authA).json.getValue("items").jsonArray.size)
        assertEquals(204, call("DELETE", "/v1/routes/$routeA", headers = authA).code)
        assertEquals(404, hook(routeA, secretA).code) // gone, and its waiting messages with it
        assertEquals(0, call("GET", "/v1/inbox?after=0", headers = authA).json.getValue("items").jsonArray.size)
    }

    @Test
    fun `limits stop oversized bodies, floods, mass registration and too many routes`() {
        val (_, auth) = register()
        val (routeId, _, secret) = route(auth)
        assertEquals(413, hook(routeId, secret, "x".repeat(70 * 1024)).code)
        repeat(4) { assertEquals(200, hook(routeId, secret).code) } // the oversized attempt above used one of five
        assertEquals(429, hook(routeId, secret).code)
        clock += 61_000
        assertEquals(200, hook(routeId, secret).code) // the window moved on
        route(auth) // second of two allowed
        assertEquals(409, call("POST", "/v1/routes", """{"channel":"telegram"}""", auth).code)
        register(); register() // three in the hour counting the first
        assertEquals(429, call("POST", "/v1/devices", "{}").code)
    }

    @Test
    fun `old messages expire and the oldest are dropped past the per-device cap`() {
        val capped = RelayStore(JdbcSqlDb(":memory:"), Sealer(key), now = { clock }, maxPerDevice = 3, ttlMs = 10_000)
        val (id, _) = capped.createDevice()
        val (route, _) = capped.createRoute(id, "telegram")
        repeat(5) { capped.ingest(route, "m$it".toByteArray()) }
        assertEquals(listOf("m2", "m3", "m4"), capped.fetch(id, 0).map { it.payload })
        clock += 20_000
        assertEquals(0, capped.fetch(id, 0).size)
        assertEquals(3, capped.purgeExpired())
        assertEquals(0, capped.pending(id))
    }

    @Test
    fun `a message wakes the phone through push, but not more often than the minimum gap`() {
        val (deviceId, auth) = register()
        call("PUT", "/v1/devices/me/push", """{"kind":"fcm","token":"tok-123"}""", auth)
        val (routeId, _, secret) = route(auth)
        hook(routeId, secret); hook(routeId, secret); hook(routeId, secret)
        Thread.sleep(300)
        assertEquals(1, pushed.size)
        assertEquals("tok-123", pushed[0].pushToken)
        assertEquals(deviceId, pushed[0].id)
        clock += 11_000
        hook(routeId, secret)
        Thread.sleep(300)
        assertEquals(2, pushed.size)
    }

    @Test
    fun `a push token that the push service rejects is cleared`() {
        val rejecting = PushSender { PushResult.TokenInvalid }
        val s2 = RelayServer(store, rejecting, RelayConfig("https://relay.test"), bind = "127.0.0.1", now = { clock }).also { it.start() }
        try {
            val (id, secret) = store.createDevice()
            store.setPush(id, "fcm", "stale")
            val (route, hookSecret) = store.createRoute(id, "telegram")
            val c = URL("http://127.0.0.1:${s2.port}/hook/telegram/${route.id}").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("X-Telegram-Bot-Api-Secret-Token", hookSecret); c.outputStream.use { it.write("{}".toByteArray()) }
            assertEquals(200, c.responseCode)
            Thread.sleep(300)
            assertEquals("", store.device(id)?.pushToken)
        } finally { s2.stop() }
    }

    @Test
    fun `the storage key must be 32 bytes and hex parses`() {
        assertEquals(32, Hex.decode("00".repeat(32))!!.size)
        kotlin.runCatching { Sealer(ByteArray(5)) }.let { assertTrue(it.isFailure) }
        assertNotEquals(Crypto.token(20), Crypto.token(20))
    }

    private fun nodeRoute(auth: Map<String, String>): Triple<String, String, String> {
        val r = call("POST", "/v1/routes", """{"channel":"node"}""", auth)
        assertEquals(201, r.code)
        return Triple(r.str("routeId"), r.str("hookUrl"), r.str("secret"))
    }

    @Test
    fun `another node can post ciphertext to a node route and only the owner can read it`() {
        val (_, owner) = register()
        val (_, other) = register()
        val (routeId, hookUrl, secret) = nodeRoute(owner)
        assertEquals("https://relay.test/hook/node/$routeId", hookUrl)
        val post = { sec: String, body: String -> call("POST", "/hook/node/$routeId", body, mapOf("X-Route-Secret" to sec)) }
        assertEquals(200, post(secret, "opaque-ciphertext").code)
        assertEquals(404, post("wrong", "x").code)
        assertEquals(404, call("POST", "/hook/telegram/$routeId", "x", mapOf("X-Telegram-Bot-Api-Secret-Token" to secret)).code) // a node route is not a Telegram route
        val items = call("GET", "/v1/inbox?after=0", headers = owner).json.getValue("items").jsonArray
        assertEquals(1, items.size)
        assertEquals("opaque-ciphertext", items[0].jsonObject.getValue("payload").jsonPrimitive.content)
        assertEquals(0, call("GET", "/v1/inbox?after=0", headers = other).json.getValue("items").jsonArray.size)
    }

    @Test
    fun `a device can hold sixteen routes and no more`() {
        val s2 = RelayServer(store, pusher, RelayConfig("https://relay.test"), bind = "127.0.0.1", now = { clock }).also { it.start() }
        try {
            fun post(path: String, body: String, auth: Map<String, String>): Int {
                val c = URL("http://127.0.0.1:${s2.port}$path").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                auth.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body.toByteArray()) }
                return c.responseCode
            }
            val (id, secret) = store.createDevice()
            val auth = mapOf("Authorization" to "Bearer $id.$secret")
            repeat(16) { assertEquals(201, post("/v1/routes", """{"channel":"node"}""", auth)) }
            assertEquals(409, post("/v1/routes", """{"channel":"node"}""", auth))
        } finally { s2.stop() }
    }
}
