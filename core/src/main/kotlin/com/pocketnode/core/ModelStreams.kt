package com.pocketnode.core

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns a model server's streamed reply, one line at a time, into a [ModelReply]. One reader per wire format; each is plain
 * code with no network, so it can be tested with canned lines. A reader may throw [IllegalStateException] with the server's
 * own words when the stream carries an error.
 */
internal sealed class StreamReader(private val onText: (String) -> Unit) {
    protected val json = Json { ignoreUnknownKeys = true }
    protected val text = StringBuilder()

    /** True once at least one line of the expected kind arrived. When it stays false the server did not stream. */
    var sawData = false
        protected set

    /** Lines that were not part of the stream, such as a whole JSON answer from a server that ignored the request to stream. */
    val other = StringBuilder()

    abstract fun line(raw: String)
    abstract fun reply(): ModelReply

    protected fun add(piece: String) {
        if (piece.isEmpty()) return
        text.append(piece)
        onText(text.toString())
    }

    protected fun parse(data: String): JsonObject? = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }

    protected fun failIfError(o: JsonObject) {
        val e = o["error"] ?: return
        if (e is JsonNull) return
        val message = (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: (e as? JsonPrimitive)?.contentOrNull ?: e.toString()
        throw IllegalStateException(message.take(300))
    }

    protected class PartialCall { var id = ""; var name = ""; val args = StringBuilder() }

    protected fun finish(calls: Collection<PartialCall>): List<ToolCall> = calls.filter { it.name.isNotBlank() }.map {
        ToolCall(it.id.ifBlank { "call_${UUID.randomUUID().toString().take(8)}" }, ToolNames.core(it.name), it.args.toString().ifBlank { "{}" })
    }

    /** OpenAI chat completions with `stream: true`: lines starting `data:`, ending with `data: [DONE]`. */
    class OpenAi(onText: (String) -> Unit) : StreamReader(onText) {
        private val calls = sortedMapOf<Int, PartialCall>()

        override fun line(raw: String) {
            if (raw.isBlank() || raw.startsWith(":")) return
            if (!raw.startsWith("data:")) { other.append(raw).append('\n'); return }
            sawData = true
            val data = raw.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return
            val o = parse(data) ?: return
            failIfError(o)
            val delta = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return
            (delta["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.let { add(it) }
            for (tc in delta["tool_calls"]?.jsonArray.orEmpty()) {
                val t = tc.jsonObject
                val p = calls.getOrPut(t["index"]?.jsonPrimitive?.intOrNull ?: 0) { PartialCall() }
                t["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotEmpty()) p.id = it }
                val f = t["function"]?.jsonObject ?: continue
                f["name"]?.jsonPrimitive?.contentOrNull?.let { p.name += it }
                (f["arguments"] as? JsonPrimitive)?.contentOrNull?.let { p.args.append(it) }
            }
        }

        override fun reply() = ModelReply(text.toString(), finish(calls.values))
    }

    /** The Anthropic Messages API with `stream: true`: `data:` lines carrying typed events. */
    class Anthropic(onText: (String) -> Unit) : StreamReader(onText) {
        private val calls = sortedMapOf<Int, PartialCall>()

        override fun line(raw: String) {
            if (raw.isBlank() || raw.startsWith(":") || raw.startsWith("event:")) return
            if (!raw.startsWith("data:")) { other.append(raw).append('\n'); return }
            sawData = true
            val o = parse(raw.removePrefix("data:").trim()) ?: return
            when (o["type"]?.jsonPrimitive?.contentOrNull) {
                "error" -> failIfError(o)
                "content_block_start" -> {
                    val block = o["content_block"]?.jsonObject ?: return
                    if (block["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                        val p = calls.getOrPut(o["index"]?.jsonPrimitive?.intOrNull ?: 0) { PartialCall() }
                        p.id = block["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        p.name = block["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                }
                "content_block_delta" -> {
                    val d = o["delta"]?.jsonObject ?: return
                    when (d["type"]?.jsonPrimitive?.contentOrNull) {
                        "text_delta" -> add(d["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        "input_json_delta" -> calls.getOrPut(o["index"]?.jsonPrimitive?.intOrNull ?: 0) { PartialCall() }.args.append(d["partial_json"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    }
                }
            }
        }

        override fun reply() = ModelReply(text.toString(), finish(calls.values))
    }

    /** Ollama `/api/chat` with `stream: true`: one JSON object per line. */
    class Ollama(onText: (String) -> Unit) : StreamReader(onText) {
        private val calls = mutableListOf<ToolCall>()

        override fun line(raw: String) {
            if (raw.isBlank()) return
            val o = parse(raw.trim()) ?: run { other.append(raw).append('\n'); return }
            sawData = true
            failIfError(o)
            val message = o["message"]?.jsonObject ?: return
            (message["content"] as? JsonPrimitive)?.contentOrNull?.let { add(it) }
            for (c in message["tool_calls"]?.jsonArray.orEmpty()) {
                val f = c.jsonObject.getValue("function").jsonObject
                calls += ToolCall(
                    c.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "call_${UUID.randomUUID().toString().take(8)}",
                    ToolNames.core(f.getValue("name").jsonPrimitive.content),
                    (f["arguments"] ?: JsonObject(emptyMap())).toString(),
                )
            }
        }

        override fun reply() = ModelReply(text.toString(), calls.toList())
    }
}

/** Server replies that mean "I do not do that", where trying the same request without streaming is worth one more go. */
internal fun streamingNotSupported(e: Exception): Boolean = e is ModelHttpException && e.status in setOf(400, 404, 405, 415, 422, 501)
