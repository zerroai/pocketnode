package com.pocketnode.relay

import com.pocketnode.core.SqlDb

data class InboxItem(val seq: Long, val routeId: String, val receivedAt: Long, val payload: String)
data class Device(val id: String, val pushKind: String, val pushToken: String)
data class Route(val id: String, val deviceId: String, val channel: String)

private class RawItem(val seq: Long, val routeId: String, val receivedAt: Long, val sealed: String)

/**
 * Devices, routes and the inbox. A device is a phone. A route says "deliver webhooks sent to this
 * address to that phone". The inbox holds messages until the phone acknowledges them.
 */
class RelayStore(
    private val db: SqlDb,
    private val sealer: Sealer,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPerDevice: Int = 500,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
) {
    init {
        db.transaction { tx ->
            tx.exec("CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, push_kind TEXT NOT NULL DEFAULT '', push_token TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, last_seen INTEGER NOT NULL)")
            tx.exec("CREATE TABLE IF NOT EXISTS routes (id TEXT PRIMARY KEY, device_id TEXT NOT NULL, channel TEXT NOT NULL, secret_hash TEXT NOT NULL, created INTEGER NOT NULL)")
            tx.exec("CREATE INDEX IF NOT EXISTS routes_device ON routes (device_id)")
            // Older databases lack the column that holds a channel's extra secret (the WhatsApp app secret).
            if (tx.query("SELECT COUNT(*) FROM pragma_table_info('routes') WHERE name = 'extra'") { it.long(0) }.first() == 0L) {
                tx.exec("ALTER TABLE routes ADD COLUMN extra TEXT NOT NULL DEFAULT ''")
            }
            tx.exec("CREATE TABLE IF NOT EXISTS inbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, device_id TEXT NOT NULL, route_id TEXT NOT NULL, received INTEGER NOT NULL, payload TEXT NOT NULL)")
            tx.exec("CREATE INDEX IF NOT EXISTS inbox_device ON inbox (device_id, seq)")
        }
    }

    /** Creates a device and returns its id and its secret. The secret is shown once and only its hash is kept. */
    fun createDevice(): Pair<String, String> {
        val id = Crypto.token(22)
        val secret = Crypto.token(43)
        val t = now()
        db.transaction { it.exec("INSERT INTO devices (id, secret_hash, created, last_seen) VALUES (?, ?, ?, ?)", id, Crypto.sha256Hex(secret), t, t) }
        return id to secret
    }

    /** Returns the device if the id and secret match, and notes that it was seen. */
    fun authenticate(id: String, secret: String): Device? = db.transaction { tx ->
        val row = tx.query("SELECT secret_hash, push_kind, push_token FROM devices WHERE id = ?", id) { Triple(it.string(0), it.string(1), it.string(2)) }.firstOrNull()
        if (row == null || !Crypto.same(row.first, Crypto.sha256Hex(secret))) return@transaction null
        tx.exec("UPDATE devices SET last_seen = ? WHERE id = ?", now(), id)
        Device(id, row.second, row.third)
    }

    fun device(id: String): Device? = db.transaction { tx ->
        tx.query("SELECT push_kind, push_token FROM devices WHERE id = ?", id) { Device(id, it.string(0), it.string(1)) }.firstOrNull()
    }

    fun setPush(deviceId: String, kind: String, token: String) {
        db.transaction { it.exec("UPDATE devices SET push_kind = ?, push_token = ? WHERE id = ?", kind, token, deviceId) }
    }

    /** [extra] is a second secret the relay must be able to read back (kept encrypted), such as the WhatsApp app secret used to check signatures. */
    fun createRoute(deviceId: String, channel: String, extra: String = ""): Pair<Route, String> {
        val id = Crypto.token(22)
        val secret = Crypto.token(40)
        val sealedExtra = if (extra.isEmpty()) "" else sealer.seal(extra.toByteArray(), "route|$id")
        db.transaction { it.exec("INSERT INTO routes (id, device_id, channel, secret_hash, created, extra) VALUES (?, ?, ?, ?, ?, ?)", id, deviceId, channel, Crypto.sha256Hex(secret), now(), sealedExtra) }
        return Route(id, deviceId, channel) to secret
    }

    /** The route's extra secret, or null if there is none or the route does not exist on [channel]. */
    fun routeExtra(routeId: String, channel: String): String? {
        val sealed = db.transaction { tx -> tx.query("SELECT extra FROM routes WHERE id = ? AND channel = ?", routeId, channel) { it.string(0) }.firstOrNull() }
        if (sealed.isNullOrEmpty()) return null
        return sealer.open(sealed, "route|$routeId")?.decodeToString()
    }

    /** The route, if it exists on [channel]. For callers that prove themselves some other way than with the route secret. */
    fun routeIfExists(routeId: String, channel: String): Route? = db.transaction { tx ->
        tx.query("SELECT device_id FROM routes WHERE id = ? AND channel = ?", routeId, channel) { Route(routeId, it.string(0), channel) }.firstOrNull()
    }

    /** True if [secret] is the route's secret. */
    fun routeSecretMatches(routeId: String, channel: String, secret: String): Boolean = findRoute(routeId, channel, secret) != null

    fun deleteRoute(deviceId: String, routeId: String): Boolean = db.transaction { tx ->
        val owned = tx.query("SELECT 1 FROM routes WHERE id = ? AND device_id = ?", routeId, deviceId) { 1 }.isNotEmpty()
        if (owned) { tx.exec("DELETE FROM routes WHERE id = ?", routeId); tx.exec("DELETE FROM inbox WHERE route_id = ?", routeId) }
        owned
    }

    fun routeCount(deviceId: String): Int = db.transaction { it.query("SELECT COUNT(*) FROM routes WHERE device_id = ?", deviceId) { r -> r.long(0).toInt() }.first() }

    /** The route if its address and secret both match. */
    fun findRoute(routeId: String, channel: String, secret: String): Route? = db.transaction { tx ->
        val row = tx.query("SELECT device_id, secret_hash FROM routes WHERE id = ? AND channel = ?", routeId, channel) { it.string(0) to it.string(1) }.firstOrNull()
        if (row == null || !Crypto.same(row.second, Crypto.sha256Hex(secret))) null else Route(routeId, row.first, channel)
    }

    /** Stores a message for the route's phone. The oldest messages are dropped past the per-device limit. */
    fun ingest(route: Route, payload: ByteArray) {
        val t = now()
        val sealed = sealer.seal(payload, "${route.deviceId}|${route.id}")
        db.transaction { tx ->
            tx.exec("INSERT INTO inbox (device_id, route_id, received, payload) VALUES (?, ?, ?, ?)", route.deviceId, route.id, t, sealed)
            tx.exec(
                "DELETE FROM inbox WHERE device_id = ? AND seq NOT IN (SELECT seq FROM inbox WHERE device_id = ? ORDER BY seq DESC LIMIT ?)",
                route.deviceId, route.deviceId, maxPerDevice,
            )
        }
    }

    fun fetch(deviceId: String, afterSeq: Long, limit: Int = 50): List<InboxItem> {
        val rows = db.transaction { tx ->
            tx.query("SELECT seq, route_id, received, payload FROM inbox WHERE device_id = ? AND seq > ? AND received > ? ORDER BY seq LIMIT ?", deviceId, afterSeq, now() - ttlMs, limit) {
                RawItem(it.long(0), it.string(1), it.long(2), it.string(3))
            }
        }
        return rows.mapNotNull { r ->
            val bytes = sealer.open(r.sealed, "$deviceId|${r.routeId}") ?: return@mapNotNull null
            InboxItem(r.seq, r.routeId, r.receivedAt, bytes.decodeToString())
        }
    }

    /** Removes everything up to and including [upToSeq]; the phone has it. */
    fun ack(deviceId: String, upToSeq: Long) {
        db.transaction { it.exec("DELETE FROM inbox WHERE device_id = ? AND seq <= ?", deviceId, upToSeq) }
    }

    /** Deletes expired messages. Call periodically. */
    fun purgeExpired(): Int = db.transaction { tx ->
        val n = tx.query("SELECT COUNT(*) FROM inbox WHERE received <= ?", now() - ttlMs) { it.long(0).toInt() }.first()
        tx.exec("DELETE FROM inbox WHERE received <= ?", now() - ttlMs)
        n
    }

    fun pending(deviceId: String): Int = db.transaction { it.query("SELECT COUNT(*) FROM inbox WHERE device_id = ?", deviceId) { r -> r.long(0).toInt() }.first() }
}
