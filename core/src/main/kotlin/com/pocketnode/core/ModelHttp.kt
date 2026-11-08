package com.pocketnode.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One POST to a model provider, shared by the three clients. Two things matter on phones:
 *  - A fresh connection every time (`Connection: close`). Android reuses pooled connections, and a provider that closed one
 *    meanwhile makes the next request fail with "unexpected end of stream" or "connection reset". Requests are rare and big,
 *    so there is nothing to gain from reuse.
 *  - The body is buffered so its length is sent up front (not chunked), which providers handle better.
 * A failure to connect or an abruptly closed connection is tried up to three times, waiting a little longer each time (a weak
 * Wi-Fi often drops one request and carries the next); nothing has been done by the provider yet that a
 * second try could repeat. Timeouts are not retried: the provider may still be working on it.
 */
internal object ModelHttp {
    class Reply(val code: Int, val body: String)

    private const val ATTEMPTS = 3

    fun post(url: String, headers: Map<String, String>, payload: String, readTimeoutMs: Int): Reply {
        var failure: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return once(url, headers, payload, readTimeoutMs)
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                if (ModelCancel.cancelled()) throw e
                failure = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(800L * (attempt + 1))
            }
        }
        throw failure ?: IOException("The request failed.")
    }

    /**
     * Like [post], but the reply is read line by line as it arrives (server-sent events and the like) and each line goes to
     * [onLine]. The reading timeout counts silence, not total time. A failed connection is retried like [post] only while no
     * line has been delivered; after that the failure comes out, since the provider may have done work. A reply that is not
     * a success is returned whole, with its body, and [onLine] is not called. [onLine] may throw to stop.
     */
    fun postStream(url: String, headers: Map<String, String>, payload: String, readTimeoutMs: Int, onLine: (String) -> Unit): Reply {
        var begun = false
        var failure: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return streamOnce(url, headers, payload, readTimeoutMs) { begun = true; onLine(it) }
            } catch (e: SocketTimeoutException) {
                throw e
            } catch (e: IOException) {
                if (begun || ModelCancel.cancelled()) throw e
                failure = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(800L * (attempt + 1))
            }
        }
        throw failure ?: IOException("The request failed.")
    }

    private fun streamOnce(url: String, headers: Map<String, String>, payload: String, readTimeoutMs: Int, onLine: (String) -> Unit): Reply {
        val bytes = payload.toByteArray()
        ModelCancel.check()
        val conn = URL(url).openConnection() as HttpURLConnection
        ModelCancel.register(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("accept", "text/event-stream, application/x-ndjson, application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val t0 = System.currentTimeMillis()
            conn.outputStream.use { it.write(bytes) }
            val sent = System.currentTimeMillis() - t0
            val code = conn.responseCode
            val head = System.currentTimeMillis() - t0
            ModelCancel.timing?.invoke("model request: connected and sent in $sent ms, answer began after $head ms")
            if (code !in 200..299) return Reply(code, conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            val usage = ModelUsage.Scanner()
            try { conn.inputStream.bufferedReader().use { r ->
                // Some providers keep a connection alive with comment lines (": PROCESSING") while nothing is being answered. Those must not
                // reset the clock, or a stalled answer would keep the chat spinning for ever.
                var lastData = System.currentTimeMillis()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isNotBlank() && !line.startsWith(":")) lastData = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - lastData > readTimeoutMs) throw SocketTimeoutException("No answer data for ${readTimeoutMs / 1000} seconds.")
                    usage.add(line)
                    onLine(line)
                }
            } } finally { usage.result()?.let { ModelUsage.report(it) } }
            return Reply(code, "")
        } finally {
            ModelCancel.unregister(conn)
            conn.disconnect()
        }
    }

    private fun once(url: String, headers: Map<String, String>, payload: String, readTimeoutMs: Int): Reply {
        val bytes = payload.toByteArray()
        ModelCancel.check()
        val conn = URL(url).openConnection() as HttpURLConnection
        ModelCancel.register(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) ModelUsage.Scanner().also { it.add(text) }.result()?.let { ModelUsage.report(it) }
            return Reply(code, text)
        } finally {
            ModelCancel.unregister(conn)
            conn.disconnect()
        }
    }
}
