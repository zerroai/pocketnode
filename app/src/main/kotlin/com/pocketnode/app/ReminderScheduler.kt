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
import com.pocketnode.core.Reminder
import com.pocketnode.core.ReminderStore

/**
 * Wakes the phone for each reminder and shows it as a notification, even when Pocketnode is closed. The alarm manager keeps
 * the alarms while the app is not running; after a restart, a time change or an app update they are set again from the store.
 * Exact timing needs Android's "alarms and reminders" permission on Android 12 and later; without it a reminder can be a few
 * minutes late, which [exact] reports so the assistant can say so.
 */
class ReminderScheduler(private val app: Context, val store: ReminderStore) {
    private val alarms get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Whether reminders will arrive on the minute. */
    fun exact(): Boolean = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    fun schedule(r: Reminder) {
        val pi = fireIntent(r.id)
        if (exact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
    }

    fun cancel(id: Long) {
        alarms.cancel(fireIntent(id))
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(id))
    }

    /** Sets every saved reminder again. Reminders already past fire at once, so one missed while the phone was off still shows. */
    fun rescheduleAll() { store.list().forEach(::schedule) }

    /** Shows the reminder, then moves it on (if it repeats) or removes it. */
    fun fire(id: Long) {
        val r = store.get(id) ?: return
        val late = System.currentTimeMillis() - r.at > 5 * 60_000
        show(r.id, r.text, late)
        store.fired(id)?.let(::schedule)
    }

    /** Sets the same reminder again [minutes] from now. */
    fun snooze(id: Long, text: String, minutes: Int = 10) {
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId(id))
        runCatching { store.add(text, System.currentTimeMillis() + minutes * 60_000L) }.getOrNull()?.let(::schedule)
    }

    private fun show(id: Long, text: String, late: Boolean) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply { description = "Reminders you asked your assistant to set" })
        }
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val snooze = PendingIntent.getBroadcast(
            app, notificationId(id), Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_SNOOZE).putExtra("id", id).putExtra("text", text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mark).setContentTitle(if (late) "Reminder (missed)" else "Reminder").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open).addAction(0, "Snooze 10 min", snooze).build()
        nm.notify(notificationId(id), n)
    }

    private fun fireIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        app, notificationId(id), Intent(app, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationId(id: Long) = 10_000 + (id % 1_000_000).toInt()

    companion object { const val CHANNEL = "reminders" }
}

/** One scheduler for the whole app, so an alarm that starts the app cold uses the same database as everything else. */
object Reminders {
    @Volatile private var scheduler: ReminderScheduler? = null

    fun scheduler(context: Context): ReminderScheduler = scheduler ?: synchronized(this) {
        scheduler ?: ReminderScheduler(context.applicationContext, ReminderStore(AppDb.get(context))).also { scheduler = it }
    }
}

/** Receives the alarm manager's wake-up for a reminder, and the Snooze button on the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = Reminders.scheduler(context)
        val id = intent.getLongExtra("id", -1)
        when (intent.action) {
            ACTION_FIRE -> scheduler.fire(id)
            ACTION_SNOOZE -> scheduler.snooze(id, intent.getStringExtra("text").orEmpty())
        }
    }

    companion object {
        const val ACTION_FIRE = "com.pocketnode.app.REMINDER_FIRE"
        const val ACTION_SNOOZE = "com.pocketnode.app.REMINDER_SNOOZE"
    }
}
