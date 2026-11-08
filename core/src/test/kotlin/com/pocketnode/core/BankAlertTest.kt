package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** All messages here are invented. */
class BankAlertTest {
    @Test fun debitWithBalance() {
        val a = BankAlert("debit", 500_000, "NGN", 12_345_000, "1234", "")
        val r = BankAlertReader.read("Acct:***1234 Debit: NGN 5,000.00 Desc: POS purchase Avail Bal: NGN 123,450.00")!!
        assertEquals(a.copy(note = r.note), r)
    }

    @Test fun creditWithSymbol() {
        val r = BankAlertReader.read("Credit Alert! ₦25,000.50 received from ADA OKAFOR. A/C 0987. Balance: ₦40,100.00")!!
        assertEquals("credit", r.direction); assertEquals(2_500_050, r.amountMinor); assertEquals("NGN", r.currency); assertEquals(4_010_000, r.balanceMinor); assertEquals("0987", r.accountEnd)
    }

    @Test fun ghanaCedis() {
        val r = BankAlertReader.read("You have paid GHS 45.00 to ECG Prepaid. Bal GHS 1,200.10")!!
        assertEquals("debit", r.direction); assertEquals(4_500, r.amountMinor); assertEquals("GHS", r.currency); assertEquals(120_010, r.balanceMinor)
    }

    @Test fun dollarsAndNoBalance() {
        val r = BankAlertReader.read("Card purchase of \$19.99 at EXAMPLE STORE was debited from your account")!!
        assertEquals("debit", r.direction); assertEquals(1_999, r.amountMinor); assertEquals("USD", r.currency); assertNull(r.balanceMinor)
    }

    @Test fun thingsThatAreNotAlerts() {
        assertNull(BankAlertReader.read("Your OTP is 482913. Do not share it with anyone. Valid for 5 minutes."))
        assertNull(BankAlertReader.read("Hi, are you coming for dinner tonight?"))
        assertNull(BankAlertReader.read("Win NGN 1,000,000 now! Click the link to claim your prize today"))
        assertNull(BankAlertReader.read("Debited"))
        assertNull(BankAlertReader.read("Your account was debited. Visit a branch for details about this transaction."))
        assertNull(BankAlertReader.read("x".repeat(700)))
    }

    @Test fun firstDirectionWordWinsWhenBothAppear() {
        assertEquals("credit", BankAlertReader.read("Credited NGN 1,000.00 to your account after reversal of earlier debit")!!.direction)
    }
}
