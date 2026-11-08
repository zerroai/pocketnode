package com.pocketnode.core

/** A capability such as `net:api.telegram.org` or `device:camera.snap`. */
data class Permission(val scope: String, val target: String = "*") {
    override fun toString() = "$scope:$target"
}

sealed interface Decision {
    data object Allowed : Decision
    data class Denied(val reason: String) : Decision
}

/**
 * The single place that decides whether a call may run. A call is allowed only if the pack
 * declared every permission it needs AND the user granted it. Sensitive scopes are never
 * granted by default.
 */
class PermissionBroker(
    private val sensitiveScopes: Set<String> = DEFAULT_SENSITIVE,
    /** When given, explicit grants and revocations survive a restart. */
    private val persist: PackKv? = null,
) {
    private val grants = mutableMapOf<String, MutableSet<Permission>>()
    private val revoked = mutableMapOf<String, MutableSet<Permission>>()
    private val loaded = mutableSetOf<String>()

    /** Reads this pack's saved decisions the first time it is touched. */
    private fun load(packId: String) {
        if (!loaded.add(packId) || persist == null) return
        for (line in persist.get(STORE, packId).orEmpty().lines()) {
            val parts = line.drop(1).split('\t')
            if (line.length < 2 || parts.size != 2) continue
            val p = Permission(parts[0], parts[1])
            when (line[0]) {
                '+' -> grants.getOrPut(packId) { mutableSetOf() } += p
                '-' -> revoked.getOrPut(packId) { mutableSetOf() } += p
            }
        }
    }

    private fun save(packId: String) {
        persist ?: return
        val text = grants[packId].orEmpty().joinToString("\n") { "+${it.scope}\t${it.target}" } + "\n" + revoked[packId].orEmpty().joinToString("\n") { "-${it.scope}\t${it.target}" }
        persist.set(STORE, packId, text.trim())
    }

    /** Grants everything the pack declared except sensitive scopes and anything the user took away. Called on install and at start. */
    @Synchronized
    fun grantDefaults(packId: String, declared: Set<Permission>) {
        load(packId)
        grants.getOrPut(packId) { mutableSetOf() } += declared.filterNot { isSensitive(it) || it in revoked[packId].orEmpty() }
    }

    /** An explicit user decision, the only way a sensitive scope becomes usable. */
    @Synchronized
    fun grant(packId: String, permission: Permission) {
        load(packId)
        revoked[packId]?.remove(permission)
        grants.getOrPut(packId) { mutableSetOf() } += permission
        save(packId)
    }

    @Synchronized
    fun revoke(packId: String, permission: Permission) {
        load(packId)
        grants[packId]?.remove(permission)
        revoked.getOrPut(packId) { mutableSetOf() } += permission
        save(packId)
    }

    @Synchronized
    fun authorize(packId: String, declared: Set<Permission>, needed: Set<Permission>): Decision {
        load(packId)
        for (p in needed) {
            if (declared.none { covers(it, p) }) return Decision.Denied("$packId did not declare $p")
            if (grants[packId].orEmpty().none { covers(it, p) }) return Decision.Denied("user has not granted $p to $packId")
        }
        return Decision.Allowed
    }

    /** Whether the owner must switch this permission on themselves. A device operation is sensitive if it is listed that way. */
    fun isSensitive(p: Permission): Boolean = p.scope in sensitiveScopes || (p.scope == "device" && DeviceOps.isSensitive(p.target))

    private fun covers(have: Permission, want: Permission) =
        have.scope == want.scope && ((have.target == "*" && want.scope != "device") || have.target == want.target)

    companion object {
        val DEFAULT_SENSITIVE = setOf("device.camera", "device.sms", "device.contacts.write", "exec")
        private const val STORE = "core.grants"
    }
}
