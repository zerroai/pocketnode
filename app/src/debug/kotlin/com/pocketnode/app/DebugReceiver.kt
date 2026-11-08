package com.pocketnode.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Debug builds only. Lets a developer start a chat from a computer without tapping through the screens:
 *   adb shell am broadcast -n com.pocketnode.app/.DebugReceiver -a com.pocketnode.app.DEBUG_SEND --es text "hello"
 * Install or update a pack: --es install com.pocketnode.reminders
 * Optional extras: --es photo NAME (a picture in the app's cache/debug folder), --es filename NAME --es filetext "words" (a text file).
 * It is not in release builds (this file lives in the debug source set).
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("tour", false)) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.pocketnode.app.ui.Tour.start() }
            return
        }
        if (intent.getBooleanExtra("setup", false)) {
            // Shows the first-run screens again without erasing anything: the owner's keys and chats are untouched.
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.pocketnode.app.AppModel.stack.clear(); com.pocketnode.app.AppModel.stack.add(com.pocketnode.app.Route.Welcome) }
            return
        }
        if (intent.getBooleanExtra("exportdb", false)) {
            // A plain copy of the locked database in the app's cache, for a developer to pull with adb. Contains real chats.
            Thread { val ok = AppDb.get(context).exportPlainTo(java.io.File(context.cacheDir, "debug-export.db")); android.util.Log.i("Pocketnode", "debug export: $ok") }.start()
            return
        }
        if (intent.getBooleanExtra("crashtest", false)) {
            // Stops the app on purpose, with a fake secret in the message, to check the crash report leaves it out.
            Thread { throw IllegalStateException("crash test with secret PIN 4921 inside") }.also { it.name = "crash-test" }.start()
            return
        }
        intent.getStringExtra("agentdemo")?.let { which ->
            if (which == "runapp") { CoreHost.whenReady { CoreHost.runAgent("demo-app") { r -> android.util.Log.i("Pocketnode", "agentdemo runapp ${r?.outcome} ${r?.lines}") } }; return }
            if (which == "flow") {
                // A flow with a branch that needs no model: reads the phone status, checks for a word that is not there, and takes the No way.
                CoreHost.whenReady {
                    val a = com.pocketnode.core.AgentDef("demo-flow", "Flow demo", com.pocketnode.core.AgentTrigger(), listOf(
                        com.pocketnode.core.AgentStep("tool", tool = "phone.status", save = "st", id = "a", next = "b", x = 100, y = 140),
                        com.pocketnode.core.AgentStep("check", from = "st", op = "contains", text = "zzzz", id = "b", next = "c", no = "d", x = 100, y = 260),
                        com.pocketnode.core.AgentStep("notify", tool = "Flow", text = "Yes way", id = "c", x = 10, y = 400),
                        com.pocketnode.core.AgentStep("notify", tool = "Flow", text = "No way: {st}", id = "d", x = 190, y = 400),
                    ))
                    val problems = com.pocketnode.core.Agents.validate(a, CoreHost.agentToolNames())
                    android.util.Log.i("Pocketnode", "agentdemo flow problems=$problems")
                    if (problems.isEmpty()) { CoreHost.agents.save(a); CoreHost.runAgent(a.id) { r -> android.util.Log.i("Pocketnode", "agentdemo flow ${r?.outcome} ${r?.lines}") } }
                }
                return
            }
            if (which == "delete") { CoreHost.whenReady { CoreHost.agents.remove("demo-flow"); CoreHost.agents.remove("demo-status"); CoreHost.agents.remove("demo-app"); AgentScheduler.schedule(context); android.util.Log.i("Pocketnode", "agentdemo deleted") }; return }
            // Makes (and with "run" also starts) a test agent that only reads the phone's status and notifies. Needs no model credit.
            CoreHost.whenReady {
                val a = com.pocketnode.core.AgentDef("demo-status", "Battery watch", com.pocketnode.core.AgentTrigger("every", everyMinutes = 15), listOf(
                    com.pocketnode.core.AgentStep("tool", tool = "phone.status", save = "st"),
                    com.pocketnode.core.AgentStep("notify", tool = "Phone status", text = "{st}"),
                ))
                val problems = com.pocketnode.core.Agents.validate(a, CoreHost.agentToolNames())
                android.util.Log.i("Pocketnode", "agentdemo problems=$problems")
                if (problems.isEmpty()) {
                    CoreHost.agents.save(a)
                    AgentScheduler.schedule(context)
                    if (which == "run") CoreHost.runAgent(a.id) { r -> android.util.Log.i("Pocketnode", "agentdemo ${r?.outcome} ${r?.lines}") }
                }
            }
            return
        }
        intent.getStringExtra("appdemo")?.let { pkg ->
            // Turns on a tool app (for example the sample Cafe app) as if the owner had, then runs an agent that calls its read-only tool.
            CoreHost.whenReady {
                AppToolApps.refresh()
                val found = AppToolApps.discover(context)
                android.util.Log.i("Pocketnode", "appdemo found=${found.map { it.packageName + ":" + ((it.parse as? com.pocketnode.core.AppToolParse.Bad)?.problems ?: "ok") }}")
                val app = found.firstOrNull { it.packageName == pkg } ?: return@whenReady
                AppToolApps.setOn(context, app, true, { id, p -> CoreHost.grantApp(id, p) }, { id, p -> CoreHost.revokeApp(id, p) })
                val tool = com.pocketnode.core.AppTools.modelName(pkg, "order.status")
                val a = com.pocketnode.core.AgentDef("demo-app", "App demo", com.pocketnode.core.AgentTrigger(), listOf(
                    com.pocketnode.core.AgentStep("tool", tool = tool, save = "r"),
                    com.pocketnode.core.AgentStep("notify", tool = "Cafe", text = "{r}"),
                ))
                val problems = com.pocketnode.core.Agents.validate(a, CoreHost.agentToolNames())
                android.util.Log.i("Pocketnode", "appdemo problems=$problems")
                if (problems.isEmpty()) { CoreHost.agents.save(a); CoreHost.runAgent(a.id) { r -> android.util.Log.i("Pocketnode", "appdemo ${r?.outcome} ${r?.lines}") } }
            }
            return
        }
        intent.getStringExtra("bankdemo")?.let { what ->
            // "on": read alerts from the adb shell (com.android.shell) for a test; "list": log what was kept; "off": undo and erase.
            CoreHost.whenReady {
                when (what) {
                    "on" -> { BankAlertSettings.setEnabled(context, true); BankAlertSettings.addSource(context, "com.android.shell") }
                    "list" -> android.util.Log.i("Pocketnode", "bankdemo kept=${CoreHost.bankAlerts.recent().map { it.alert.direction + " " + it.alert.amountMinor + " " + it.alert.currency + " bal=" + it.alert.balanceMinor + " acct=" + it.alert.accountEnd + " via " + it.source }} tool=" + com.pocketnode.core.BankAlertsTool({ CoreHost.bankAlerts }, { BankAlertSettings.enabled(context) }).call("{}").take(300))
                    else -> { BankAlertSettings.setEnabled(context, false); BankAlertSettings.removeSource(context, "com.android.shell"); CoreHost.bankAlerts.clear(); android.util.Log.i("Pocketnode", "bankdemo undone") }
                }
            }
            return
        }
        if (intent.getBooleanExtra("qrtest", false)) { MorningBriefing.notify(context, "local:qrtest", "Test briefing. Reply to this notification to check the reply box."); return }
        intent.getStringExtra("usemodel")?.let { which ->
            // Switches the connected model for a test: "ollama" (--es ollamaurl, --es ollamamodel) or "openai" (--es openaiurl, --es openaikey, --es openaimodel; anything left out keeps the saved value).
            val done = { r: com.pocketnode.core.ProbeResult -> android.util.Log.i("Pocketnode", "usemodel $which: $r"); Unit }
            if (which == "ollama") CoreHost.connectOllama(intent.getStringExtra("ollamaurl").orEmpty(), intent.getStringExtra("ollamamodel").orEmpty(), done)
            else CoreHost.connectOpenAi(
                intent.getStringExtra("openaiurl") ?: CoreHost.config.openAiUrl,
                intent.getStringExtra("openaikey").orEmpty(),
                intent.getStringExtra("openaimodel") ?: CoreHost.config.openAiModel,
                done,
            )
            return
        }
        if (intent.hasExtra("datasaver")) { CoreHost.chooseDataSaver(intent.getBooleanExtra("datasaver", false)); return }
        if (intent.getBooleanExtra("testsetup", false)) { android.util.Log.i("Pocketnode", "testsetup start"); CoreHost.testSetup { ok, msg -> android.util.Log.i("Pocketnode", "testsetup ok=$ok: $msg") }; return }
        intent.getStringExtra("forgetdata")?.let { id ->
            // Deletes one pack's saved data (its "data" key) after a test, so test entries do not stay on the owner's phone.
            CoreHost.debugForgetPackData(id); return
        }
        intent.getStringExtra("limit")?.toLongOrNull()?.let { tokens -> CoreHost.chooseUsageLimit(tokens); return }
        intent.getStringExtra("mail")?.let { spec ->
            // address|name|password|smtpHost|smtpPort|imapHost|imapPort : saves an email account after checking it.
            val f = spec.split('|')
            val sp = f[4].toInt(); val ip = f[6].toInt()
            fun sec(host: String, port: Int) = if (host == "127.0.0.1") com.pocketnode.core.MailSecurity.NONE else if (port == 587 || port == 143) com.pocketnode.core.MailSecurity.STARTTLS else com.pocketnode.core.MailSecurity.SSL
            CoreHost.saveMailAccount(com.pocketnode.core.MailAccount(f[0], f[1], f[3], sp, sec(f[3], sp), f[5], ip, sec(f[5], ip), f[0], f[2])) { err, warn -> android.util.Log.i("Pocketnode", "debug mail account: ${err ?: "saved"} ${warn.orEmpty()}") }
            return
        }
        if (intent.getBooleanExtra("removemail", false)) { CoreHost.removeMailAccount(); return }
        intent.getStringExtra("grant")?.let { packId ->
            // Switches on every private phone ability the pack asks for. For tests on a developer's own phone.
            CoreHost.debugGrantAll(packId); return
        }
        intent.getStringExtra("revoke")?.let { packId -> CoreHost.debugRevokeAll(packId); return }
        intent.getStringExtra("callsay")?.let { words -> Handler(Looper.getMainLooper()).post { VoiceCall.debugSay(words) }; return }
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        val photo = intent.getStringExtra("photo")   // a file name inside the app's cache/debug folder
        val fileName = intent.getStringExtra("filename")
        val fileText = intent.getStringExtra("filetext")
        if (text.isEmpty() && photo == null && fileText == null) return
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (intent.getBooleanExtra("new", true)) AppModel.newChat()
            var waiting = (if (photo != null) 1 else 0) + (if (fileText != null) 1 else 0)
            fun done() { if (--waiting <= 0) AppModel.send(text) }
            if (waiting == 0) AppModel.send(text)
            if (photo != null) {
                val bytes = java.io.File(context.cacheDir, "debug/$photo").takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
                CoreHost.attachBytes(photo, bytes, true) { items, _ -> AppModel.addAttachments(items); done() }
            }
            if (fileText != null) CoreHost.attachBytes(fileName ?: "note.txt", fileText.toByteArray(), false) { items, _ -> AppModel.addAttachments(items); done() }
        }
    }
}
