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
import com.pocketnode.core.Briefing
import com.pocketnode.core.BriefingSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The morning briefing: once a day, at the time the owner chose, the assistant prepares a short summary and posts it as a notification
 * that opens the chat. It is off until the owner switches it on, and does nothing while the assistant is switched off.
 */
object MorningBriefing {
    private const val CHANNEL = "briefing"
    private const val NOTIFICATION = 20_001

    fun settings(context: Context): BriefingSettings {
        val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        return BriefingSettings(p.getBoolean("brief_on", false), p.getInt("brief_h", 7), p.getInt("brief_m", 30), p.getString("brief_place", "").orEmpty())
    }

    fun save(context: Context, s: BriefingSettings) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("brief_on", s.enabled).putInt("brief_h", s.hour).putInt("brief_m", s.minute).putString("brief_place", s.place.take(80)).apply()
        schedule(context)
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 20_000, Intent(context, BriefingReceiver::class.java).setAction(BriefingReceiver.ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets (or clears) the alarm for the next briefing. Called after a change, a restart, a time change and after each briefing. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val s = settings(context)
        val pi = alarmIntent(context)
        if (!s.enabled) { alarms.cancel(pi); return }
        val at = Briefing.nextAt(s.hour, s.minute, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Prepares one briefing now. [done] gets the chat id and the text, or a plain reason it could not be made. */
    fun run(context: Context, done: (sessionId: String?, text: String) -> Unit) {
        val s = settings(context)
        val id = "briefing:" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        CoreHost.runBriefing(id, Briefing.prompt(s.place)) { result -> done(if (result == null) null else id, result ?: "") }
    }

    fun notify(context: Context, sessionId: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Your daily summary" })
        }
        val open = PendingIntent.getActivity(
            context, 20_002, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, sessionId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val clean = text.replace(Regex("[ \\t]+"), " ").trim()
        val b = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_mark).setContentTitle("Your morning briefing")
            .setContentText(clean.lineSequence().filter { it.isNotBlank() }.joinToString("  ").take(140)).setStyle(NotificationCompat.BigTextStyle().bigText(clean.take(600)))
            .setAutoCancel(true).setContentIntent(open)
        nm.notify(NOTIFICATION, QuickReply.addTo(context, b, sessionId, NOTIFICATION, CHANNEL, "Your morning briefing").build())
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // The next one is set first, so a failure today does not lose tomorrow's.
        MorningBriefing.schedule(context)
        CoreHost.init(context)
        val pending = goAsync()
        MorningBriefing.run(context) { sessionId, text ->
            if (sessionId != null && text.isNotBlank()) MorningBriefing.notify(context, sessionId, text)
            pending.finish()
        }
    }

    companion object { const val ACTION = "com.pocketnode.app.BRIEFING" }
}
