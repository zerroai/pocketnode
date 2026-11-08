package com.pocketnode.core

import java.math.BigDecimal

/** What a bank's text or notification says happened. Amounts are in the smallest unit (kobo, pesewas, cents); null where the text did not say. */
data class BankAlert(
    val direction: String,
    val amountMinor: Long,
    val currency: String,
    val balanceMinor: Long?,
    /** The last digits of the account, if shown. Never more than four. */
    val accountEnd: String?,
    val note: String,
)

/**
 * Reads the short messages banks send about money moving. It is deliberately cautious: it answers only when it finds a clear
 * direction word (debit or credit and their usual synonyms) and an amount with a currency. Anything else, such as one-time
 * codes, adverts and chat, gives null. It stores nothing and sends nothing; whoever calls it decides what to keep.
 * The patterns come from common layouts seen in public help pages and invented examples; they are not tested on real messages.
 */
object BankAlertReader {
    private val CURRENCIES = mapOf("NGN" to "NGN", "₦" to "NGN", "N" to "NGN", "GHS" to "GHS", "GH₵" to "GHS", "₵" to "GHS", "USD" to "USD", "$" to "USD", "KES" to "KES", "KSH" to "KES", "ZAR" to "ZAR", "R" to "ZAR", "GBP" to "GBP", "£" to "GBP", "EUR" to "EUR", "€" to "EUR")
    private val money = Regex("(?i)(NGN|GHS|GH₵|USD|KES|KSH|ZAR|GBP|EUR|₦|₵|\\$|£|€|\\bN|\\bR)\\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)")
    private val debitWords = Regex("(?i)\\b(debit(?:ed)?|dr|withdrawal|withdrawn|purchase|spent|paid|payment to|transfer to|sent to|pos)\\b")
    private val creditWords = Regex("(?i)\\b(credit(?:ed)?|cr|deposit(?:ed)?|received|salary|transfer from|received from|inflow)\\b")
    private val otp = Regex("(?i)\\b(otp|one[- ]time|verification code|passcode|pin)\\b")
    private val balance = Regex("(?i)\\b(?:avail(?:able)?\\.? ?bal(?:ance)?|bal(?:ance)?)\\b[:\\s-]*")
    private val account = Regex("(?i)(?:a/?c|acct?|account)[^0-9*xX]{0,12}[*xX]*\\s*([0-9]{3,4})\\b")

    private fun toMinor(s: String): Long = BigDecimal(s.replace(",", "")).movePointRight(2).toLong()

    fun read(text: String): BankAlert? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        if (t.length < 15 || t.length > 600 || otp.containsMatchIn(t)) return null
        val deb = debitWords.find(t); val cred = creditWords.find(t)
        val direction = when {
            deb != null && cred == null -> "debit"
            cred != null && deb == null -> "credit"
            deb != null && cred != null -> if (deb.range.first < cred.range.first) "debit" else "credit"
            else -> return null
        }
        // The balance is the amount after a balance word; the transaction is the first amount that is not it.
        val balanceStart = balance.find(t)?.range?.last?.plus(1)
        val amounts = money.findAll(t).toList()
        if (amounts.isEmpty()) return null
        val bal = balanceStart?.let { start -> amounts.firstOrNull { it.range.first >= start } }
        val main = amounts.firstOrNull { it !== bal } ?: return null
        val cur = CURRENCIES[main.groupValues[1].uppercase()] ?: CURRENCIES[main.groupValues[1]] ?: return null
        val amount = runCatching { toMinor(main.groupValues[2]) }.getOrNull()?.takeIf { it > 0 } ?: return null
        return BankAlert(direction, amount, cur, bal?.let { runCatching { toMinor(it.groupValues[2]) }.getOrNull() }, account.find(t)?.groupValues?.get(1), t.take(160))
    }
}
