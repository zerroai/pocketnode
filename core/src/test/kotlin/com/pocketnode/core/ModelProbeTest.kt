package com.pocketnode.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelProbeTest {
    private var server: HttpServer? = null
    private var lastKey: String? = null

    @AfterTest fun stop() { server?.stop(0) }

    private fun serve(path: String, status: Int, body: String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(path) { ex ->
            lastKey = ex.requestHeaders.getFirst("x-api-key")
            val b = body.toByteArray(); ex.sendResponseHeaders(status, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @Test
    fun `anthropic accepts a valid key and sends it as a header`() {
        val url = serve("/v1/models", 200, """{"data":[]}""")
        assertIs<ProbeResult.Ok>(ModelProbe.anthropic("sk-good", url))
        assertEquals("sk-good", lastKey)
    }

    @Test
    fun `anthropic rejects a bad key with a plain message and an empty key without a request`() {
        val url = serve("/v1/models", 401, """{"error":{"message":"invalid x-api-key"}}""")
        val r = assertIs<ProbeResult.Failed>(ModelProbe.anthropic("sk-bad", url))
        assertTrue(r.reason.contains("not accepted"))
        assertIs<ProbeResult.Failed>(ModelProbe.anthropic("  ", url))
    }

    @Test
    fun `ollama lists models and accepts a known model by full or short name`() {
        val url = serve("/api/tags", 200, """{"models":[{"name":"llama3.1:8b"},{"name":"nomic-embed-text:latest"}]}""")
        assertEquals(ProbeResult.Ok("2 models available"), ModelProbe.ollama(url, "llama3.1:8b"))
        assertIs<ProbeResult.Ok>(ModelProbe.ollama(url, "llama3.1"))
        assertIs<ProbeResult.Ok>(ModelProbe.ollama(url, ""))
    }

    @Test
    fun `ollama reports a missing model, a non-ollama server and an unreachable server`() {
        val url = serve("/api/tags", 200, """{"models":[{"name":"llama3.1:8b"}]}""")
        assertTrue(assertIs<ProbeResult.Failed>(ModelProbe.ollama(url, "mistral")).reason.contains("mistral"))
        val other = serve("/api/tags", 200, "<html>hello</html>")
        assertTrue(assertIs<ProbeResult.Failed>(ModelProbe.ollama(other, "x")).reason.contains("Ollama"))
        assertIs<ProbeResult.Failed>(ModelProbe.ollama("http://127.0.0.1:1", "x"))
        assertTrue(assertIs<ProbeResult.Failed>(ModelProbe.ollama("example.com", "x")).reason.contains("http"))
    }
}
