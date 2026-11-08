package com.pocketnode.relay

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class RelayConfig(
    /** The public https address Telegram will call, with no trailing slash. */
    val publicUrl: String,
    val maxBodyBytes: Int = 64 * 1024,
    val trustProxy: Boolean = false,
    val registerPerHour: Int = 20,
    val hookPerMinute: Int = 120,
    val maxRoutesPerDevice: Int = 16,
    val nodePerMinute: Int = 300,
    val maxWaitSeconds: Int = 30,
    val pushMinGapMs: Long = 10_000,
)

/** Counts events per key in a fixed window. */
class Limiter(private val limit: Int, private val windowMs: Long, private val now: () -> Long) {
    private class Bucket(var start: Long, var count: Int)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun allow(key: String): Boolean {
        val t = now()
        if (buckets.size > 10_000) buckets.entries.removeIf { t - it.value.start > windowMs }
        val b = buckets.computeIfAbsent(key) { Bucket(t, 0) }
        synchronized(b) {
            if (t - b.start >= windowMs) { b.start = t; b.count = 0 }
            return if (b.count >= limit) false else { b.count++; true }
        }
    }
}

/** Lets a waiting request know that something arrived for its device without losing a wake-up that came first. */
private class Signal {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var version = 0L

    fun current(): Long = lock.withLock { version }

    fun bump() { lock.withLock { version++; changed.signalAll() } }

    fun await(since: Long, ms: Long) {
        lock.withLock {
            var left = TimeUnit.MILLISECONDS.toNanos(ms)
            while (version == since && left > 0) left = changed.awaitNanos(left)
        }
    }
}

