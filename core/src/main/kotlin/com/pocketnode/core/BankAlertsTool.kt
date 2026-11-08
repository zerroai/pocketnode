package com.pocketnode.core

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Lets the assistant read the bank alerts the owner allowed the phone to keep. It reads only; it cannot see any other message. */
class BankAlertsTool(private val store: () -> BankAlertStore?, private val enabled: () -> Boolean, private val now: () -> Long = System::currentTimeMillis) : Tool {
    override val spec = ToolSpec(
        "bank.recent",
        "Lists the owner's recent bank alerts (money in or out) that the phone read from texts or bank app notifications the owner chose. Use it for questions such as what was spent today or what came in. It lists amounts, direction, balance when shown and the last four digits of the account.",
        """{"type":"object","properties":{"days":{"type":"integer","minimum":1,"maximum":90,"description":"How many days back, default 7"},"limit":{"type":"integer","minimum":1,"maximum":50,"description":"Most alerts to list, default 20"}}}""",
        idempotent = true,
    )
    override val needs: Set<Permission> = emptySet()

    private fun major(minor: Long) = BigDecimal(minor).movePointLeft(2).toPlainString()

    override fun call(args: String): String {
        if (!enabled()) return "Error: Reading bank alerts is switched off. The owner can turn it on in Settings, Bank alerts."
        val s = store() ?: return "Error: Not ready yet. Try again in a moment."
        val o = runCatching { Json.parseToJsonElement(args.ifBlank { "{}" }).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val days = ((o["days"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 7).coerceIn(1, 90)
        val limit = ((o["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val list = s.recent(limit, now() - days * 86_400_000L)
        return buildJsonObject {
            put("count", list.size)
            put("alerts", buildJsonArray {
                list.forEach { a ->
                    add(buildJsonObject {
                        put("at", java.time.Instant.ofEpochMilli(a.at).toString()); put("direction", a.alert.direction); put("amount", major(a.alert.amountMinor)); put("currency", a.alert.currency)
                        a.alert.balanceMinor?.let { put("balance", major(it)) }; a.alert.accountEnd?.let { put("accountEnd", it) }
                    })
                }
            })
            if (list.isEmpty()) put("note", "Nothing was read in that time. Alerts are only kept from the apps the owner chose.")
        }.toString()
    }
}
