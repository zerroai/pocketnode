package com.pocketnode.core

enum class Plan(val label: String) {
    None("No plan"),
    Trial("Trial"),
    Personal("Personal"),
}

/**
 * What the user is allowed to use, and until when. This is the only place that decides whether a
 * plan is active. Payment providers (store billing, a card gateway, licence keys) will produce an
 * [Entitlement]; nothing else in the app interprets money or dates.
 */
data class Entitlement(val plan: Plan, val startedAtMillis: Long = 0L, val endsAtMillis: Long? = null) {
    /** A plan with no end date never expires. */
    fun isActive(nowMillis: Long): Boolean = when (plan) {
        Plan.None -> false
        else -> endsAtMillis == null || nowMillis < endsAtMillis
    }

    /** Whole days left, rounded up, or null when the plan has no end date. Never negative. */
    fun daysLeft(nowMillis: Long): Int? = endsAtMillis?.let { end ->
        if (nowMillis >= end) 0 else ((end - nowMillis + DAY - 1) / DAY).toInt()
    }

    companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val TRIAL_DAYS = 14

        fun startTrial(nowMillis: Long, days: Int = TRIAL_DAYS) = Entitlement(Plan.Trial, nowMillis, nowMillis + days * DAY)
    }
}

/** Where the entitlement is kept. Implementations are provided by the app. */
interface EntitlementStore {
    fun load(): Entitlement
    fun save(entitlement: Entitlement)
}

class InMemoryEntitlementStore(private var value: Entitlement = Entitlement(Plan.None)) : EntitlementStore {
    override fun load() = value
    override fun save(entitlement: Entitlement) { value = entitlement }
}
