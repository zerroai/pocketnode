package com.pocketnode.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Wakes the phone for the next agent that is due. One alarm is kept, for the earliest planned start; when it goes off every agent
 * that is due runs and the alarm is set again. Like reminders, it is set again after a restart or a time change.
 */
object AgentScheduler {
    private const val CHANNEL = "agents"
    private const val ALARM_ID = 30_000

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, ALARM_ID, Intent(context, AgentReceiver::class.java).setAction(AgentReceiver.ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the alarm for the earliest planned start, or clears it when there is none. Safe to call from any thread once the app has started. */
    fun schedule(context: Context) {
        val store = runCatching { CoreHost.agents }.getOrNull() ?: return
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context)
        val at = store.list().filter { it.enabled && !it.state.paused && it.nextAt > 0 }.minOfOrNull { it.nextAt }
        if (at == null) { alarms.cancel(pi); return }
        val t = maxOf(at, System.currentTimeMillis() + 1000)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }

    /** For the boot receiver: starts the app's core, waits for the database, then sets the alarm. */
    fun rescheduleLater(context: Context) {
        CoreHost.init(context)
        CoreHost.whenReady { schedule(context) }
    }

    fun notify(context: Context, agentName: String, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agents", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "What your agents tell you" })
        }
        val open = PendingIntent.getActivity(context, 30_001, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_mark).setContentTitle(title).setContentText(text.replace(Regex("\\s+"), " ").take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text).setSummaryText(agentName)).setAutoCancel(true).setContentIntent(open).build()
        nm.notify(30_100 + (agentName.hashCode() and 0xffff), n)
    }
}

class AgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        CoreHost.init(context)
        val pending = goAsync()
        CoreHost.runDueAgents { pending.finish() }
    }

    companion object { const val ACTION = "com.pocketnode.app.AGENTS_DUE" }
}
