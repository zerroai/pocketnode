package com.pocketnode.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.pocketnode.core.AppToolDecl
import com.pocketnode.core.AppToolParse
import com.pocketnode.core.AppTools
import com.pocketnode.core.Permission
import com.pocketnode.core.RegisteredTool
import com.pocketnode.core.Tool
import com.pocketnode.core.ToolSpec
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** An installed app that says it offers tools (docs/design/app-protocol.md). Nothing here is used until the owner turns the app on. */
class FoundApp(val packageName: String, val label: String, val service: ComponentName, val certSha256: String, val parse: AppToolParse)

/**
 * Finds apps that offer tools to the agent, remembers which ones the owner turned on (with the signing certificate seen at that
 * moment), and offers the tools of the turned-on ones. An app whose certificate has changed since is not used until turned on again.
 */
/** Reads at most [limit] bytes. (InputStream.readNBytes needs a newer Android than this app supports.) */
private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < limit) { val n = read(buf, 0, minOf(buf.size, limit - out.size())); if (n < 0) break; out.write(buf, 0, n) }
    return out.toByteArray()
}

object AppToolApps {
    const val ACTION = "com.pocketnode.action.TOOLS"
    const val META = "com.pocketnode.tools"
    private const val MAX_RESULT = 32_000
    private const val CALL_TIMEOUT_SECONDS = 20L
    private const val PREFS = "app_tools"

    @Volatile private var cache: Pair<Long, List<FoundApp>>? = null

    fun refresh() { cache = null }

    @Volatile private var known: Context? = null
    fun remember(context: Context) { known = context.applicationContext }

    /** For a tool name the model used (app.<package_with_underscores>.<tool>): the app's name and the tool in plain words, if the app is known. */
    fun describe(modelName: String): Pair<String, String>? {
        if (!modelName.startsWith("app.")) return null
        val apps = cache?.second ?: known?.let { runCatching { discover(it) }.getOrNull() } ?: return null
        val app = apps.firstOrNull { modelName.startsWith("app.${it.packageName.replace('.', '_')}.") } ?: return null
        val tool = modelName.removePrefix("app.${app.packageName.replace('.', '_')}.").replace('.', ' ').replace('_', ' ')
        return app.label to tool.replaceFirstChar { it.uppercase() }
    }

