package com.pocketnode.core

/** Minimal SQL surface the core needs. Android and the JVM tests each supply an adapter. */
interface SqlDb {
    /** Runs [block] atomically. Blocking and synchronous; never suspend inside it. */
    fun <T> transaction(block: (Tx) -> T): T
}

interface Tx {
    fun exec(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T>
}

interface Row {
    fun string(col: Int): String
    fun long(col: Int): Long
}
