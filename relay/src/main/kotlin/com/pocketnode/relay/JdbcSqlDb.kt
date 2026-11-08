package com.pocketnode.relay

import com.pocketnode.core.Row
import com.pocketnode.core.SqlDb
import com.pocketnode.core.Tx
import java.sql.Connection
import java.sql.DriverManager

/** The core's SQL interface over SQLite via JDBC. One connection, so writes are serialized. */
class JdbcSqlDb(path: String) : SqlDb, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also { c ->
        c.createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA synchronous=NORMAL") }
    }

    @Synchronized
    override fun <T> transaction(block: (Tx) -> T): T {
        conn.autoCommit = false
        try {
            val r = block(JdbcTx(conn))
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = conn.close()

    private class JdbcTx(private val conn: Connection) : Tx {
        override fun exec(sql: String, vararg args: Any?) {
            conn.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate() }
        }

        override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    val row = object : Row {
                        override fun string(col: Int) = rs.getString(col + 1) ?: ""
                        override fun long(col: Int) = rs.getLong(col + 1)
                    }
                    buildList { while (rs.next()) add(map(row)) }
                }
            }
    }
}
