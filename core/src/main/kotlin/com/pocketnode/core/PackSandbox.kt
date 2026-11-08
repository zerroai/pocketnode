package com.pocketnode.core

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class HostResponse(val status: Int, val body: String)

/** The network call the phone makes on a pack's behalf. Allowlist checks happen before this is reached. */
fun interface HostNet {
    fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse
}

/** Resource caps for one pack's JavaScript context. */
data class PackLimits(
    val memoryBytes: Long = 24L * 1024 * 1024,
    val stackBytes: Long = 512L * 1024,
    val callTimeoutMs: Long = 10_000,
)

/**
 * Runs one pack's script in its own QuickJS context. The script sees only standard JavaScript
 * plus `fetch`, which is checked against the pack's declared and user-granted `net` permissions
 * (https only). It exposes tools by assigning `pack = { tools: { "name": async (args) => ... } }`.
 */
class PackSandbox(
    val packId: String,
    private val declared: Set<Permission>,
    private val broker: PermissionBroker,
    private val net: HostNet,
    script: String,
    private val limits: PackLimits = PackLimits(),
    private val kv: PackKv = InMemoryPackKv(),
    private val secrets: (String) -> String? = { null },
    private val device: DeviceHost = DeviceHost.None,
    /** Told about every request a pack makes to a website, for the owner's activity record: pack, address, method, and the status or the failure. */
    private val onFetch: ((packId: String, host: String, method: String, status: Int?, error: String?) -> Unit)? = null,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val js: QuickJs = QuickJs.create(jobDispatcher = Dispatchers.IO)

    init {
        js.memoryLimit = limits.memoryBytes
        js.maxStackSize = limits.stackBytes
        js.evaluationTimeoutMillis = limits.callTimeoutMs
        js.asyncFunction("__hostFetch") { args -> hostFetch(args[0] as String, args[1] as String) }
        js.function("__hostStoreGet") { args -> kv.get(packId, args[0] as String) ?: "" }
        js.function("__hostStoreHas") { args -> kv.get(packId, args[0] as String) != null }
        js.function("__hostStoreSet") { args -> kv.set(packId, args[0] as String, args[1] as String); true }
        js.function("__hostSecret") { args -> secretFor(args[0] as String) }
        js.asyncFunction("__hostDevice") { args -> hostDevice(args[0] as String, args[1] as String) }
        runBlocking {
            js.evaluate<Any?>(PRELUDE, "prelude.js")
            js.evaluate<Any?>(script, "pack.js")
        }
    }

    /** Calls a tool the pack registered. Blocking; run on a worker thread. */
    fun callTool(name: String, argsJson: String): String = runBlocking {
        val n = JsonPrimitive(name).toString()
        val a = JsonPrimitive(argsJson.ifBlank { "{}" }).toString()
        js.evaluate<String>(
            "await (async () => { const t = pack.tools[$n]; if (typeof t !== 'function') throw new Error('unknown tool ' + $n); " +
                "const r = await t(JSON.parse($a)); return typeof r === 'string' ? r : JSON.stringify(r); })()",
            "call.js",
        )
    }

    override fun close() {
        js.close()
    }

    private fun secretFor(name: String): String {
        val decision = broker.authorize(packId, declared, setOf(Permission("secret", name)))
        if (decision is Decision.Denied) throw SecurityException(decision.reason)
        return secrets(name) ?: ""
    }

    private suspend fun hostDevice(op: String, argsJson: String): String {
        val decision = broker.authorize(packId, declared, setOf(Permission("device", op)))
        if (decision is Decision.Denied) {
            val spec = DeviceOps.get(op)
            throw SecurityException(
                if (spec == null) "$packId asked for an unknown phone feature $op"
                else if (spec.sensitive && decision.reason.contains("has not granted")) "The owner has not granted \"${spec.label}\" to this pack yet. Tell them they can switch it on in Packs, under this pack's permissions."
                else decision.reason,
            )
        }
        return withContext(Dispatchers.IO) { device.call(op, argsJson.ifBlank { "{}" }) }
    }

    private suspend fun hostFetch(url: String, optionsJson: String): String {
        val uri = try { URI(url) } catch (e: Exception) { throw IllegalArgumentException("bad url") }
        if (uri.scheme != "https") throw SecurityException("only https is allowed")
        val host = uri.host ?: throw IllegalArgumentException("bad url")
        val decision = broker.authorize(packId, declared, setOf(Permission("net", host)))
        if (decision is Decision.Denied) throw SecurityException(decision.reason)
        val o = json.parseToJsonElement(optionsJson.ifBlank { "{}" }).jsonObject
        val headers = (o["headers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val resp = try {
            withContext(Dispatchers.IO) { net.fetch(url, method, headers, o["body"]?.jsonPrimitive?.contentOrNull) }
        } catch (e: Exception) {
            onFetch?.invoke(packId, host, method, null, e.message ?: e::class.java.simpleName)
            throw e
        }
        onFetch?.invoke(packId, host, method, resp.status, null)
        return buildJsonObject { put("status", resp.status); put("body", resp.body) }.toString()
    }

    private companion object {
        // Captures the raw host function in a closure and removes it from the global scope.
        const val PRELUDE = """
            (() => {
              const raw = globalThis.__hostFetch;
              const dev = globalThis.__hostDevice;
              const sGet = globalThis.__hostStoreGet, sHas = globalThis.__hostStoreHas, sSet = globalThis.__hostStoreSet, sec = globalThis.__hostSecret;
              for (const n of ['__hostFetch', '__hostStoreGet', '__hostStoreHas', '__hostStoreSet', '__hostSecret', '__hostDevice']) delete globalThis[n];
              globalThis.store = {
                get: async (k) => (sHas(String(k)) ? sGet(String(k)) : null),
                set: async (k, v) => { sSet(String(k), String(v)); },
              };
              globalThis.device = { call: async (op, args) => JSON.parse((await dev(String(op), JSON.stringify(args || {}))) || 'null') };
              globalThis.secret = { get: async (name) => sec(String(name)) || null };
              globalThis.fetch = async (url, opts) => {
                const r = JSON.parse(await raw(String(url), JSON.stringify(opts || {})));
                return { status: r.status, ok: r.status >= 200 && r.status < 300, text: async () => r.body, json: async () => JSON.parse(r.body) };
              };
            })();
        """
    }
}

/** A tool implemented by a pack script. Permission checks for network happen inside the sandbox. */
class PackTool(private val sandbox: PackSandbox, override val spec: ToolSpec) : Tool {
    override val needs: Set<Permission> = emptySet()
    override fun call(args: String): String = sandbox.callTool(spec.name, args)
}

/** Real network adapter. Does not follow redirects: a redirect could leave the pack's allowed hosts. */
class HttpHostNet(private val maxBytes: Int = 1_000_000) : HostNet {
    private companion object { const val USER_AGENT = "Pocketnode/0.1 (personal assistant app for Android)" }

    override fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // Some services (Wikipedia among them) refuse the default Java agent, so say who is asking. A pack may set its own.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readUpTo(maxBytes + 1) } ?: ByteArray(0)
            if (bytes.size > maxBytes) throw IllegalStateException("response larger than $maxBytes bytes")
            return HostResponse(code, bytes.decodeToString())
        } finally {
            conn.disconnect()
        }
    }
}
