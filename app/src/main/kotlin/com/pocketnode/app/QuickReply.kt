package com.pocketnode.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * The Reply box on a notification. The owner types an answer in the notification shade; it goes to the same chat and the
 * assistant's answer replaces the notification text. Nothing is shown on the lock screen beyond what the notification already showed.
 */
object QuickReply {
    private const val KEY = "reply_text"
    const val ACTION = "com.pocketnode.app.QUICK_REPLY"
    const val EXTRA_SESSION = "session"
    const val EXTRA_NOTIFICATION = "notification"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_TITLE = "title"

    /** Adds a Reply action to a notification builder. */
    fun addTo(context: Context, builder: NotificationCompat.Builder, sessionId: String, notificationId: Int, channel: String, title: String): NotificationCompat.Builder {
        val input = RemoteInput.Builder(KEY).setLabel("Reply").build()
        val intent = Intent(context, QuickReplyReceiver::class.java).setAction(ACTION)
            .putExtra(EXTRA_SESSION, sessionId).putExtra(EXTRA_NOTIFICATION, notificationId).putExtra(EXTRA_CHANNEL, channel).putExtra(EXTRA_TITLE, title)
        // A reply box needs a changeable intent; Android fills the typed text into it.
        val pending = PendingIntent.getBroadcast(context, notificationId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = NotificationCompat.Action.Builder(R.drawable.ic_mc_send, "Reply", pending).addRemoteInput(input).setAllowGeneratedReplies(false).build()
        return builder.addAction(action)
    }

    fun text(intent: Intent): String? = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

class QuickReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickReply.ACTION) return
        val text = QuickReply.text(intent) ?: return
        val session = intent.getStringExtra(QuickReply.EXTRA_SESSION) ?: return
        val id = intent.getIntExtra(QuickReply.EXTRA_NOTIFICATION, 0)
        val channel = intent.getStringExtra(QuickReply.EXTRA_CHANNEL) ?: return
        val title = intent.getStringExtra(QuickReply.EXTRA_TITLE).orEmpty()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pending = goAsync()
        CoreHost.init(context)
        // Showing the notification again right away stops the spinner on the Reply button.
        nm.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_mark).setContentTitle(title).setContentText("Answering...").setOnlyAlertOnce(true).build())
        CoreHost.send(session, text) { _ ->
            CoreHost.loadHistory(session) { history ->
                val answer = (history.lastOrNull { it is com.pocketnode.core.Msg.Assistant } as? com.pocketnode.core.Msg.Assistant)?.text.orEmpty().replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1").replace(Regex("[ \\t]+"), " ").trim()
                val shown = if (answer.isBlank()) "No answer came back. Open the chat to try again." else answer
                val open = PendingIntent.getActivity(context, id + 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SESSION, session).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val b = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_mark).setContentTitle(title)
                    .setContentText(shown.take(140)).setStyle(NotificationCompat.BigTextStyle().bigText(shown.take(600))).setAutoCancel(true).setContentIntent(open).setOnlyAlertOnce(true)
                nm.notify(id, QuickReply.addTo(context, b, session, id, channel, title).build())
                pending.finish()
            }
        }
    }
}
