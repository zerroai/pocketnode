package com.pocketnode.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RelayCredentials(val deviceId: String, val secret: String)
data class RelayRoute(val routeId: String, val hookUrl: String, val secret: String)
data class RelayItem(val seq: Long, val routeId: String, val receivedAt: Long, val payload: String)

class RelayException(message: String, val status: Int) : Exception(message) {
    /** The relay no longer knows this phone, so it must register again. */
    val notRecognized: Boolean get() = status == 401
}

/** The phone's side of the relay's API. Blocking; call from a worker thread. */
class RelayClient(private val base: String, private val http: HostNet) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = base.trimEnd('/')

    private fun call(method: String, path: String, creds: RelayCredentials?, body: String? = null): String {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (creds != null) put("Authorization", "Bearer ${creds.deviceId}.${creds.secret}")
        }
        val r = try { http.fetch("$root$path", method, headers, body) } catch (e: Exception) { throw RelayException("Could not reach the relay.", 0) }
        if (r.status !in 200..299) {
            val message = runCatching { json.parseToJsonElement(r.body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw RelayException(message ?: "The relay answered with an error (${r.status}).", r.status)
        }
        return r.body
    }

    fun register(): RelayCredentials {
        val o = json.parseToJsonElement(call("POST", "/v1/devices", null, "{}")).jsonObject
        return RelayCredentials(o.getValue("deviceId").jsonPrimitive.content, o.getValue("secret").jsonPrimitive.content)
    }

    fun setPush(creds: RelayCredentials, kind: String, token: String) {
        call("PUT", "/v1/devices/me/push", creds, buildJsonObject { put("kind", JsonPrimitive(kind)); put("token", JsonPrimitive(token)) }.toString())
    }

    /** [appSecret] is only for WhatsApp: the Meta app secret the relay uses to check that events really come from Meta. */
    fun createRoute(creds: RelayCredentials, channel: String, appSecret: String? = null): RelayRoute {
        val body = buildJsonObject { put("channel", JsonPrimitive(channel)); if (appSecret != null) put("appSecret", JsonPrimitive(appSecret)) }.toString()
        val o = json.parseToJsonElement(call("POST", "/v1/routes", creds, body)).jsonObject
        return RelayRoute(o.getValue("routeId").jsonPrimitive.content, o.getValue("hookUrl").jsonPrimitive.content, o.getValue("secret").jsonPrimitive.content)
    }

    fun deleteRoute(creds: RelayCredentials, routeId: String) { call("DELETE", "/v1/routes/$routeId", creds) }

    /** Posts to another node's address (a route URL and its secret). Needs no account of our own. */
    fun post(routeUrl: String, routeSecret: String, body: String) {
        val r = try { http.fetch(routeUrl, "POST", mapOf("Content-Type" to "application/json", "X-Route-Secret" to routeSecret), body) } catch (e: Exception) { throw RelayException("Could not reach the relay.", 0) }
        if (r.status !in 200..299) throw RelayException(if (r.status == 404) "That device is not reachable any more." else "The relay answered with an error (${r.status}).", r.status)
    }

    /** Messages after [after]. With [waitSeconds] the call stays open until something arrives or the time is up. */
    fun inbox(creds: RelayCredentials, after: Long, waitSeconds: Int): List<RelayItem> {
        val o = json.parseToJsonElement(call("GET", "/v1/inbox?after=$after&wait=$waitSeconds", creds)).jsonObject
        return o.getValue("items").jsonArray.map {
            val i = it.jsonObject
            RelayItem(i.getValue("seq").jsonPrimitive.long(), i.getValue("route").jsonPrimitive.content, i.getValue("receivedAt").jsonPrimitive.long(), i.getValue("payload").jsonPrimitive.content)
        }
    }

    fun ack(creds: RelayCredentials, upTo: Long) { call("POST", "/v1/inbox/ack", creds, buildJsonObject { put("upTo", JsonPrimitive(upTo)) }.toString()) }

    private fun JsonPrimitive.long(): Long = longOrNull ?: throw RelayException("The relay sent something unexpected.", 0)
}

/** What must be remembered to receive messages through the relay. */
data class RelayLink(val credentials: RelayCredentials, val routeId: String, val hookSecret: String)

/** Switches a channel pack between receiving through the relay and receiving directly. */
class RelaySetup(private val client: RelayClient) {
    /**
     * Registers this phone if needed, creates a route, and points the channel at it. If pointing the
     * channel fails the route is removed again, so nothing is left half set up.
     */
    fun enable(existing: RelayCredentials?, channelSandbox: PackSandbox): RelayLink {
        val creds = existing ?: client.register()
        val route = try { client.createRoute(creds, "telegram") } catch (e: RelayException) {
            if (!e.notRecognized || existing == null) throw e
            // The relay forgot this phone: register again and retry once.
            return enable(null, channelSandbox)
        }
        try {
            channelSandbox.callTool("channel.webhook.set", buildJsonObject { put("url", JsonPrimitive(route.hookUrl)); put("secret", JsonPrimitive(route.secret)) }.toString())
        } catch (e: Exception) {
            runCatching { client.deleteRoute(creds, route.routeId) }
            throw e
        }
        return RelayLink(creds, route.routeId, route.secret)
    }

    /** Points the channel back at direct delivery and removes the route. Each step is tried even if the other fails. */
    fun disable(link: RelayLink, channelSandbox: PackSandbox) {
        val first = runCatching { channelSandbox.callTool("channel.webhook.clear", "{}") }
        runCatching { client.deleteRoute(link.credentials, link.routeId) }
        first.getOrThrow()
    }
}

/**
 * Fetches this phone's messages from the relay and hands each to [handle]. Owns the read position
 * (tied to this registration, because a different relay counts from one again) and tells the relay
 * to forget messages only after they were handled.
 */
class RelayInbox(
    private val client: RelayClient,
    private val credentials: () -> RelayCredentials,
    private val kv: PackKv,
    private val namespace: String = "core",
    private val waitSeconds: Int = 25,
) {
    private fun cursorKey(creds: RelayCredentials) = "core.relay.cursor:${creds.deviceId}"

    /** Handles whatever is waiting (or arrives within the wait). Returns how many items were handled. */
    fun pollOnce(handle: (RelayItem) -> Unit): Int {
        val creds = credentials()
        val after = kv.get(namespace, cursorKey(creds))?.toLongOrNull() ?: 0L
        val items = client.inbox(creds, after, waitSeconds)
        for (item in items) {
            handle(item)
            kv.set(namespace, cursorKey(creds), item.seq.toString())
        }
        if (items.isNotEmpty()) client.ack(creds, items.last().seq)
        return items.size
    }
}

/** Handles a channel's messages that arrive through the relay, through the same crash-safe path as polling. */
class RelayInboxRunner(
    client: RelayClient,
    credentials: () -> RelayCredentials,
    private val packId: String,
    private val sandbox: PackSandbox,
    private val runner: ChannelRunner,
    kv: PackKv,
    waitSeconds: Int = 25,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inbox = RelayInbox(client, credentials, kv, packId, waitSeconds)

    fun pollOnce(): Int = inbox.pollOnce { handle(it) }

    /** Turns one relay item into messages, answers them and sends the replies. */
    fun handle(item: RelayItem) {
        val args = buildJsonObject { put("payload", JsonPrimitive(item.payload)) }.toString()
        val messages = json.decodeFromString(ListSerializer(ChannelMessage.serializer()), sandbox.callTool("channel.parse", args))
        for (m in messages.sortedBy { it.id }) runner.deliver(m) { }
    }
}
