package com.pocketnode.relay

import com.pocketnode.core.Hex
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Configuration comes from the environment:
 *   RELAY_PUBLIC_URL    required, the public https address, e.g. https://relay.example.com
 *   RELAY_STORAGE_KEY   64 hex characters. Required unless RELAY_DEV=1.
 *   PORT                default 8088
 *   RELAY_DB            default ./relay.db
 *   FCM_SERVICE_ACCOUNT path to a Firebase service account JSON (optional; without it nothing is pushed)
 *   RELAY_TRUST_PROXY   1 when behind a proxy that sets X-Forwarded-For
 *   RELAY_DEV           1 to run with a throwaway storage key
 */
fun main() {
    val env = System.getenv()
    val publicUrl = env["RELAY_PUBLIC_URL"]?.trimEnd('/') ?: error("RELAY_PUBLIC_URL is required")
    val dev = env["RELAY_DEV"] == "1"
    val keyHex = env["RELAY_STORAGE_KEY"] ?: if (dev) Hex.encode(ByteArray(32).also { SecureRandom().nextBytes(it) }).also { System.err.println("Running with a throwaway storage key. Stored messages cannot be read after a restart.") } else error("RELAY_STORAGE_KEY is required")
    val store = RelayStore(JdbcSqlDb(env["RELAY_DB"] ?: "relay.db"), Sealer.fromHex(keyHex))
    val pusher: PushSender = env["FCM_SERVICE_ACCOUNT"]?.let { path -> FcmPusher(File(path).readText(), onTokenInvalid = {}) } ?: NoPush
    val server = RelayServer(store, pusher, RelayConfig(publicUrl, trustProxy = env["RELAY_TRUST_PROXY"] == "1"), port = env["PORT"]?.toInt() ?: 8088)
    server.start()
    Executors.newSingleThreadScheduledExecutor { Thread(it, "relay-purge").apply { isDaemon = true } }.scheduleAtFixedRate({ runCatching { store.purgeExpired() } }, 1, 1, TimeUnit.MINUTES)
    println("Relay listening on port ${server.port}, public address $publicUrl, push ${if (pusher === NoPush) "off" else "on"}")
    Thread.currentThread().join()
}
