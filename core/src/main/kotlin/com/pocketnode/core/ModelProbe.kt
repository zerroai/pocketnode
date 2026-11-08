package com.pocketnode.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ProbeResult {
    /** [detail] is shown to the user, e.g. "3 models available". */
    data class Ok(val detail: String = "") : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

/** Checks a model connection without spending tokens. Blocking; call from a worker thread. */
object ModelProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun anthropic(apiKey: String, baseUrl: String = "https://api.anthropic.com"): ProbeResult {
        if (apiKey.isBlank()) return ProbeResult.Failed("Enter your API key.")
        return when (val r = get("$baseUrl/v1/models", mapOf("x-api-key" to apiKey.trim(), "anthropic-version" to "2023-06-01"))) {
            is Http.Failure -> ProbeResult.Failed(r.reason)
            is Http.Response -> when (r.code) {
                200 -> ProbeResult.Ok()
                401, 403 -> ProbeResult.Failed("This key was not accepted. Check it and try again.")
                else -> ProbeResult.Failed("Anthropic returned an error (${r.code}). Try again in a moment.")
            }
        }
    }

    fun ollama(baseUrl: String, model: String, extraHeaders: Map<String, String> = mapOf("ngrok-skip-browser-warning" to "true")): ProbeResult {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return ProbeResult.Failed("Enter the server address.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ProbeResult.Failed("The address should start with http:// or https://.")
        return when (val r = get("$url/api/tags", extraHeaders)) {
            is Http.Failure -> ProbeResult.Failed(r.reason)
            is Http.Response -> {
                if (r.code != 200) return ProbeResult.Failed("The server answered with an error (${r.code}). Is this an Ollama server?")
                val names = try {
                    json.parseToJsonElement(r.body).jsonObject["models"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                } catch (e: Exception) {
                    return ProbeResult.Failed("This address did not answer like an Ollama server.")
                }
                val wanted = model.trim()
                if (wanted.isNotEmpty() && names.none { it == wanted || it.substringBefore(':') == wanted }) {
                    ProbeResult.Failed("The server does not have a model named $wanted.")
                } else {
                    ProbeResult.Ok("${names.size} ${if (names.size == 1) "model" else "models"} available")
                }
            }
        }
    }

    /** Checks an OpenAI-compatible server by listing its models. A blank key is allowed for servers that need none. */
    fun openAi(baseUrl: String, apiKey: String, model: String): ProbeResult {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return ProbeResult.Failed("Enter the server address.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ProbeResult.Failed("The address should start with http:// or https://.")
        if (model.isBlank()) return ProbeResult.Failed("Enter the model name.")
        val headers = if (apiKey.isBlank()) emptyMap() else mapOf("authorization" to "Bearer ${apiKey.trim()}")
        return when (val r = get("$url/models", headers)) {
            is Http.Failure -> ProbeResult.Failed(r.reason)
            is Http.Response -> when (r.code) {
                200 -> ProbeResult.Ok()
                401, 403 -> ProbeResult.Failed("This key was not accepted. Check it and try again.")
                404 -> ProbeResult.Failed("This address did not answer like an OpenAI-style server. It usually ends in /v1.")
                else -> ProbeResult.Failed("The server answered with an error (${r.code}).")
            }
        }
    }

    private sealed interface Http {
        data class Response(val code: Int, val body: String) : Http
        data class Failure(val reason: String) : Http
    }

    private fun get(url: String, headers: Map<String, String>): Http {
        val conn = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return Http.Failure("That address is not valid.") }
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            Http.Response(code, body)
        } catch (e: java.net.UnknownHostException) {
            Http.Failure("Could not find that address. Check the spelling and your connection.")
        } catch (e: java.net.SocketTimeoutException) {
            Http.Failure("The connection timed out.")
        } catch (e: java.io.IOException) {
            Http.Failure("Could not connect. ${e.message.orEmpty()}".trim())
        } finally {
            conn.disconnect()
        }
    }
}