class RelayServer(
    private val store: RelayStore,
    private val pusher: PushSender,
    private val config: RelayConfig,
    port: Int = 0,
    bind: String = "0.0.0.0",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val registerLimit = Limiter(config.registerPerHour, 3_600_000, now)
    private val hookLimit = Limiter(config.hookPerMinute, 60_000, now)
    private val nodeLimit = Limiter(config.nodePerMinute, 60_000, now)
    private val signals = ConcurrentHashMap<String, Signal>()
    private val lastPush = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(256) { Thread(it, "relay-http").apply { isDaemon = true } }
    private val pushPool = Executors.newFixedThreadPool(4) { Thread(it, "relay-push").apply { isDaemon = true } }
    private val http: HttpServer = HttpServer.create(InetSocketAddress(bind, port), 256)

    val port: Int get() = http.address.port

    init {
        http.executor = pool
        http.createContext("/healthz") { ex -> guard(ex) { text(ex, 200, "ok") } }
        http.createContext("/v1/devices") { ex -> guard(ex) { devices(ex) } }
        http.createContext("/v1/routes") { ex -> guard(ex) { routes(ex) } }
        http.createContext("/v1/inbox") { ex -> guard(ex) { inbox(ex) } }
        http.createContext("/hook/telegram/") { ex -> guard(ex) { telegramHook(ex) } }
        http.createContext("/hook/whatsapp/") { ex -> guard(ex) { whatsappHook(ex) } }
        http.createContext("/hook/node/") { ex -> guard(ex) { nodeHook(ex) } }
    }

    fun start() = http.start()
    fun stop() { http.stop(0); pool.shutdownNow(); pushPool.shutdownNow() }

    // ---- Handlers ------------------------------------------------------------------------

    private fun devices(ex: HttpExchange) {
        val path = ex.requestURI.path
        if (path == "/v1/devices" && ex.requestMethod == "POST") {
            if (!registerLimit.allow(clientIp(ex))) return error(ex, 429, "Too many registrations. Try again later.")
            val (id, secret) = store.createDevice()
            val body = readBody(ex)?.let { parseObject(it) }
            val kind = body?.get("pushKind")?.jsonPrimitive?.contentOrNull.orEmpty()
            val token = body?.get("pushToken")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (kind == "fcm" && token.isNotBlank() && token.length <= 4096) store.setPush(id, kind, token)
            return respond(ex, 201, buildJsonObject { put("deviceId", id); put("secret", secret) }.toString())
        }
        if (path == "/v1/devices/me/push" && ex.requestMethod == "PUT") {
            val d = authenticate(ex) ?: return unauthorized(ex)
            val body = parseObject(readBody(ex) ?: return error(ex, 413, "The request is too large.")) ?: return error(ex, 400, "The request is not valid.")
            val kind = body["kind"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val token = body["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (kind != "fcm" && kind.isNotEmpty() || token.length > 4096) return error(ex, 400, "The push settings are not valid.")
            store.setPush(d.id, kind, token)
            return respond(ex, 204, null)
        }
        error(ex, 404, "Not found.")
    }

    private fun routes(ex: HttpExchange) {
        val d = authenticate(ex) ?: return unauthorized(ex)
        val path = ex.requestURI.path
        if (path == "/v1/routes" && ex.requestMethod == "POST") {
            val body = parseObject(readBody(ex) ?: return error(ex, 413, "The request is too large.")) ?: return error(ex, 400, "The request is not valid.")
            val channel = body["channel"]?.jsonPrimitive?.contentOrNull
            if (channel != "telegram" && channel != "node" && channel != "whatsapp") return error(ex, 400, "That channel is not supported.")
            if (store.routeCount(d.id) >= config.maxRoutesPerDevice) return error(ex, 409, "This device has reached its limit of routes.")
            // WhatsApp signs its webhooks with the Meta app secret, so the relay needs it to check them.
            val appSecret = body["appSecret"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (channel == "whatsapp" && appSecret.length !in 8..128) return error(ex, 400, "The WhatsApp app secret is required.")
            val (route, secret) = store.createRoute(d.id, channel, if (channel == "whatsapp") appSecret else "")
            return respond(ex, 201, buildJsonObject { put("routeId", route.id); put("hookUrl", "${config.publicUrl}/hook/$channel/${route.id}"); put("secret", secret) }.toString())
        }
        if (path.startsWith("/v1/routes/") && ex.requestMethod == "DELETE") {
            return if (store.deleteRoute(d.id, path.removePrefix("/v1/routes/"))) respond(ex, 204, null) else error(ex, 404, "No such route.")
        }
        error(ex, 404, "Not found.")
    }

    private fun inbox(ex: HttpExchange) {
        val d = authenticate(ex) ?: return unauthorized(ex)
        val path = ex.requestURI.path
        if (path == "/v1/inbox" && ex.requestMethod == "GET") {
            val q = query(ex)
            val after = q["after"]?.toLongOrNull() ?: 0L
            val wait = (q["wait"]?.toIntOrNull() ?: 0).coerceIn(0, config.maxWaitSeconds)
            val signal = signals.computeIfAbsent(d.id) { Signal() }
            val deadline = System.nanoTime() + wait * 1_000_000_000L // real time, whatever the injected clock says
            while (true) {
                val seen = signal.current()
                val items = store.fetch(d.id, after)
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (items.isNotEmpty() || left <= 0) {
                    return respond(ex, 200, buildJsonObject {
                        put("items", buildJsonArray {
                            items.forEach { add(buildJsonObject { put("seq", it.seq); put("route", it.routeId); put("receivedAt", it.receivedAt); put("payload", it.payload) }) }
                        })
                    }.toString())
                }
                signal.await(seen, left)
            }
        }
        if (path == "/v1/inbox/ack" && ex.requestMethod == "POST") {
            val body = parseObject(readBody(ex) ?: return error(ex, 413, "The request is too large.")) ?: return error(ex, 400, "The request is not valid.")
            val upTo = body["upTo"]?.jsonPrimitive?.longOrNull ?: return error(ex, 400, "upTo is required.")
            store.ack(d.id, upTo)
            return respond(ex, 204, null)
        }
        error(ex, 404, "Not found.")
    }

    private fun telegramHook(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return error(ex, 405, "Method not allowed.")
        val routeId = ex.requestURI.path.removePrefix("/hook/telegram/")
        if (routeId.isEmpty() || routeId.contains('/')) return error(ex, 404, "Not found.")
        if (!hookLimit.allow(routeId)) return error(ex, 429, "Slow down.")
        val secret = ex.requestHeaders.getFirst("X-Telegram-Bot-Api-Secret-Token").orEmpty()
        val body = readBodyBytes(ex) ?: return error(ex, 413, "The request is too large.")
        // A wrong address and a wrong secret look the same from outside.
        val route = store.findRoute(routeId, "telegram", secret) ?: return error(ex, 404, "Not found.")
        store.ingest(route, body)
        signals.computeIfAbsent(route.deviceId) { Signal() }.bump()
        wake(route.deviceId)
        text(ex, 200, "ok")
    }

    /**
     * WhatsApp Cloud API. Meta first calls with GET to check the address (it must echo the challenge when the
     * verify token matches the route secret), then POSTs events signed with HMAC-SHA256 of the body under the app secret.
     */
    private fun whatsappHook(ex: HttpExchange) {
        val routeId = ex.requestURI.path.removePrefix("/hook/whatsapp/")
        if (routeId.isEmpty() || routeId.contains('/')) return error(ex, 404, "Not found.")
        if (!hookLimit.allow(routeId)) return error(ex, 429, "Slow down.")
        if (ex.requestMethod == "GET") {
            val q = query(ex)
            val ok = q["hub.mode"] == "subscribe" && store.routeSecretMatches(routeId, "whatsapp", q["hub.verify_token"].orEmpty())
            val challenge = q["hub.challenge"].orEmpty()
            if (!ok || challenge.isEmpty() || challenge.length > 200 || !challenge.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return error(ex, 404, "Not found.")
            return text(ex, 200, challenge)
        }
        if (ex.requestMethod != "POST") return error(ex, 405, "Method not allowed.")
        val body = readBodyBytes(ex) ?: return error(ex, 413, "The request is too large.")
        val route = store.routeIfExists(routeId, "whatsapp")
        val appSecret = store.routeExtra(routeId, "whatsapp")
        val given = ex.requestHeaders.getFirst("X-Hub-Signature-256").orEmpty().removePrefix("sha256=")
        // A wrong address and a bad signature look the same from outside.
        if (route == null || appSecret == null || !Crypto.same(Crypto.hmacSha256Hex(appSecret, body), given.lowercase())) return error(ex, 404, "Not found.")
        store.ingest(route, body)
        signals.computeIfAbsent(route.deviceId) { Signal() }.bump()
        wake(route.deviceId)
        text(ex, 200, "ok")
    }

    /** Another node posts an encrypted message to this phone's address. The relay sees only ciphertext. */
    private fun nodeHook(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return error(ex, 405, "Method not allowed.")
        val routeId = ex.requestURI.path.removePrefix("/hook/node/")
        if (routeId.isEmpty() || routeId.contains('/')) return error(ex, 404, "Not found.")
        if (!nodeLimit.allow(routeId)) return error(ex, 429, "Slow down.")
        val secret = ex.requestHeaders.getFirst("X-Route-Secret").orEmpty()
        val body = readBodyBytes(ex) ?: return error(ex, 413, "The request is too large.")
        val route = store.findRoute(routeId, "node", secret) ?: return error(ex, 404, "Not found.")
        store.ingest(route, body)
        signals.computeIfAbsent(route.deviceId) { Signal() }.bump()
        wake(route.deviceId)
        text(ex, 200, "ok")
    }

    private fun wake(deviceId: String) {
        val t = now()
        val last = lastPush[deviceId]
        if (last != null && t - last < config.pushMinGapMs) return
        lastPush[deviceId] = t
        pushPool.execute {
            val device = store.device(deviceId) ?: return@execute
            val r = try { pusher.wake(device) } catch (e: Exception) { PushResult.Failed }
            if (r == PushResult.TokenInvalid) store.setPush(deviceId, "", "")
        }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun authenticate(ex: HttpExchange): Device? {
        val h = ex.requestHeaders.getFirst("Authorization") ?: return null
        if (!h.startsWith("Bearer ")) return null
        val token = h.removePrefix("Bearer ").trim()
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.length - 1) return null
        return store.authenticate(token.substring(0, dot), token.substring(dot + 1))
    }

    private fun clientIp(ex: HttpExchange): String =
        (if (config.trustProxy) ex.requestHeaders.getFirst("X-Forwarded-For")?.substringBefore(',')?.trim() else null) ?: ex.remoteAddress.address.hostAddress

    private fun query(ex: HttpExchange): Map<String, String> =
        ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun readBodyBytes(ex: HttpExchange): ByteArray? {
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && declared > config.maxBodyBytes) return null
        val bytes = ex.requestBody.readNBytes(config.maxBodyBytes + 1)
        return if (bytes.size > config.maxBodyBytes) null else bytes
    }

    private fun readBody(ex: HttpExchange): String? = readBodyBytes(ex)?.decodeToString()

    private fun parseObject(s: String): JsonObject? = if (s.isBlank()) JsonObject(emptyMap()) else try { json.parseToJsonElement(s).jsonObject } catch (e: Exception) { null }

    private fun unauthorized(ex: HttpExchange) = error(ex, 401, "Unauthorized.")

    private fun error(ex: HttpExchange, code: Int, message: String) = respond(ex, code, buildJsonObject { put("error", message) }.toString())

    private fun text(ex: HttpExchange, code: Int, body: String) = respond(ex, code, body, "text/plain")

    private fun respond(ex: HttpExchange, code: Int, body: String?, type: String = "application/json") {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, if (body == null) -1 else bytes.size.toLong())
        if (body != null) ex.responseBody.use { it.write(bytes) }
        ex.close()
    }

    private fun guard(ex: HttpExchange, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            try { respond(ex, 500, buildJsonObject { put("error", "Something went wrong.") }.toString()) } catch (_: Exception) { ex.close() }
        }
    }
}