    fun discover(context: Context): List<FoundApp> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < 30_000) return list }
        val pm = context.packageManager
        val list = pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA).mapNotNull { r ->
            val s = r.serviceInfo ?: return@mapNotNull null
            if (!s.exported) return@mapNotNull null
            val res = s.metaData?.getInt(META, 0) ?: 0
            val parse = if (res == 0) AppToolParse.Bad(listOf("The app does not point to a tool file."))
            else runCatching {
                pm.getResourcesForApplication(s.packageName).openRawResource(res).use { it.readBytes(AppTools.MAX_FILE_BYTES + 1) }.let(AppTools::parse)
            }.getOrElse { AppToolParse.Bad(listOf("The tool file could not be read.")) }
            FoundApp(s.packageName, runCatching { pm.getApplicationLabel(s.applicationInfo).toString() }.getOrDefault(s.packageName), ComponentName(s.packageName, s.name), certOf(pm, s.packageName), parse)
        }.sortedBy { it.label.lowercase() }
        cache = System.currentTimeMillis() to list
        return list
    }

    @Suppress("DEPRECATION")
    private fun certOf(pm: PackageManager, pkg: String): String = runCatching {
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        MessageDigest.getInstance("SHA-256").digest(sigs?.firstOrNull()?.toByteArray() ?: ByteArray(0)).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /** The app's signing certificate right now, read fresh (not from the 30 second list). Empty if the app is gone. */
    fun liveCert(context: Context, pkg: String): String = certOf(context.packageManager, pkg)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The certificate the owner saw when turning the app on, or null if it is off. */
    fun enabledCert(context: Context, pkg: String): String? = prefs(context).getString(pkg, null)

    fun isOn(context: Context, app: FoundApp) = enabledCert(context, app.packageName) == app.certSha256 && app.certSha256.isNotEmpty()

    /** True when the app was turned on before but its signature is not the same now. */
    fun changed(context: Context, app: FoundApp): Boolean = enabledCert(context, app.packageName).let { it != null && it != app.certSha256 }

    fun setOn(context: Context, app: FoundApp, on: Boolean, grant: (String, Permission) -> Unit, revoke: (String, Permission) -> Unit) {
        val p = Permission("app", app.packageName)
        val id = "app.${app.packageName}"
        if (on && app.parse is AppToolParse.Ok && app.certSha256.isNotEmpty()) { prefs(context).edit().putString(app.packageName, app.certSha256).apply(); grant(id, p) }
        else { prefs(context).edit().remove(app.packageName).apply(); revoke(id, p) }
    }

    /** The tools of every app that is on, as the agent sees them. [ask] is used before any tool that spends, sends or cannot be undone. */
    fun tools(context: Context, ask: (com.pocketnode.app.Question) -> String?): List<RegisteredTool> =
        discover(context).filter { isOn(context, it) && it.parse is AppToolParse.Ok }.flatMap { app ->
            (app.parse as AppToolParse.Ok).manifest.tools.map { d ->
                val need = Permission("app", app.packageName)
                RegisteredTool(AppTool(context.applicationContext, app, d, need, ask), "app.${app.packageName}", setOf(need))
            }
        }

    /** Calls one tool in another app and waits for its answer. Throws with a plain message on any failure. */
    internal fun call(context: Context, app: FoundApp, tool: String, argsJson: String): String {
        val thread = HandlerThread("app-tool-call").also { it.start() }
        val reply = arrayOfNulls<Bundle>(1)
        val done = CountDownLatch(1)
        val connected = CountDownLatch(1)
        var service: Messenger? = null
        val replies = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) { if (msg.what == 2) { reply[0] = msg.data; done.countDown() } }
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = Messenger(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { done.countDown() }
        }
        try {
            if (!context.bindService(Intent(ACTION).setComponent(app.service), conn, Context.BIND_AUTO_CREATE)) throw IllegalStateException("${app.label} could not be reached.")
            if (!connected.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IllegalStateException("${app.label} did not answer in time.")
            service!!.send(Message.obtain(null, 1).apply { replyTo = replies; data = Bundle().apply { putString("tool", tool); putString("args", argsJson) } })
            if (!done.await(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS) || reply[0] == null) throw IllegalStateException("${app.label} did not answer in time.")
            val b = reply[0]!!
            val text = b.getString("result").orEmpty().take(MAX_RESULT)
            if (!b.getBoolean("ok")) throw IllegalStateException(text.ifBlank { "${app.label} could not do that." }.take(200))
            return text
        } finally {
            runCatching { context.unbindService(conn) }
            thread.quitSafely()
        }
    }
}

private class AppTool(private val context: Context, private val app: FoundApp, private val decl: AppToolDecl, private val need: Permission, private val ask: (Question) -> String?) : Tool {
    override val spec = ToolSpec(AppTools.modelName(app.packageName, decl.name), "[${app.label}] ${decl.description}".take(500), decl.schema.toString(), decl.effect == "read")
    override val needs = setOf(need)

    override fun call(args: String): String {
        // The signing certificate must still be the one the owner saw when turning the app on.
        val pinned = AppToolApps.enabledCert(context, app.packageName)
        val live = AppToolApps.liveCert(context, app.packageName)
        if (pinned == null || live.isEmpty() || live != pinned) throw IllegalStateException("${app.label} has changed since you turned it on. Turn it on again in Settings, Connected apps.")
        if (decl.effect == "external" || decl.confirm) ask(Question("Let ${app.label} do this?", "The assistant wants ${app.label} to: ${decl.description.take(150)}\n\nDetails: ${args.take(200)}"))?.let { throw IllegalStateException(it) }
        return AppToolApps.call(context, app, decl.name, args)
    }
}
