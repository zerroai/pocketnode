package com.pocketnode.core

import java.sql.Connection
import java.sql.DriverManager

/** JVM test adapter over sqlite-jdbc. Android uses its own adapter over SQLiteDatabase. */
class JdbcSqlDb(path: String) : SqlDb, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    @Synchronized
    override fun <T> transaction(block: (Tx) -> T): T {
        conn.autoCommit = false
        try {
            val result = block(JdbcTx(conn))
            conn.commit()
            return result
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
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeUpdate()
            }
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
