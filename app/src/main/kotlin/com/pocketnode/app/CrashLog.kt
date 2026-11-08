package com.pocketnode.app

import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketnode.core.CrashReport
import java.io.File

/**
 * Keeps the last few crash reports on this phone. A report holds the kind of error and where in the code it happened, never a
 * message (see [CrashReport]). Nothing is sent anywhere: the owner reads a report and chooses whether to share it.
 */
object CrashLog {
    private const val KEEP = 5
    private fun dir(context: Context) = File(context.filesDir, "crash").apply { mkdirs() }

    /** Writes a report for any error that stops the app, then lets Android's usual handling carry on. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val now = System.currentTimeMillis()
                val text = CrashReport.format(error, thread.name, BuildConfig.VERSION_NAME, "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", "${Build.MANUFACTURER} ${Build.MODEL}", now)
                File(dir(app), "crash-$now.txt").writeText(text)
                dir(app).listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun files(context: Context): List<File> = dir(context).listFiles()?.filter { it.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()

    fun count(context: Context): Int = files(context).size

    fun latestText(context: Context): String? = files(context).firstOrNull()?.readText()

    fun deleteAll(context: Context) { files(context).forEach { it.delete() } }

    /** Opens the share sheet with the newest report as text. The owner chooses where, if anywhere, it goes. */
    fun share(context: Context) {
        val text = latestText(context) ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Pocketnode crash report").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
