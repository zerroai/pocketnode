package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelHttpTest {
    private val servers = mutableListOf<AutoCloseable>()
    @AfterTest fun stop() = servers.forEach { it.close() }

    @Test
    fun `a request closes its connection and tells the server how long the body is`() {
        val seen = mutableMapOf<String, String>()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestHeaders.forEach { (k, v) -> seen[k.lowercase()] = v.first() }
            val body = ex.requestBody.readBytes().decodeToString()
            val out = "echo:$body".toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        s.start(); servers += AutoCloseable { s.stop(0) }
        val r = ModelHttp.post("http://127.0.0.1:${s.address.port}/x", mapOf("x-test" to "1"), """{"a":1}""", 5_000)
        assertEquals(200, r.code); assertEquals("""echo:{"a":1}""", r.body)
        assertEquals("close", seen["connection"]); assertEquals("7", seen["content-length"]); assertEquals("1", seen["x-test"]); assertEquals("application/json", seen["content-type"])
    }

    @Test
    fun `a connection that is cut off is tried once more and then succeeds`() {
        val attempts = AtomicInteger()
        val server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        servers += server
        Thread {
            while (!server.isClosed) {
                val c = try { server.accept() } catch (e: Exception) { return@Thread }
                if (attempts.incrementAndGet() == 1) { c.close(); continue } // the first connection dies without an answer
                c.getInputStream().let { input -> val buf = ByteArray(8192); input.read(buf) }
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                c.close()
            }
        }.also { it.isDaemon = true }.start()
        val r = ModelHttp.post("http://127.0.0.1:${server.localPort}/x", emptyMap(), "{}", 5_000)
        assertEquals("ok", r.body)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `a server that never answers is not retried`() {
        val attempts = AtomicInteger()
        val server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        servers += server
        Thread { while (!server.isClosed) { val c = try { server.accept() } catch (e: Exception) { return@Thread }; attempts.incrementAndGet(); servers += c } }.also { it.isDaemon = true }.start()
        assertFailsWith<java.net.SocketTimeoutException> { ModelHttp.post("http://127.0.0.1:${server.localPort}/x", emptyMap(), "{}", 700) }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `an error status is returned, not retried`() {
        val hits = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> hits.incrementAndGet(); val b = """{"error":"no"}""".toByteArray(); ex.sendResponseHeaders(401, b.size.toLong()); ex.responseBody.use { it.write(b) } }
        s.start(); servers += AutoCloseable { s.stop(0) }
        val r = ModelHttp.post("http://127.0.0.1:${s.address.port}/x", emptyMap(), "{}", 5_000)
        assertEquals(401, r.code); assertTrue(r.body.contains("no")); assertEquals(1, hits.get())
    }

    @Test
    fun `keep-alive comment lines do not keep a stalled stream alive`() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestBody.readBytes()
            ex.responseHeaders.add("content-type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            try { repeat(40) { out.write(": PROCESSING\n\n".toByteArray()); out.flush(); Thread.sleep(100) } } catch (e: Exception) { }
            out.close()
        }
        s.start(); servers += AutoCloseable { s.stop(0) }
        val started = System.currentTimeMillis()
        assertFailsWith<java.net.SocketTimeoutException> { ModelHttp.postStream("http://127.0.0.1:${s.address.port}/x", emptyMap(), "{}", 1_000) { } }
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }
}
