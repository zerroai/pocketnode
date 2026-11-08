package com.pocketnode.core

/** When the app asks for the phone's screen lock again. Pure rules, so they can be tested without a phone. */
object AppLockPolicy {
    const val GRACE_MILLIS = 30_000L

    /**
     * Whether coming back to the app should show the lock. [leftAtMillis] and [nowMillis] come from the same clock that does
     * not stop in sleep. A clock that went backwards (the phone restarted) counts as a long time away.
     */
    fun shouldLockOnReturn(enabled: Boolean, setUp: Boolean, alreadyLocked: Boolean, leftAtMillis: Long, nowMillis: Long, graceMillis: Long = GRACE_MILLIS): Boolean {
        if (!enabled || !setUp) return false
        if (alreadyLocked) return true
        val away = nowMillis - leftAtMillis
        return away < 0 || away > graceMillis
    }

    /** A fresh start of the app always asks, when the lock is on and setup is finished. */
    fun lockedAtStart(enabled: Boolean, setUp: Boolean): Boolean = enabled && setUp
}
