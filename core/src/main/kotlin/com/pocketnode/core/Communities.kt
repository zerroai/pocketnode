package com.pocketnode.core

import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A group of paired devices (family, friends, a team) that get the same shared tools and the same daily limit.
 * [perPersonPerDay] is how many tool calls each member may make in a day; 0 means no limit.
 */
data class Community(val id: String, val name: String, val tools: Set<String>, val members: Set<String>, val perPersonPerDay: Int)

class CommunityStore(private val db: SqlDb) {
    private val json = Json
    private val strings = SetSerializer(String.serializer())

    init {
        db.transaction { tx ->
            tx.exec("CREATE TABLE IF NOT EXISTS communities (id TEXT PRIMARY KEY, name TEXT NOT NULL, tools TEXT NOT NULL, members TEXT NOT NULL, per_day INTEGER NOT NULL)")
            tx.exec("CREATE TABLE IF NOT EXISTS community_usage (peer TEXT NOT NULL, day INTEGER NOT NULL, calls INTEGER NOT NULL, PRIMARY KEY (peer, day))")
        }
    }

    fun save(c: Community) {
        require(c.name.isNotBlank() && c.name.length <= 40) { "A community needs a name of 1 to 40 characters." }
        require(c.perPersonPerDay in 0..10_000) { "The daily limit is not valid." }
        db.transaction { tx ->
            val count = tx.query("SELECT COUNT(*) FROM communities WHERE id <> ?", c.id) { it.long(0) }.first()
            require(count < MAX) { "You can have at most $MAX communities." }
            tx.exec("INSERT OR REPLACE INTO communities (id, name, tools, members, per_day) VALUES (?, ?, ?, ?, ?)", c.id, c.name.trim(), json.encodeToString(strings, c.tools), json.encodeToString(strings, c.members), c.perPersonPerDay)
        }
    }

    fun list(): List<Community> = db.transaction { it.query("SELECT id, name, tools, members, per_day FROM communities ORDER BY name") { r ->
        Community(r.string(0), r.string(1), json.decodeFromString(strings, r.string(2)), json.decodeFromString(strings, r.string(3)), r.long(4).toInt())
    } }

    fun remove(id: String) { db.transaction { it.exec("DELETE FROM communities WHERE id = ?", id) } }

    /** A device that is unpaired leaves every community. */
    fun forget(peerId: String) { list().filter { peerId in it.members }.forEach { save(it.copy(members = it.members - peerId)) } }

    fun toolsFor(peerId: String): Set<String> = list().filter { peerId in it.members }.flatMap { it.tools }.toSet()

    /** The strictest daily limit among the communities the device is in; 0 when none sets one. */
    fun limitFor(peerId: String): Int = list().filter { peerId in it.members && it.perPersonPerDay > 0 }.minOfOrNull { it.perPersonPerDay } ?: 0

    /** Counts one call for [peerId] on [day] (days since 1970). False, and not counted, when the limit for that person is already used. */
    fun tryUse(peerId: String, day: Long): Boolean {
        val limit = limitFor(peerId)
        return db.transaction { tx ->
            val used = tx.query("SELECT calls FROM community_usage WHERE peer = ? AND day = ?", peerId, day) { it.long(0) }.firstOrNull() ?: 0L
            if (limit > 0 && used >= limit) false
            else { tx.exec("INSERT OR REPLACE INTO community_usage (peer, day, calls) VALUES (?, ?, ?)", peerId, day, used + 1); tx.exec("DELETE FROM community_usage WHERE day < ?", day - 30); true }
        }
    }

    fun usedToday(peerId: String, day: Long): Int = db.transaction { it.query("SELECT calls FROM community_usage WHERE peer = ? AND day = ?", peerId, day) { r -> r.long(0).toInt() }.firstOrNull() ?: 0 }

    companion object { const val MAX = 20 }
}
