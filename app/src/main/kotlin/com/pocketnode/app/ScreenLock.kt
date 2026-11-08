package com.pocketnode.app

/**
 * The bridge to Android's own "confirm your screen lock" screen. The main activity fills these in while it exists.
 * The app never sees or stores the PIN, pattern or password; it only learns whether the phone accepted it.
 */
object ScreenLock {
    /** Shows the phone's lock screen check and reports whether it was passed. Null when no screen is available to show it on. */
    @Volatile var confirm: (((Boolean) -> Unit) -> Unit)? = null

    /** Whether the phone has a PIN, pattern or password set. Without one there is nothing to ask for. */
    @Volatile var isSecure: () -> Boolean = { false }
}
