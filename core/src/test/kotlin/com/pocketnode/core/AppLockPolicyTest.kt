package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockPolicyTest {
    private fun back(enabled: Boolean = true, setUp: Boolean = true, locked: Boolean = false, left: Long = 1_000, now: Long) =
        AppLockPolicy.shouldLockOnReturn(enabled, setUp, locked, left, now)

    @Test fun `a short trip away does not lock`() = assertFalse(back(now = 1_000 + 29_000))

    @Test fun `more than the grace time away locks`() = assertTrue(back(now = 1_000 + 30_001))

    @Test fun `switched off never locks`() = assertFalse(back(enabled = false, now = 10_000_000))

    @Test fun `before setup is finished there is nothing to lock`() = assertFalse(back(setUp = false, now = 10_000_000))

    @Test fun `once locked it stays locked until the phone says yes`() = assertTrue(back(locked = true, now = 1_001))

    @Test fun `a clock that went backwards, as after a restart, locks`() = assertTrue(back(left = 5_000_000, now = 1_000))

    @Test fun `a fresh start asks when the lock is on and setup is done, and not otherwise`() {
        assertTrue(AppLockPolicy.lockedAtStart(enabled = true, setUp = true))
        assertFalse(AppLockPolicy.lockedAtStart(enabled = true, setUp = false))
        assertFalse(AppLockPolicy.lockedAtStart(enabled = false, setUp = true))
    }
}
