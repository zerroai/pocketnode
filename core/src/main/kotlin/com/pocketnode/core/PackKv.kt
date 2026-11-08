package com.pocketnode.core

/** Pack-private key-value storage. A pack can only reach its own keys. */
interface PackKv {
    fun get(packId: String, key: String): String?
    fun set(packId: String, key: String, value: String)
    fun delete(packId: String, key: String)
    /** Drops everything the pack saved. Used when the owner removes a pack and asks for its data to go too. */
    fun deleteAll(packId: String)
}

class InMemoryPackKv : PackKv {
    private val map = mutableMapOf<Pair<String, String>, String>()
    @Synchronized override fun get(packId: String, key: String) = map[packId to key]
    @Synchronized override fun set(packId: String, key: String, value: String) { map[packId to key] = value }
    @Synchronized override fun delete(packId: String, key: String) { map.remove(packId to key) }
    @Synchronized override fun deleteAll(packId: String) { map.keys.removeAll { it.first == packId } }
}

class SqlPackKv(private val db: SqlDb) : PackKv {
    init {
        db.transaction { it.exec("CREATE TABLE IF NOT EXISTS pack_kv (pack_id TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY (pack_id, k))") }
    }

    override fun get(packId: String, key: String): String? =
        db.transaction { it.query("SELECT v FROM pack_kv WHERE pack_id = ? AND k = ?", packId, key) { r -> r.string(0) }.firstOrNull() }

    override fun set(packId: String, key: String, value: String) {
        db.transaction { it.exec("INSERT OR REPLACE INTO pack_kv (pack_id, k, v) VALUES (?, ?, ?)", packId, key, value) }
    }

    override fun delete(packId: String, key: String) {
        db.transaction { it.exec("DELETE FROM pack_kv WHERE pack_id = ? AND k = ?", packId, key) }
    }

    override fun deleteAll(packId: String) {
        db.transaction { it.exec("DELETE FROM pack_kv WHERE pack_id = ?", packId) }
    }

    /** True when the pack has saved anything at all. */
    fun hasData(packId: String): Boolean =
        db.transaction { it.query("SELECT COUNT(*) FROM pack_kv WHERE pack_id = ?", packId) { r -> r.long(0) }.first() } > 0
}
