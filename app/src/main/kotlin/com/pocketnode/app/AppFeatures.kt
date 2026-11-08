package com.pocketnode.app

import com.pocketnode.core.FeatureSwitch

/** What this build has switched off. Fixed when the app is built, so a feature that is off cannot be turned on from the phone. */
object AppFeatures {
    val switch = FeatureSwitch(BuildConfig.DISABLED_FEATURES, BuildConfig.DISABLED_TOOLS)
    fun on(id: String) = switch.enabled(id)
    fun toolOn(name: String) = switch.toolEnabled(name)
}
