package com.pocketnode.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone restarts (or the clock, time zone or app changes), sets the saved reminders again; after a restart it also
 * brings the assistant back, but only for people who finished setup.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        runCatching { Reminders.scheduler(context).rescheduleAll() }
        runCatching { MorningBriefing.schedule(context) }
        runCatching { AgentScheduler.rescheduleLater(context) }
        val done = context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("onboarded", false)
        if (action == Intent.ACTION_BOOT_COMPLETED && done) CoreService.start(context)
    }
}
