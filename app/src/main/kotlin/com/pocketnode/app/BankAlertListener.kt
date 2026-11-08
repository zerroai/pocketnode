package com.pocketnode.app

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pocketnode.core.BankAlertReader

/** Which apps the owner allowed to be read for bank alerts, and whether reading is on. Nothing is read from any other app. */
object BankAlertSettings {
    private fun prefs(c: Context) = c.getSharedPreferences("bank_alerts", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()
    fun sources(c: Context): Set<String> = prefs(c).getStringSet("sources", emptySet()).orEmpty()
    fun addSource(c: Context, pkg: String) = prefs(c).edit().putStringSet("sources", sources(c) + pkg).apply()
    fun removeSource(c: Context, pkg: String) = prefs(c).edit().putStringSet("sources", sources(c) - pkg).apply()
    fun accessGranted(c: Context) = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
}

/**
 * Reads notifications from the apps the owner chose (their bank's app, their texts app), keeps the ones that look like money moving
 * (see BankAlertReader), and throws the rest away at once. Android only lets this run after the owner turns on "Notification access"
 * for Pocketnode in the phone's settings.
 */
class BankAlertListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val ctx = applicationContext
        if (!BankAlertSettings.enabled(ctx) || sbn.packageName !in BankAlertSettings.sources(ctx)) return
        val n = sbn.notification ?: return
        val e = n.extras
        val text = listOfNotNull(e.getCharSequence("android.title"), e.getCharSequence("android.bigText") ?: e.getCharSequence("android.text")).joinToString(" ")
        if (!AppFeatures.on("bank_alerts")) return
        val alert = BankAlertReader.read(text) ?: return
        val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        CoreHost.init(ctx)
        CoreHost.whenReady { CoreHost.bankAlerts.add(alert, label) }
    }
}
