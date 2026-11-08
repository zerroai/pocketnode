package com.pocketnode.core

/** A bank alert that was kept, with where and when it was seen. */
data class SavedBankAlert(val id: Long, val at: Long, val source: String, val alert: BankAlert)

/**
 * The alerts the owner allowed the phone to read, kept in the app's database. Only the parsed fields and a short note are kept, never
 * a whole message. The same payment is often announced twice (a text and the bank's app); the second one within a few minutes is dropped.
 */
class BankAlertStore(private val db: SqlDb, private val now: () -> Long = System::currentTimeMillis) {
    init {
        db.transaction { tx ->
            tx.exec(
                "CREATE TABLE IF NOT EXISTS bank_alerts (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, source TEXT NOT NULL, direction TEXT NOT NULL, amount INTEGER NOT NULL, " +
                    "currency TEXT NOT NULL, balance INTEGER, account_end TEXT NOT NULL, note TEXT NOT NULL)",
            )
        }
    }

    /** Keeps [a] unless the same payment was already kept in the last [DUPLICATE_WINDOW_MS]. Returns whether it was kept. */
    fun add(a: BankAlert, source: String): Boolean = db.transaction { tx ->
        val t = now()
        val same = tx.query(
            "SELECT 1 FROM bank_alerts WHERE direction = ? AND amount = ? AND currency = ? AND at > ? AND (account_end = ? OR account_end = '' OR ? = '')",
            a.direction, a.amountMinor, a.currency, t - DUPLICATE_WINDOW_MS, a.accountEnd.orEmpty(), a.accountEnd.orEmpty(),
        ) { 1 }.isNotEmpty()
        if (same) return@transaction false
        tx.exec("INSERT INTO bank_alerts (at, source, direction, amount, currency, balance, account_end, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", t, source, a.direction, a.amountMinor, a.currency, a.balanceMinor, a.accountEnd.orEmpty(), a.note)
        val count = tx.query("SELECT COUNT(*) FROM bank_alerts") { it.long(0) }.first()
        if (count > MAX_KEPT) tx.exec("DELETE FROM bank_alerts WHERE id IN (SELECT id FROM bank_alerts ORDER BY id LIMIT ?)", count - MAX_KEPT)
        true
    }

    fun recent(limit: Int = 20, sinceMillis: Long = 0): List<SavedBankAlert> = db.transaction { tx ->
        tx.query("SELECT id, at, source, direction, amount, currency, balance, account_end, note FROM bank_alerts WHERE at >= ? ORDER BY id DESC LIMIT ?", sinceMillis, limit.coerceIn(1, 100)) { r ->
            val bal = r.string(6)
            SavedBankAlert(r.long(0), r.long(1), r.string(2), BankAlert(r.string(3), r.long(4), r.string(5), bal.toLongOrNull(), r.string(7).ifEmpty { null }, r.string(8)))
        }
    }

    fun clear() = db.transaction { it.exec("DELETE FROM bank_alerts") }

    companion object {
        const val DUPLICATE_WINDOW_MS = 5 * 60_000L
        const val MAX_KEPT = 500
    }
}
