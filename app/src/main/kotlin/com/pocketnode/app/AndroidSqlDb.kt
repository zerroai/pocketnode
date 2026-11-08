package com.pocketnode.app

import android.content.Context
import android.util.Log
import com.pocketnode.core.Row
import com.pocketnode.core.SqlDb
import com.pocketnode.core.Tx
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File

/**
 * Adapter from the core's [SqlDb] to an encrypted SQLite file (SQLCipher). The schema is created by `SqlRunStore`.
 *
 * On start it makes sure the file is locked with this phone's key:
 * - a new file is made locked;
 * - an older plain file is converted once, checked, then the plain copy is erased (see [DbMigration]);
 * - a locked file whose key is gone cannot be read, so it is set aside and a new empty one is started ([startedFresh]).
 * If a conversion fails the plain file is left as it was and keeps being used, and the next start tries again.
 */
class AndroidSqlDb(context: Context, name: String = "pocketnode.db") : SqlDb {
    /** True when a locked file could not be opened and was set aside. The screens and the Activity record say so once. */
    @Volatile var startedFresh = false
        private set

    /** False only while a failed conversion leaves the data in a plain file. */
    @Volatile var locked = true
        private set

    private val helper: SQLiteOpenHelper

    init {
        System.loadLibrary("sqlcipher")
        val app = context.applicationContext
        val file = app.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val keys = DbKey(app)
        val key = keys.getOrCreate()
        // A key that cannot be opened protects nothing readable, so a new one is made when there is nothing to unlock.
        fun freshKey(): ByteArray { keys.reset(); return keys.getOrCreate() ?: error("No key could be saved for the database") }
        val password: ByteArray = when {
            !file.exists() -> key ?: freshKey()
            DbMigration.isPlain(file) -> {
                if (key != null && DbMigration.convert(file, key)) key else { locked = false; ByteArray(0) }
            }
            key != null && DbMigration.opens(file, key) -> key
            else -> {
                // Locked, and the key is gone or wrong. Keep the bytes for a developer, but start a usable empty record.
                DbMigration.setAside(file)
                startedFresh = true
                freshKey()
            }
        }
        helper = object : SQLiteOpenHelper(app, name, password, null, 1, 0, null, null, false) {
            override fun onCreate(db: SQLiteDatabase) {}
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        helper.writableDatabase // fail now, on the thread that asked, not in the middle of some later write
    }

    override fun <T> transaction(block: (Tx) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(AndroidTx(db))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /** Debug builds only: a plain copy for a developer to read. Never call this in release code. */
    fun exportPlainTo(target: File): Boolean = DbMigration.exportPlain(helper.writableDatabase, target)

    private class AndroidTx(private val db: SQLiteDatabase) : Tx {
        override fun exec(sql: String, vararg args: Any?) {
            db.execSQL(sql, args.map { it }.toTypedArray())
        }

        override fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> {
            db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
                val row = object : Row {
                    override fun string(col: Int): String = c.getString(col) ?: ""
                    override fun long(col: Int): Long = c.getLong(col)
                }
                return buildList { while (c.moveToNext()) add(map(row)) }
            }
        }
    }
}

/** One-time conversion of the plain database into a locked one, and the checks around it. */
internal object DbMigration {
    private const val TAG = "Pocketnode"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /** A plain SQLite file starts with a fixed text; a locked one looks like random bytes. */
    fun isPlain(file: File): Boolean = try {
        file.inputStream().use { s -> ByteArray(PLAIN_HEADER.size).let { b -> s.read(b) == b.size && b.contentEquals(PLAIN_HEADER) } }
    } catch (e: Exception) { false }

    /** Whether [key] unlocks [file]. */
    fun opens(file: File, key: ByteArray): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READONLY, null, null).use { db -> tableCounts(db); true }
    } catch (e: Exception) { Log.w(TAG, "database does not open with the saved key: ${e.message}"); false }

    /**
     * Copies everything into a new locked file next to the plain one, compares the row count of every table, and only then
     * swaps them and erases the plain file. Nothing is removed before the new file has been checked. Returns false (and leaves
     * the plain file as it was) on any problem.
     */
    fun convert(plain: File, key: ByteArray): Boolean {
        val tmp = File(plain.path + ".locking")
        cleanup(tmp)
        return try {
            val before: Map<String, Long>
            val version: Long
            SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
                before = tableCounts(db)
                version = db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                // ATTACH does not create the file here (it fails with "unable to open"), so an empty one is made first.
                tmp.createNewFile()
                db.execSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS locked KEY \"${String(key)}\"")
                try {
                    db.rawExecSQL("SELECT sqlcipher_export('locked');")
                } finally {
                    db.rawExecSQL("DETACH DATABASE locked;")
                }
            }
            val after = SQLiteDatabase.openDatabase(tmp.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
                db.version = version.toInt()
                tableCounts(db)
            }
            check(before == after) { "row counts differ after conversion: $before vs $after" }
            // Swap. The plain bytes are overwritten before the file goes.
            erase(plain)
            check(tmp.renameTo(plain)) { "could not move the locked file into place" }
            check(opens(plain, key)) { "the locked file does not open after the swap" }
            Log.i(TAG, "database converted to a locked file (${after.size} tables, ${after.values.sum()} rows)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "database conversion failed; the plain file is kept and used for now: ${e.message}", e)
            cleanup(tmp)
            false
        }
    }

    /** Moves a file that cannot be unlocked out of the way without deleting it. */
    fun setAside(file: File) {
        val dest = File(file.path + ".unreadable")
        cleanup(dest)
        if (!file.renameTo(dest)) erase(file)
        listOf("-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /** Debug builds only. Writes a plain copy of [db] to [target]. */
    fun exportPlain(db: SQLiteDatabase, target: File): Boolean = try {
        target.delete()
        target.createNewFile()
        db.execSQL("ATTACH DATABASE '${target.path.replace("'", "''")}' AS plainout KEY ''")
        try { db.rawExecSQL("SELECT sqlcipher_export('plainout');") } finally { db.rawExecSQL("DETACH DATABASE plainout;") }
        true
    } catch (e: Exception) { Log.e(TAG, "debug export failed: ${e.message}"); false }

    private fun tableCounts(db: SQLiteDatabase): Map<String, Long> {
        val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        return names.associateWith { n -> db.rawQuery("SELECT COUNT(*) FROM \"${n.replace("\"", "\"\"")}\"", null).use { c -> c.moveToFirst(); c.getLong(0) } }
    }

    private fun cleanup(file: File) { erase(file); listOf("-journal", "-wal", "-shm").forEach { File(file.path + it).delete() } }

    /** Overwrites a file with zeros, then deletes it (and its journal files), so a plain copy is not left behind in free space. Best effort. */
    private fun erase(file: File) {
        eraseFile(file)
        listOf("-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }
}
