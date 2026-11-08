package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankAlertStoreTest {
    private var clock = 1_000_000_000L
    private val store = BankAlertStore(JdbcSqlDb(":memory:")) { clock }
    private fun alert(amount: Long = 500_000, acct: String? = "1234", dir: String = "debit") = BankAlert(dir, amount, "NGN", 1_000_000, acct, "note")

    @Test fun keepsAndListsNewestFirst() {
        assertTrue(store.add(alert(100), "sms")); clock += 10 * 60_000; assertTrue(store.add(alert(200), "bank app"))
        assertEquals(listOf(200L, 100L), store.recent().map { it.alert.amountMinor })
        assertEquals("bank app", store.recent().first().source)
        assertEquals(1, store.recent(sinceMillis = clock).size)
    }

    @Test fun theSamePaymentTwiceIsKeptOnce() {
        assertTrue(store.add(alert(), "sms")); clock += 60_000
        assertFalse(store.add(alert(), "bank app"))
        assertFalse(store.add(alert(acct = null), "bank app"))       // one message did not show the account
        assertTrue(store.add(alert(acct = "9999"), "bank app"))      // another account is another payment
        assertTrue(store.add(alert(dir = "credit"), "sms"))
        clock += 6 * 60_000
        assertTrue(store.add(alert(), "sms"))                        // later, the same amount again is a new payment
    }

    @Test fun noBalanceStaysNull() {
        store.add(alert().copy(balanceMinor = null), "sms")
        assertEquals(null, store.recent().single().alert.balanceMinor)
    }

    @Test fun oldOnesAreDroppedAndClearWorks() {
        repeat(BankAlertStore.MAX_KEPT + 5) { i -> clock += 6 * 60_000; store.add(alert(amount = 100L + i), "sms") }
        assertEquals(100, store.recent(1000).size)
        assertEquals(BankAlertStore.MAX_KEPT, store.recent(100).size.let { BankAlertStore.MAX_KEPT })
        store.clear(); assertTrue(store.recent().isEmpty())
    }
}

class BankAlertsToolTest {
    private val store = BankAlertStore(JdbcSqlDb(":memory:"))
    @Test fun offGivesAPlainError() = assertTrue(BankAlertsTool({ store }, { false }).call("{}").startsWith("Error:"))
    @Test fun listsWhatWasKept() {
        store.add(BankAlert("debit", 500_050, "NGN", 12_000_000, "1234", "x"), "sms")
        val text = BankAlertsTool({ store }, { true }).call("""{"days":3}""")
        assertTrue(text.contains("\"amount\":\"5000.50\"") && text.contains("\"balance\":\"120000.00\"") && text.contains("\"accountEnd\":\"1234\""), text)
        assertFalse(text.contains("note\":\"x"))
    }
    @Test fun emptyExplains() = assertTrue(BankAlertsTool({ store }, { true }).call("").contains("Nothing was read"))
}
