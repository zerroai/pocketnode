package com.pocketnode.app

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketnode.core.AgentRuntime
import com.pocketnode.core.AnthropicModel
import com.pocketnode.core.Budget
import com.pocketnode.core.ChannelRunner
import com.pocketnode.core.ChannelSupervisor
import com.pocketnode.core.SupervisorState
import com.pocketnode.core.HttpHostNet
import com.pocketnode.core.BundledPacks
import com.pocketnode.core.InstalledPack
import com.pocketnode.core.ModelClient
import com.pocketnode.core.ModelProbe
import com.pocketnode.core.Hex
import com.pocketnode.core.Msg
import com.pocketnode.core.RelayInbox
import com.pocketnode.core.readUpTo
import com.pocketnode.core.OllamaModel
import com.pocketnode.core.OpenAiModel
import com.pocketnode.core.PackRuntime
import com.pocketnode.core.PackSandbox
import com.pocketnode.core.ImapClient
import com.pocketnode.core.MailAccount
import com.pocketnode.core.MailException
import com.pocketnode.core.MailSecurity
import com.pocketnode.core.SmtpClient
import com.pocketnode.core.Permission
import com.pocketnode.core.PermissionBroker
import com.pocketnode.core.ProbeResult
import com.pocketnode.core.RegisteredTool
import com.pocketnode.core.RelayClient
import com.pocketnode.core.RelayCredentials
import com.pocketnode.core.RelayException
import com.pocketnode.core.RelayInboxRunner
import com.pocketnode.core.RelayLink
import com.pocketnode.core.RelaySetup
import com.pocketnode.core.RunResult
import com.pocketnode.core.RunStore
import com.pocketnode.core.SessionSummary
import com.pocketnode.core.SqlPackKv
import com.pocketnode.core.SqlRunStore
import com.pocketnode.core.Tool
import com.pocketnode.core.ToolNames
import com.pocketnode.core.ToolSpec
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class Provider { None, Anthropic, Ollama, OpenAi }

sealed interface TelegramState {
    data object Off : TelegramState
    data object Connecting : TelegramState
    data object Waiting : TelegramState
    data class Connected(val username: String) : TelegramState
    data class Problem(val message: String) : TelegramState
}

sealed interface WhatsAppState {
    data object Off : WhatsAppState
    data object Connecting : WhatsAppState
    data object Waiting : WhatsAppState
    data class Connected(val number: String) : WhatsAppState
    data class Problem(val message: String) : WhatsAppState
}

/** What the owner pastes into Meta's console so WhatsApp events reach the relay. */
data class WhatsAppSetup(val callbackUrl: String, val verifyToken: String)


enum class AnthropicTier(val id: String, val label: String, val note: String) {
    Fast("claude-haiku-5-5", "Haiku", "Fastest and lowest cost"),
    Balanced("claude-sonnet-5-5", "Sonnet", "Balanced"),
    Capable("claude-opus-5-5", "Opus", "Most capable"),
}

private class ClockTool : Tool {
    override val spec = ToolSpec("clock.now", "Gets the phone's current local time.", "{}", idempotent = true)
    override val needs = emptySet<Permission>()
    override fun call(args: String): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}

/** Process-wide owner of the agent runtime, model configuration and packs. The UI talks only to this object. */
object CoreHost {
    const val TELEGRAM_PACK = "com.pocketnode.telegram"
    const val WHATSAPP_PACK = "com.pocketnode.whatsapp"
    // A task that throws must not take the whole app down. The pool starts a fresh thread for the next task.
    private fun workerThread(name: String) = { r: Runnable ->
        Thread(r, name).apply { isDaemon = true; setUncaughtExceptionHandler { _, e -> android.util.Log.e("Pocketnode", "$name task failed: ${e.message}", e) } }
    }
    private val io = Executors.newSingleThreadExecutor(workerThread("core-io"))
    private val agent = Executors.newSingleThreadExecutor(workerThread("core-agent"))
    // Reading the chat list must not wait behind the rest of start-up, which shares the io thread.
    private val reads = Executors.newSingleThreadExecutor(workerThread("core-read"))
    private val storeReady = java.util.concurrent.CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())

    var telegram by mutableStateOf<TelegramState>(TelegramState.Off)
        private set

    var whatsapp by mutableStateOf<WhatsAppState>(WhatsAppState.Off)
        private set

    var whatsappSetup by mutableStateOf<WhatsAppSetup?>(null)
        private set

    /** Installed packs, as verified at the last refresh. */
    var packs by mutableStateOf<List<InstalledPack>>(emptyList())
        private set


    /** Pack ids with an install or removal in progress. */
    var packBusy by mutableStateOf<Set<String>>(emptySet())
        private set

    val relayAvailable: Boolean get() = BuildConfig.RELAY_URL.isNotBlank()

    /**
     * What the screens need to know about settings, held in memory. Reading the Keystore-backed
     * store can take hundreds of milliseconds on a budget phone, so it is never done while drawing.
     */
    data class Config(
        val provider: Provider = Provider.None,
        val tier: AnthropicTier = AnthropicTier.Fast,
        val serverUrl: String = "",
        val serverModel: String = "",
        val hasAnthropicKey: Boolean = false,
        val openAiUrl: String = "",
        val openAiModel: String = "",
        val hasOpenAiKey: Boolean = false,
        val hasTelegramToken: Boolean = false,
        /** Telegram messages arrive through the relay instead of being fetched directly. */
        val relayOn: Boolean = false,
        val ready: Boolean = false,
    )

    var config by mutableStateOf(Config())
        private set

    private lateinit var appContext: Context
    fun appContextOrNull(): Context? = if (::appContext.isInitialized) appContext else null
    private lateinit var secrets: KeystoreSecretStore
    private lateinit var store: RunStore
    private lateinit var packKv: SqlPackKv
    private lateinit var db: AndroidSqlDb
    private lateinit var packManager: BundledPacks
    private lateinit var packRuntime: PackRuntime
    private var runtime: AgentRuntime? = null
    private var telegramThread: Thread? = null
    @Volatile private var supervisor: ChannelSupervisor? = null
    private var networkWatcherStarted = false
    private val relayClient by lazy { RelayClient(BuildConfig.RELAY_URL, HttpHostNet()) }
    private val builtin = listOf<Tool>(ClockTool(), com.pocketnode.core.BankAlertsTool({ if (::bankAlerts.isInitialized) bankAlerts else null }, { BankAlertSettings.enabled(appContext) })) +
        com.pocketnode.core.StatusTools.all(object : com.pocketnode.core.StatusHost {
            override fun activity(limit: Int, about: String, problemsOnly: Boolean) = run { started.await(); activity.recent(limit, null, about, problemsOnly) }
            override fun status() = listOf(
                "Assistant" to if (paused) "switched off" else "on",
                "Model" to modelSummary(),
                "Used today" to usageSummary().ifBlank { "unknown" },
                "Plan" to AppModel.planSummary(),
                "Agents" to agentList.size.toString(),
            )
            override fun devices() = emptyList<String>()
        }) +
        com.pocketnode.core.SettingsTools.all(object : com.pocketnode.core.SettingsHost {
            override fun appearance() = appContext.getSharedPreferences("app", Context.MODE_PRIVATE).getString("theme", "System").orEmpty().lowercase()
            override fun setAppearance(mode: String) { main.post { AppModel.chooseTheme(com.pocketnode.app.ui.ThemeMode.values().first { it.name.equals(mode, ignoreCase = true) }) } }
            override fun usageLimit() = usageLimit
            override fun setUsageLimit(tokens: Long) { main.post { chooseUsageLimit(tokens) } }
            override fun briefing() = MorningBriefing.settings(appContext)
            override fun setBriefing(s: com.pocketnode.core.BriefingSettings) { MorningBriefing.save(appContext, s) }
            override fun ask(title: String, message: String) = askOwner(Question(title, message, "Yes"))
        })
    private val started = java.util.concurrent.CountDownLatch(1)
    private var initCalled = false

    /** Cheap and safe to call from any thread. The real work happens on a background thread. */
    @Synchronized
    /** Runs [block] on the agent thread once start-up has finished. */
    fun whenReady(block: () -> Unit) { agent.execute { started.await(); block() } }

    fun init(context: Context) {
        if (initCalled) return
        initCalled = true
        appContext = context.applicationContext
        AppToolApps.remember(appContext)
        CrashLog.install(appContext)
        (appContext as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { visibleActivities++ }
            override fun onActivityStopped(activity: android.app.Activity) { visibleActivities-- }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        io.execute { startUp() }
    }

    /** How many of this app's screens are showing. Some phone actions only work while it is above zero. */
    @Volatile private var visibleActivities = 0
    private lateinit var broker: PermissionBroker
    private lateinit var camera: CameraCapturer
    private lateinit var intake: FileIntake
    private lateinit var photoFiles: PhotoFiles

    /** What the assistant has done, kept on this phone only. Shown on the Activity screen. */
    lateinit var activity: com.pocketnode.core.ActivityLog
        private set

    /** True while the owner has switched the assistant off. Nothing new runs, from the app or from Telegram and WhatsApp. */
    var paused by mutableStateOf(false)
        private set
    private val seenRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val stoppedRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val runGuard = com.pocketnode.core.RunGuard { id ->
        seenRuns.add(id)
        when {
            paused -> "The assistant is switched off. Switch it on in Settings to use it again."
            id in stoppedRuns -> "Stopped. Nothing more was done."
            else -> null
        }
    }

    fun switchAssistant(on: Boolean) {
        paused = on
        appContext.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("paused", on).apply()
        if (on) stopNow()
        io.execute { if (::activity.isInitialized) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, if (on) "Assistant switched off" else "Assistant switched on", "By the owner.") }
    }

    /** Runs one briefing request in a chat of its own. [done] gets the answer, or null if it could not be made (switched off, no model, an error). */
    fun runBriefing(sessionId: String, prompt: String, done: (String?) -> Unit) {
        agent.execute {
            try {
                started.await()
                if (paused || !hasModel()) { done(null); return@execute }
                store.setSessionTitle(sessionId, "Morning briefing")
                val result = checkNotNull(runtime).run(sessionId, prompt)
                done(if (result.outcome == com.pocketnode.core.RunOutcome.ANSWERED) result.text else null)
            } catch (e: Exception) { done(null) }
        }
    }

    fun clearActivity() = io.execute { activity.clear() }

    // ---- Agents ------------------------------------------------------------------------------

    lateinit var agents: com.pocketnode.core.AgentStore
        private set

    lateinit var bankAlerts: com.pocketnode.core.BankAlertStore
        private set

    var agentList by mutableStateOf<List<com.pocketnode.core.SavedAgent>>(emptyList())
        private set
    var agentsLoaded by mutableStateOf(false)
        private set

    fun loadAgents() = io.execute { started.await(); agentList = agents.list(); agentsLoaded = true }

    fun addAgent(a: com.pocketnode.core.AgentDef, problem: (String) -> Unit = {}, saved: () -> Unit = {}) = io.execute {
        started.await()
        val p = com.pocketnode.core.Agents.validate(a, agentToolNames())
        if (p.isNotEmpty()) { main.post { problem(p.first()) }; return@execute }
        try { agents.save(a) } catch (e: IllegalArgumentException) { main.post { problem(e.message.orEmpty()) }; return@execute }
        AgentScheduler.schedule(appContext)
        agentList = agents.list()
        main.post { saved() }
    }

    /** What an agent block may use: the tools the owner has allowed, with their inputs, for the agent builder. */
    fun agentToolSpecs(): List<ToolSpec> { val names = agentToolNames(); return localTools().map { it.tool.spec }.filter { it.name in names }.sortedBy { it.name } }

    fun switchAgent(id: String, on: Boolean) = io.execute { started.await(); agents.setEnabled(id, on); AgentScheduler.schedule(appContext); agentList = agents.list() }

    fun deleteAgent(id: String) = io.execute { started.await(); agents.remove(id); AgentScheduler.schedule(appContext); agentList = agents.list() }

    fun clearBankAlerts() = io.execute { started.await(); bankAlerts.clear() }

    fun grantApp(id: String, p: com.pocketnode.core.Permission) { broker.grant(id, p) }
    fun revokeApp(id: String, p: com.pocketnode.core.Permission) { broker.revoke(id, p) }

    /** The names of the tools an agent may use: everything installed. The owner's permissions still apply to each call. */
    fun agentToolNames(): Set<String> = localTools().map { it.tool.spec.name }.filter { !it.startsWith("agent.") && !it.startsWith("pack.") && !it.startsWith("settings.") }.toSet()

    /** Runs one agent to its end on the agent thread, records the result, plans the next start and tells [done]. */
    fun runAgent(id: String, done: (com.pocketnode.core.AgentRun?) -> Unit = {}) {
        agent.execute { done(runAgentNow(id)) }
    }

    private fun runAgentNow(id: String): com.pocketnode.core.AgentRun? {
        run {
            val result = try {
                started.await()
                val saved = agents.get(id)
                if (saved == null) null else if (paused || !hasModel()) {
                    com.pocketnode.core.AgentRun(com.pocketnode.core.AgentOutcome.STOPPED_BY_CHECK, listOf(if (paused) "The assistant is switched off." else "No model is connected."))
                } else {
                    val a = saved.def
                    val caller = com.pocketnode.core.AgentToolCaller { name, args ->
                        val reg = localTools().firstOrNull { it.tool.spec.name == name }
                        if (reg == null) "Error: that tool is not available" else {
                            val d = broker.authorize(reg.packId, reg.declared, reg.tool.needs)
                            if (d is com.pocketnode.core.Decision.Denied) { activity.add(com.pocketnode.core.ActivityKind.TOOL, name, "Agent \u201c${a.name}\u201d: refused: ${d.reason}".take(380), problem = true); "Denied: ${d.reason}" }
                            else try { reg.tool.call(args).also { activity.add(com.pocketnode.core.ActivityKind.TOOL, name, "Agent \u201c${a.name}\u201d: ${args.replace(Regex("\\s+"), " ").take(120)}") } } catch (e: Exception) { "Error: ${e.message}" }
                        }
                    }
                    val asker = com.pocketnode.core.AgentAsker { prompt -> checkNotNull(setupTestModel).complete(listOf(Msg.User(prompt)), emptyList()).text }
                    val notifier = com.pocketnode.core.AgentNotifier { title, text -> AgentScheduler.notify(appContext, a.name, title, text) }
                    com.pocketnode.core.AgentRunner(caller, asker, notifier).run(a)
                }
            } catch (e: Exception) { com.pocketnode.core.AgentRun(com.pocketnode.core.AgentOutcome.FAILED, listOf("Could not run: ${e.message}")) }
            if (result != null) {
                agents.finished(id, result)
                val a = agents.get(id)
                activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Agent \u201c${a?.def?.name ?: id}\u201d ran", result.lines.joinToString("; ").take(300), problem = result.outcome == com.pocketnode.core.AgentOutcome.FAILED)
            }
            AgentScheduler.schedule(appContext)
            return result
        }
    }

    /** Runs every agent whose time has come, one after another, then calls [done]. */
    fun runDueAgents(done: () -> Unit) {
        agent.execute {
            try {
                started.await()
                agents.due().forEach { s -> runAgentNow(s.def.id) }
            } finally { done() }
        }
    }

    /** Stops every run in progress at its next step. A request already sent to the model, or a tool already working, finishes first. */
    fun stopNow() {
        stoppedRuns.addAll(seenRuns)
        seenRuns.clear()
        io.execute { if (::activity.isInitialized) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Stopped", "The owner pressed stop.") }
    }
    /** PDFs the assistant made. Kept in the app's own storage (not the cache) so they are still there to open later. */
    lateinit var assetFiles: AssetFiles
        private set
    private lateinit var attachedFiles: AttachmentFiles

    private fun startUp() {
        val t0 = android.os.SystemClock.elapsedRealtime()
        db = AppDb.get(appContext)
        store = SqlRunStore(db)
        storeReady.countDown()
        android.util.Log.d("Pocketnode", "startup: database ready after ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        secrets = KeystoreSecretStore(appContext)
        packKv = SqlPackKv(db)
        activity = com.pocketnode.core.ActivityLog(db)
        usage = com.pocketnode.core.UsageMeter(SqlPackKv(db))
        agents = com.pocketnode.core.AgentStore(db)
        bankAlerts = com.pocketnode.core.BankAlertStore(db)
        usageLimit = usage.limit
        autoDelete = com.pocketnode.core.AutoDelete(SqlPackKv(db))
        autoDeleteDays = autoDelete.days
        if (db.startedFresh) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Saved chats could not be opened", "The key that locks them was lost, so a new empty record was started. The old file is kept, still locked, on this phone.", problem = true)
        if (!db.locked) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Saved chats are not locked yet", "Locking them failed this time, so they are still stored as before. It will be tried again next time the app starts.", problem = true)
        paused = appContext.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("paused", false)
        VoiceCall.restoreAudio(appContext)
        dataSaver = appContext.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("data_saver", false)
        packManager = BundledPacks(listOf("phone", "reminders", "notes", "weather", "utilities", "math", "safety", "telegram")) { path -> runCatching { appContext.assets.open(path).use { it.readBytes() } }.getOrNull() }
        broker = PermissionBroker(persist = packKv)
        val vault = com.pocketnode.core.FileVault(FileKey(appContext)::get)
        val photos = PhotoFiles(File(appContext.cacheDir, "images"), vault).also { photoFiles = it; it.saver = dataSaver }
        assetFiles = AssetFiles(File(appContext.filesDir, "documents"), vault, File(appContext.cacheDir, "exports")).also { it.tidy(); it.adoptCached(File(appContext.cacheDir, "documents")) }
        attachedFiles = AttachmentFiles(File(appContext.cacheDir, "files"), vault).also { it.tidy() }
        // Files kept by earlier versions as plain files are locked once, then the plain ones are erased. Plain copies made for other apps, or left by a crash, go too.
        val locked = assetFiles.lockOld() + attachedFiles.lockOld() + photos.lockOld()
        if (locked > 0) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Files locked", "$locked files kept by an earlier version were locked, and the plain copies erased.")
        assetFiles.clearCopies(0)
        File(appContext.cacheDir, "capture").listFiles()?.forEach { eraseFile(it) }
        com.pocketnode.core.Images.source = photos::source
        com.pocketnode.core.Attachments.fileSource = attachedFiles::source
        camera = CameraCapturer(appContext, photos)
        intake = FileIntake(appContext, photos, attachedFiles)
        packRuntime = PackRuntime(packManager, broker, HttpHostNet(), packKv, { pack, host, method, status, error ->
            val name = packs.firstOrNull { it.manifest.id == pack }?.manifest?.name ?: pack.substringAfterLast('.')
            activity.add(com.pocketnode.core.ActivityKind.NETWORK, host, "$method by the $name pack: ${status?.let { "answered $it" } ?: error}", problem = status == null || status >= 400)
        }, ::packSecret).also { it.device = AndroidDeviceHost(appContext, { visibleActivities > 0 }, photos, camera, ::askOwner, Reminders.scheduler(appContext), ::mailAccountOrNull, assetFiles) }
        packs = packManager.list()

        com.pocketnode.core.ModelCancel.timing = { if (VoiceCall.active) android.util.Log.i("Pocketnode", it) }
        val anthropic = AnthropicModel(apiKey = { secrets.get(K_ANTHROPIC_KEY) }, model = { anthropicTier().id }, system = ::systemPrompt)
        val ollama = OllamaModel(baseUrl = { serverUrl() }, model = { serverModel().ifBlank { DEFAULT_OLLAMA_MODEL } }, system = ::systemPrompt)
        val openAi = OpenAiModel(baseUrl = { secrets.get(K_OPENAI_URL) }, apiKey = { secrets.get(K_OPENAI_KEY) }, model = { secrets.get(K_OPENAI_MODEL).orEmpty() }, system = ::systemPrompt)
        localModelClient = { when (provider()) { Provider.Anthropic -> anthropic; Provider.Ollama -> ollama; Provider.OpenAi -> openAi; Provider.None -> null } }
        val model = object : ModelClient {
            /** Picks the connected model, trims what is offered this turn, notes the request in Activity, then makes the call. */
            private fun route(msgs: List<Msg>, allSpecs: List<com.pocketnode.core.ToolSpec>, call: (ModelClient, List<com.pocketnode.core.ToolSpec>) -> com.pocketnode.core.ModelReply): com.pocketnode.core.ModelReply {
                if (VoiceCall.active) android.util.Log.i("Pocketnode", "route entered ${System.currentTimeMillis() - sendBegan} ms after the run began (${allSpecs.size} tools)")
                // If the owner attached a photo to their latest message, neither the camera nor the gallery is offered this turn: the model
                // should look at the attached photo, not open the camera or read other photos. (A prompt alone did not stop some models from doing so.)
                val lastUser = msgs.lastOrNull { it is Msg.User } as? Msg.User
                val specs = if (lastUser != null && com.pocketnode.core.Images.referencesIn(lastUser.text).isNotEmpty()) allSpecs.filterNot { it.name == "camera.look" || it.name == "photos.latest" } else allSpecs
                val offered = specs
                val sentPhotos = com.pocketnode.core.Images.attachments(msgs).values.sumOf { it.size }
                val where = when (provider()) { Provider.Anthropic -> "api.anthropic.com"; Provider.OpenAi -> hostOf(secrets.get(K_OPENAI_URL)); Provider.Ollama -> hostOf(serverUrl()); Provider.None -> "no model" }
                activity.add(com.pocketnode.core.ActivityKind.MODEL, where, "${msgs.size} messages, ${offered.size} tools offered" + if (sentPhotos > 0) ", $sentPhotos photo${if (sentPhotos == 1) "" else "s"} sent" else "")
                // A day's allowance, counted in estimated tokens, keeps a runaway chat or a busy group from costing more than the owner chose.
                usage.blockedReason()?.let { throw com.pocketnode.core.UsageLimitException(it) }
                val inputChars = systemPrompt().length + offered.sumOf { it.name.length + it.description.length + it.schema.length } + msgs.sumOf { m ->
                    when (m) { is Msg.User -> m.text.length; is Msg.Assistant -> m.text.length + m.calls.sumOf { it.args.length }; is Msg.ToolResult -> m.text.length }
                }
                com.pocketnode.core.ModelUsage.take()
                try {
                    val client: ModelClient = when (provider()) {
                        Provider.Anthropic -> anthropic
                        Provider.Ollama -> ollama
                        Provider.OpenAi -> openAi
                        Provider.None -> throw IllegalStateException("No model is connected. Connect one in Settings.")
                    }
                    val began = System.currentTimeMillis()
                    val reply = call(client, offered)
                    android.util.Log.i("Pocketnode", "model call: started ${began - sendBegan} ms after the run began, ${offered.size} tools offered, ${System.currentTimeMillis() - began} ms, ${reply.calls.size} calls, ${reply.text.length} letters" + (if (BuildConfig.DEBUG) ": \"${reply.text.take(90)}\" tools=${offered.map { it.name }.take(30)}" else ""))
                    // The provider's own count is used when it gives one; otherwise our estimate of four characters to a token.
                    val real = com.pocketnode.core.ModelUsage.take()
                    if (real != null) usage.addReal(real) else usage.add(com.pocketnode.core.UsageMeter.estimate(inputChars) + com.pocketnode.core.UsageMeter.estimate(reply.text.length + reply.calls.sumOf { it.args.length }))
                    android.util.Log.i("Pocketnode", "model usage: " + (real?.let { "provider says ${it.input} in (${it.cached} cached), ${it.output} out" } ?: "provider gave no numbers") + "; our estimate ${com.pocketnode.core.UsageMeter.estimate(inputChars)} in, ${com.pocketnode.core.UsageMeter.estimate(reply.text.length + reply.calls.sumOf { it.args.length })} out; voice=${VoiceCall.active}")
                    return reply
                } catch (e: com.pocketnode.core.StreamStopped) {
                    throw e
                } catch (e: Exception) {
                    // The screen shows a plain-language version; keep the real cause for debugging.
                    android.util.Log.w("Pocketnode", "model call failed (${msgs.size} messages, ${offered.size} tools): ${e::class.java.simpleName}: ${e.message}")
                    throw e
                }
            }

            override fun complete(messages: List<Msg>, tools: List<com.pocketnode.core.ToolSpec>) = route(messages, tools) { c, s -> c.complete(messages, s) }

            override fun stream(messages: List<Msg>, tools: List<com.pocketnode.core.ToolSpec>, onText: (String) -> Unit) = route(messages, tools) { c, s -> c.stream(messages, s, onText) }
        }
        setupTestModel = model
                runtime = AgentRuntime(store, model, ::allTools, broker, tier(), runGuard, activity)
        reloadConfig(ready = true)
        // A private chat left behind by a run that was cut short (the app was closed from the recents list) goes now, before
        // `recover()` below can pick its unfinished request up again and spend the owner's money on a chat they already left.
        val prefs = appContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.getStringSet("private_chats", emptySet()).orEmpty().toList().also { left ->
            if (left.isNotEmpty()) { left.forEach { removeChatNow(it) }; prefs.edit().remove("private_chats").apply(); android.util.Log.d("Pocketnode", "startup: removed ${left.size} private chat(s) left behind") }
        }
        started.countDown()
        sweepOldChats()
        startNetworkWatcher()
        startTelegramIfConfigured()
        loadWhatsAppSetup()
        startWhatsAppIfConfigured()
        agent.execute { runtime?.recover() }
        if (secrets.get(K_TELEGRAM_MODE) == "relay" || waLink() != null) refreshPushRegistration()
    }

    /** Data saver: photos are made smaller before they are kept and sent. Photos already stored are not changed. */
    var dataSaver by mutableStateOf(false)
        private set

    fun chooseDataSaver(on: Boolean) {
        dataSaver = on
        appContext.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("data_saver", on).apply()
        if (::photoFiles.isInitialized) photoFiles.saver = on
    }

    @Volatile private var setupTestModel: ModelClient? = null

    /** On by default. Off only for a test that compares with the provider's own tool calling. */
    @Volatile var ownRouting = false
        private set

    /**
     * Sends one real, tiny message through the connected model, the same path a chat takes, and says in plain words how it went.
     * Nothing is saved to any chat. It counts toward the daily limit and appears in Activity like any model request.
     */
    fun testSetup(onResult: (ok: Boolean, message: String) -> Unit) { Thread {
        val m = setupTestModel ?: run { started.await(); setupTestModel }
        val result = runCatching {
            val reply = m!!.complete(listOf(Msg.User("Reply with the single word: ready")), emptyList())
            if (reply.text.isBlank()) false to "The model answered with nothing. Try again, or check the model in Settings." else true to "The model answered. Your setup works."
        }.getOrElse { false to com.pocketnode.core.ModelErrors.friendly(it as? Exception ?: Exception(it)) }
        main.post { onResult(result.first, result.second) }
    }.start() }

    @Volatile private var localModelClient: () -> ModelClient? = { null }

    private fun allTools(): List<RegisteredTool> =
        localTools().filter { AppFeatures.toolOn(it.tool.spec.name) }

    private fun localTools(): List<RegisteredTool> =
        (builtin.map { RegisteredTool(it, "builtin.${it.spec.name}", emptySet()) } + packRuntime.tools() + AppToolApps.tools(appContext, ::askOwner)).filter { AppFeatures.toolOn(it.tool.spec.name) }

    /** The assistant is told exactly what it can do so it does not claim abilities it lacks. */
    private fun systemPrompt(): String {
        val list = allTools().joinToString("; ") { "${ToolNames.wire(it.tool.spec.name)}: ${it.tool.spec.description}" }
        val now = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.US).format(java.util.Date())
        return com.pocketnode.core.AssistantPrompt.system(now, java.util.TimeZone.getDefault().id, VoiceCall.active, ownRouting, list)
    }

    // ---- Model configuration -------------------------------------------------------------

    // Secure-store reads. Background threads only.
    private fun provider(): Provider = runCatching { Provider.valueOf(secrets.get(K_PROVIDER) ?: "None") }.getOrDefault(Provider.None)
    private fun anthropicTier(): AnthropicTier = AnthropicTier.entries.firstOrNull { it.id == secrets.get(K_ANTHROPIC_MODEL) } ?: AnthropicTier.Fast
    private fun serverUrl(): String = secrets.get(K_OLLAMA_URL).orEmpty()
    private fun serverModel(): String = secrets.get(K_OLLAMA_MODEL).orEmpty()
    private fun hasAnthropicKey() = !secrets.get(K_ANTHROPIC_KEY).isNullOrBlank()

    // What the UI reads. Safe to call while drawing.
    fun hasModel() = config.provider != Provider.None
    fun modelSummary(): String = when (config.provider) {
        Provider.Anthropic -> "Anthropic, ${config.tier.label}"
        Provider.Ollama -> "Ollama, ${config.serverModel.ifBlank { DEFAULT_OLLAMA_MODEL }.substringBefore(':')}"
        Provider.OpenAi -> "${config.openAiModel.ifBlank { "OpenAI-style" }}"
        Provider.None -> "Not connected"
    }

    /**
     * Checks the connection first and saves it only if it works. A blank [key] keeps the saved key.
     * [onResult] runs on the main thread.
     */
    fun connectAnthropic(key: String, tier: AnthropicTier, onResult: (ProbeResult) -> Unit) = connect(onResult,
        check = { ModelProbe.anthropic(key.ifBlank { secrets.get(K_ANTHROPIC_KEY).orEmpty() }) },
        save = {
            if (key.isNotBlank()) secrets.put(K_ANTHROPIC_KEY, key.trim())
            secrets.put(K_ANTHROPIC_MODEL, tier.id)
            secrets.put(K_PROVIDER, Provider.Anthropic.name)
        })

    fun connectOllama(url: String, model: String, onResult: (ProbeResult) -> Unit) = connect(onResult,
        check = { ModelProbe.ollama(url, model) },
        save = {
            secrets.put(K_OLLAMA_URL, url.trim())
            if (model.isBlank()) secrets.remove(K_OLLAMA_MODEL) else secrets.put(K_OLLAMA_MODEL, model.trim())
            secrets.put(K_PROVIDER, Provider.Ollama.name)
        })

    /** Any server that speaks the OpenAI chat format. A blank [key] keeps the saved key. */
    fun connectOpenAi(url: String, key: String, model: String, onResult: (ProbeResult) -> Unit) = connect(onResult,
        check = { ModelProbe.openAi(url, key.ifBlank { secrets.get(K_OPENAI_KEY).orEmpty() }, model) },
        save = {
            secrets.put(K_OPENAI_URL, url.trim())
            if (key.isNotBlank()) secrets.put(K_OPENAI_KEY, key.trim())
            secrets.put(K_OPENAI_MODEL, model.trim())
            secrets.put(K_PROVIDER, Provider.OpenAi.name)
        })

    private fun connect(onResult: (ProbeResult) -> Unit, check: () -> ProbeResult, save: () -> Unit) {
        io.execute {
            val r = try {
                check().also { if (it is ProbeResult.Ok) { save(); reloadConfig() } }
            } catch (e: Exception) {
                ProbeResult.Failed("Something went wrong. ${e.message.orEmpty()}".trim())
            }
            main.post { onResult(r) }
        }
    }

    fun disconnectModel() = io.execute { secrets.put(K_PROVIDER, Provider.None.name); reloadConfig() }

    /** Switches the model off and deletes every model key from this phone. The provider still honours the key until it is revoked on its website. */
    fun forgetModelKeys() = io.execute {
        secrets.put(K_PROVIDER, Provider.None.name)
        secrets.remove(K_ANTHROPIC_KEY); secrets.remove(K_OPENAI_KEY)
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Model key forgotten", "The owner deleted the saved model key from this phone.")
        reloadConfig()
    }

    /** Reads settings from the secure store (background thread only) into the snapshot the UI reads. */
    private fun reloadConfig(ready: Boolean = config.ready) {
        config = Config(
            provider = provider(),
            tier = anthropicTier(),
            serverUrl = serverUrl(),
            serverModel = serverModel(),
            hasAnthropicKey = hasAnthropicKey(),
            openAiUrl = secrets.get(K_OPENAI_URL).orEmpty(),
            openAiModel = secrets.get(K_OPENAI_MODEL).orEmpty(),
            hasOpenAiKey = !secrets.get(K_OPENAI_KEY).isNullOrBlank(),
            hasTelegramToken = hasTelegramToken(),
            relayOn = secrets.get(K_TELEGRAM_MODE) == "relay" && relayLink() != null,
            ready = ready,
        )
    }

    // ---- Packs ---------------------------------------------------------------------------

    private fun packSecret(packId: String, name: String): String? =
        if (packId == TELEGRAM_PACK && name == "bot_token") secrets.get(K_TELEGRAM_TOKEN) else secrets.get("pack:$packId:$name")

    // ---- Pack settings (keys a pack asks for) --------------------------------------------------

    /** Which secrets a pack asks for have been saved, as "packId|name". Only names, never values. */
    var packSecretsSet by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun declaredSecrets(packId: String): List<String> = packs.firstOrNull { it.manifest.id == packId }?.manifest?.permissions?.get("secret").orEmpty()

    fun loadPackSecrets(packId: String) = io.execute {
        val mine = declaredSecrets(packId).filter { !secrets.get("pack:$packId:$it").isNullOrBlank() }.map { "$packId|$it" }
        packSecretsSet = packSecretsSet.filterNot { it.startsWith("$packId|") }.toSet() + mine
    }

    /** Saves a key the pack asked for. A name the pack did not declare is refused. A blank value removes it. */
    fun setPackSecret(packId: String, name: String, value: String) = io.execute {
        if (name !in declaredSecrets(packId)) return@execute
        if (value.isBlank()) secrets.remove("pack:$packId:$name") else secrets.put("pack:$packId:$name", value.trim())
        packRuntime.changed(packId)
        loadPackSecrets(packId)
    }

    fun refreshPacks() = io.execute { packs = packManager.list() }

    /** True when the pack has saved something on this phone (notes, entries, settings). */
    fun packHasData(id: String): Boolean = ::packKv.isInitialized && packKv.hasData(id)

    /** For tests on a developer's own phone: drops what a pack saved under its main key. */
    fun debugForgetPackData(id: String) = io.execute { packKv.delete(id, "data") }

    // ---- Telegram ------------------------------------------------------------------------

    private fun hasTelegramToken() = !secrets.get(K_TELEGRAM_TOKEN).isNullOrBlank()

    /** Checks the token with Telegram, saves it if it works, and starts listening. */
    fun connectTelegram(token: String, onResult: (ProbeResult) -> Unit) {
        io.execute {
            val result = try {
                val username = checkTelegramToken(token.trim())
                secrets.put(K_TELEGRAM_TOKEN, token.trim())
                secrets.put(K_TELEGRAM_NAME, username)
                telegram = TelegramState.Connected(username)
                reloadConfig()
                startTelegramIfConfigured()
                ProbeResult.Ok("@$username")
            } catch (e: Exception) {
                ProbeResult.Failed(friendlyTelegramError(e.message.orEmpty()))
            }
            main.post { onResult(result) }
        }
    }

    fun disconnectTelegram() = io.execute { disconnectTelegramNow() }

    private fun disconnectTelegramNow() {
        if (secrets.get(K_TELEGRAM_MODE) == "relay") runCatching { turnRelayOffNow() }
        supervisor?.stop()
        telegramThread?.interrupt()
        telegramThread = null
        telegram = TelegramState.Off
        secrets.remove(K_TELEGRAM_TOKEN)
        secrets.remove(K_TELEGRAM_NAME)
        reloadConfig()
    }

    private fun checkTelegramToken(token: String): String {
        if (token.isBlank()) throw IllegalArgumentException("Enter the bot token.")
        newTelegramSandbox { token }.use { sandbox ->
            val out = sandbox.callTool("channel.check", "{}")
            return Json.parseToJsonElement(out).jsonObject["username"]?.jsonPrimitive?.content ?: throw IllegalStateException("Telegram did not return a bot name.")
        }
    }

    private fun friendlyTelegramError(raw: String): String = when {
        raw.contains("Unauthorized", true) || raw.contains("Not Found", true) -> "Telegram did not accept this token. Check it and try again."
        raw.contains("Enter the bot token") -> raw
        raw.contains("not installed") -> "The Telegram pack is not installed. Add it from Find more packs."
        raw.contains("relay", true) -> raw
        raw.contains("not recognize", true) -> "The relay does not recognize this phone. Turn reliable delivery off and on again."
        raw.isBlank() -> "Could not reach Telegram."
        else -> "Could not reach Telegram. Check your connection and try again."
    }

    /** A sandbox of its own, so a long poll never blocks the pack's other tools. */
    private fun newTelegramSandbox(token: () -> String?): PackSandbox =
        packRuntime.newSandbox(TELEGRAM_PACK) { name -> if (name == "bot_token") token() else null }

    // ---- Who may use the assistant through a chat app -----------------------------------------

    private val accessLists = HashMap<String, com.pocketnode.core.ChannelAccess>()

    /** The approval list for a chat app. Chats that already existed before approval was introduced are approved once, so an owner's own conversation keeps working. */
    @Synchronized
    private fun channelAccess(packId: String, channel: String): com.pocketnode.core.ChannelAccess = accessLists.getOrPut(packId) {
        com.pocketnode.core.ChannelAccess(packKv, packId).also { access ->
            val prefix = "$channel:"
            access.grandfather(store.sessions().filter { it.id.startsWith(prefix) }.map { com.pocketnode.core.ChannelPerson(it.id.removePrefix(prefix), it.title) })
        }
    }

    private fun unapprovedSender(channel: String, name: String) {
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Ignored a message", "From $name on $channel, who you have not approved.", problem = true)
    }

    private fun channelFor(packId: String) = if (packId == WHATSAPP_PACK) "whatsapp" else "telegram"

    /** People waiting for approval and people approved, for a chat app's pack screen. Background threads only. */
    fun channelPeople(packId: String, onResult: (pending: List<com.pocketnode.core.ChannelPerson>, allowed: List<com.pocketnode.core.ChannelPerson>) -> Unit) = io.execute {
        started.await()
        val a = channelAccess(packId, channelFor(packId))
        val p = a.pending(); val al = a.allowed()
        main.post { onResult(p, al) }
    }

    fun approveChannelPerson(packId: String, chatId: String) = io.execute {
        started.await()
        channelAccess(packId, channelFor(packId)).approve(chatId)
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Approved a chat", "$chatId on ${channelFor(packId)}")
    }

    fun dismissChannelPerson(packId: String, chatId: String) = io.execute { started.await(); channelAccess(packId, channelFor(packId)).dismiss(chatId) }

    fun revokeChannelPerson(packId: String, chatId: String) = io.execute {
        started.await()
        channelAccess(packId, channelFor(packId)).revoke(chatId)
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Removed a chat's approval", "$chatId on ${channelFor(packId)}")
    }

    @Synchronized
    private fun startTelegramIfConfigured() {
        if (telegramThread?.isAlive == true || !hasTelegramToken() || packs.none { it.manifest.id == TELEGRAM_PACK }) return
        val rt = runtime ?: return
        val name = secrets.get(K_TELEGRAM_NAME).orEmpty()
        telegram = if (name.isNotEmpty()) TelegramState.Connected(name) else TelegramState.Connecting
        telegramThread = Thread({
            newTelegramSandbox { secrets.get(K_TELEGRAM_TOKEN) }.use { sandbox ->
                val runner = ChannelRunner("telegram", TELEGRAM_PACK, sandbox, rt, packKv, channelAccess(TELEGRAM_PACK, "telegram"), ::unapprovedSender) { session, title -> store.setSessionTitle(session, title) }
                val relayRunner = RelayInboxRunner(
                    relayClient, { relayLink()?.credentials ?: throw RelayException("Reliable delivery is not set up.", 401) },
                    TELEGRAM_PACK, sandbox, runner, packKv,
                )
                val sup = ChannelSupervisor(
                    online = ::isOnline,
                    poll = {
                        if (!hasTelegramToken()) throw InterruptedException()
                        if (secrets.get(K_TELEGRAM_MODE) == "relay") pollRelayRecovering(relayRunner) else runner.pollOnce()
                    },
                    onState = { st ->
                        telegram = when (st) {
                            SupervisorState.Running -> TelegramState.Connected(secrets.get(K_TELEGRAM_NAME).orEmpty())
                            SupervisorState.WaitingForNetwork -> TelegramState.Waiting
                            is SupervisorState.Problem -> TelegramState.Problem(friendlyTelegramError(st.message))
                        }
                    },
                )
                supervisor = sup
                startNetworkWatcher()
                try { sup.run() } finally { if (supervisor === sup) supervisor = null }
            }
        }, "telegram-poller").apply { isDaemon = true; start() }
    }

    // ---- WhatsApp (official Business Cloud API; events arrive through the relay) ---------

    @Volatile private var whatsappThread: Thread? = null
    @Volatile private var waSupervisor: ChannelSupervisor? = null

    private fun waLink(): RelayLink? = secrets.get(K_WA_LINK)?.split('|')?.takeIf { it.size == 4 }?.let { RelayLink(RelayCredentials(it[0], it[1]), it[2], it[3]) }

    private fun saveWaLink(l: RelayLink) = secrets.put(K_WA_LINK, listOf(l.credentials.deviceId, l.credentials.secret, l.routeId, l.hookSecret).joinToString("|"))

    private fun loadWhatsAppSetup() {
        val l = waLink() ?: return
        whatsappSetup = WhatsAppSetup("${BuildConfig.RELAY_URL.trimEnd('/')}/hook/whatsapp/${l.routeId}", l.hookSecret)
    }

    private fun friendlyWhatsAppError(raw: String): String = when {
        raw.contains("not installed") -> "The WhatsApp pack is not installed. Add it from Find more packs."
        raw.contains("Invalid", true) || raw.contains("OAuth", true) || raw.contains("token", true) -> "WhatsApp did not accept these details. Check the access token and phone number ID."
        raw.contains("not recognize", true) -> "The relay does not recognize this phone. Disconnect WhatsApp and connect it again."
        raw.isBlank() -> "Could not reach WhatsApp."
        else -> "Could not reach WhatsApp. Check your connection and try again."
    }

    /**
     * Checks the access token and phone number with Meta, creates an address on the relay for Meta to call,
     * and starts listening. [onResult] gets a message for the user, or null on success.
     */
    fun connectWhatsApp(accessToken: String, phoneNumberId: String, appSecret: String, onResult: (String?) -> Unit) {
        io.execute {
            val message = try {
                if (!relayAvailable) throw RelayException("WhatsApp needs the relay, which is not available yet.", 0)
                if (accessToken.isBlank() || phoneNumberId.isBlank()) throw IllegalArgumentException("Enter the access token and the phone number ID.")
                if (appSecret.trim().length < 8) throw IllegalArgumentException("Enter the app secret from your Meta app.")
                val token = accessToken.trim(); val phoneId = phoneNumberId.trim()
                val number = packRuntime.newSandbox(WHATSAPP_PACK) { name -> when (name) { "access_token" -> token; "phone_number_id" -> phoneId; else -> null } }.use { sb ->
                    Json.parseToJsonElement(sb.callTool("channel.check", "{}")).jsonObject["number"]?.jsonPrimitive?.content.orEmpty()
                }
                secrets.put("pack:$WHATSAPP_PACK:access_token", token)
                secrets.put("pack:$WHATSAPP_PACK:phone_number_id", phoneId)
                secrets.put(K_WA_NUMBER, number)
                val old = waLink()
                old?.let { runCatching { relayClient.deleteRoute(it.credentials, it.routeId) } }
                var creds = old?.credentials ?: relayClient.register()
                val route = try { relayClient.createRoute(creds, "whatsapp", appSecret.trim()) } catch (e: RelayException) {
                    if (!e.notRecognized || old == null) throw e
                    creds = relayClient.register() // the relay forgot this phone
                    relayClient.createRoute(creds, "whatsapp", appSecret.trim())
                }
                saveWaLink(RelayLink(creds, route.routeId, route.secret))
                loadWhatsAppSetup()
                refreshPushRegistration()
                startWhatsAppIfConfigured()
                null
            } catch (e: RelayException) {
                e.message
            } catch (e: IllegalArgumentException) {
                e.message
            } catch (e: Exception) {
                friendlyWhatsAppError(e.message.orEmpty())
            }
            main.post { onResult(message) }
        }
    }

    fun disconnectWhatsApp() = io.execute { disconnectWhatsAppNow() }

    private fun disconnectWhatsAppNow() {
        val link = waLink()
        link?.let { runCatching { relayClient.deleteRoute(it.credentials, it.routeId) } }
        secrets.remove(K_WA_LINK)
        secrets.remove(K_WA_NUMBER)
        secrets.remove("pack:$WHATSAPP_PACK:access_token")
        secrets.remove("pack:$WHATSAPP_PACK:phone_number_id")
        waSupervisor?.stop()
        whatsappThread?.interrupt()
        whatsappThread = null
        whatsapp = WhatsAppState.Off
        whatsappSetup = null
    }

    @Synchronized
    private fun startWhatsAppIfConfigured() {
        if (whatsappThread?.isAlive == true || waLink() == null || packs.none { it.manifest.id == WHATSAPP_PACK }) return
        val rt = runtime ?: return
        val number = secrets.get(K_WA_NUMBER).orEmpty()
        whatsapp = if (number.isNotEmpty()) WhatsAppState.Connected(number) else WhatsAppState.Connecting
        whatsappThread = Thread({
            packRuntime.newSandbox(WHATSAPP_PACK).use { sandbox ->
                val runner = ChannelRunner("whatsapp", WHATSAPP_PACK, sandbox, rt, packKv, channelAccess(WHATSAPP_PACK, "whatsapp"), ::unapprovedSender) { session, title -> store.setSessionTitle(session, title) }
                val inbox = RelayInboxRunner(relayClient, { waLink()?.credentials ?: throw RelayException("WhatsApp is not set up.", 401) }, WHATSAPP_PACK, sandbox, runner, packKv)
                val sup = ChannelSupervisor(
                    online = ::isOnline,
                    poll = {
                        if (waLink() == null) throw InterruptedException()
                        inbox.pollOnce()
                    },
                    onState = { st ->
                        whatsapp = when (st) {
                            SupervisorState.Running -> WhatsAppState.Connected(secrets.get(K_WA_NUMBER).orEmpty())
                            SupervisorState.WaitingForNetwork -> WhatsAppState.Waiting
                            is SupervisorState.Problem -> WhatsAppState.Problem(friendlyWhatsAppError(st.message))
                        }
                    },
                )
                waSupervisor = sup
                startNetworkWatcher()
                try { sup.run() } finally { if (waSupervisor === sup) waSupervisor = null }
            }
        }, "whatsapp-inbox").apply { isDaemon = true; start() }
    }

    // ---- Attachments the owner adds to a message ---------------------------------------------

    /** Set by the main screen: shows Android's "save as" screen for a file name and type, and reports where the owner chose to put it (null if cancelled). */
    @Volatile var fileSaver: ((String, String, (android.net.Uri?) -> Unit) -> Unit)? = null

    /**
     * A plain copy of a stored file for another app to read, because the owner chose Open or Share. It is erased after 15 minutes
     * (and at the next start). The unlock is written to the Activity record. Null if the file is gone or cannot be unlocked.
     */
    fun unlockedCopy(ref: String, how: String): File? {
        val copy = assetFiles.plainCopy(ref) ?: return null
        io.execute { activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "File unlocked", "${copy.name} was $how. A plain copy exists for a few minutes.") }
        main.postDelayed({ io.execute { assetFiles.clearCopies(UNLOCK_MILLIS) } }, UNLOCK_MILLIS + 5_000)
        return copy
    }

    /** Saves a plain copy of a stored file where the owner chooses. [done] gets a plain sentence to show. */
    fun saveFile(ref: String, done: (String) -> Unit) {
        val a = assetFiles.info(ref) ?: return done("That file is no longer stored.")
        val saver = fileSaver ?: return done("Open Pocketnode on the screen to save a file.")
        saver(a.name, a.mime) { uri ->
            if (uri == null) { done("Not saved."); return@saver }
            io.execute {
                val ok = runCatching {
                    val bytes = checkNotNull(assetFiles.bytes(ref)) { "locked" }
                    appContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("no stream")
                }.isSuccess
                if (ok) activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "File saved to the phone", "${a.name} was saved, unlocked, to a place the owner chose.")
                main.post { done(if (ok) "Saved ${a.name} to your phone." else "${a.name} could not be saved.") }
            }
        }
    }

    private const val UNLOCK_MILLIS = 15 * 60_000L

    /**
     * Erases what the owner made and said (see [com.pocketnode.core.DataWipe]) plus every stored file and photo, and cancels the
     * reminder alarms. Model, packs, permissions and paired devices are kept. [done] gets a plain sentence.
     */
    fun eraseData(done: (String) -> Unit) = io.execute {
        started.await()
        val wipe = com.pocketnode.core.DataWipe(db)
        val ids = wipe.reminderIds()
        val result = runCatching { wipe.erase() }
        val scheduler = Reminders.scheduler(appContext)
        ids.forEach { runCatching { scheduler.cancel(it) } }
        runCatching { assetFiles.eraseAll(); attachedFiles.eraseAll(); photoFiles.eraseAll() }
        File(appContext.cacheDir, "capture").listFiles()?.forEach { eraseFile(it) }
        val c = result.getOrNull()
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Your data was erased", if (c == null) "Erasing the saved data failed." else "${c.chats} chats, ${c.reminders} reminders and ${c.packEntries} pack entries were erased, with every stored file and photo.", problem = c == null)
        main.post { done(if (c == null) "Something went wrong. Some data may remain." else "Erased ${c.chats} chats, ${c.reminders} reminders, your notes and your stored files.") }
    }

    /** Asks the owner for an export file, reads and checks it, and hands back what it holds (or a plain sentence if it cannot be used). Nothing is added yet. */
    fun readImport(done: (com.pocketnode.core.ImportPlan?, String?) -> Unit) {
        val pick = filePicker ?: return done(null, "Open Pocketnode on the screen to import.")
        pick(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) { uris ->
            val uri = uris.firstOrNull() ?: return@pick done(null, null)
            io.execute {
                val result = runCatching {
                    val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readUpTo(60_000_001) } ?: throw IllegalArgumentException("That file could not be opened.")
                    if (bytes.size > 60_000_000) throw IllegalArgumentException("That file is too big to import.")
                    com.pocketnode.core.DataImport.read(bytes)
                }
                main.post { result.fold({ done(it, null) }, { done(null, (it as? IllegalArgumentException)?.message ?: "That file could not be read.") }) }
            }
        }
    }

    /** Adds what an export file holds that this phone does not already have. [done] gets a plain summary. */
    fun applyImport(plan: com.pocketnode.core.ImportPlan, done: (String) -> Unit) = io.execute {
        started.await()
        val outcome = runCatching {
            val scheduler = Reminders.scheduler(appContext)
            val notesInstalled = packs.any { it.manifest.id == com.pocketnode.core.DataImport.NOTES_PACK }
            val r = com.pocketnode.core.DataImport.apply(plan, store, scheduler.store, if (notesInstalled) packKv else null, System.currentTimeMillis(), packKv, packs.map { it.manifest.id }.toSet())
            r.remindersAdded.forEach { scheduler.schedule(it) }
            r
        }
        outcome.onSuccess { r ->
            activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Data imported", "${r.chatsAdded} chats, ${r.remindersAdded.size} reminders, ${r.notesAdded} ${if (r.notesAdded == 1) "note" else "notes"} and ${r.todosAdded} ${if (r.todosAdded == 1) "to-do" else "to-dos"} were added from an export file the owner chose.")
        }
        main.post {
            done(outcome.fold({ r ->
                buildString {
                    append("Added ${r.chatsAdded} chats")
                    if (r.chatsSkipped > 0) append(" (${r.chatsSkipped} were already here)")
                    append(", ${r.remindersAdded.size} reminders")
                    if (r.notesAdded + r.todosAdded > 0) append(", ${r.notesAdded} ${if (r.notesAdded == 1) "note" else "notes"} and ${r.todosAdded} ${if (r.todosAdded == 1) "to-do" else "to-dos"}")
                    append(".")
                    if (r.packItemsAdded > 0) append(" Also added ${r.packItemsAdded} ${if (r.packItemsAdded == 1) "item" else "items"} saved by your other packs.")
                    if (r.notesSkipped) append(" The notes were left out because the Notes pack is not installed.")
                    if (r.packsNotInstalled.isNotEmpty()) append(" Left out because the pack is not installed: ${r.packsNotInstalled.joinToString(", ")}.")
                    append(" Photos and attached files are not in an export, so they do not come back.")
                }
            }, { "The import could not be finished." }))
        }
    }

    /** Saves the Activity record, or the part of it that is on screen, as a plain text file where the owner chooses. [done] gets a plain sentence. */
    fun saveActivityRecord(kind: com.pocketnode.core.ActivityKind?, query: String, problemsOnly: Boolean, done: (String) -> Unit) {
        val saver = fileSaver ?: return done("Open Pocketnode on the screen to save the record.")
        val name = "pocketnode-activity-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".txt"
        saver(name, "text/plain") { uri ->
            if (uri == null) done("Not saved.")
            else io.execute {
                started.await()
                val count = runCatching {
                    val entries = activity.recent(com.pocketnode.core.ActivityLog.KEEP, kind, query, problemsOnly)
                    val text = com.pocketnode.core.ActivityText.render(entries, System.currentTimeMillis())
                    appContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
                    entries.size
                }
                count.onSuccess { activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Activity record saved", "$it entries were saved as a text file the owner chose.") }
                main.post { done(count.fold({ "Saved $it ${if (it == 1) "entry" else "entries"}. The file is not locked." }, { "The record could not be saved." })) }
            }
        }
    }

    /** The day's model allowance, counted in estimated tokens. */
    lateinit var usage: com.pocketnode.core.UsageMeter
        private set

    /** The chosen daily limit in estimated tokens, 0 for none. Kept as state so the Settings row redraws. */
    var usageLimit by mutableStateOf(0L)
        private set

    fun chooseUsageLimit(tokens: Long) { usageLimit = tokens; io.execute { started.await(); usage.limit = tokens } }

    /** For the Settings row: the limit, and about how much of it has been used today. */
    fun usageSummary(): String {
        if (!::usage.isInitialized) return ""
        val used = usage.usedToday()
        val usedText = if (used >= 1_000) "${used / 1_000}k" else "$used"
        return if (usageLimit <= 0) "No limit" else "$usedText of ${usageLimit / 1_000}k"
    }

    /** Asks Android to clear everything the app stores, including keys, and close it. The next start is the first-run setup. */
    fun resetApp(context: Context): Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()

    /**
     * Saves chats, reminders, notes and to-dos as one zip file where the owner chooses (Android's save screen). Nothing is built
     * until a place is chosen. The saved file is not locked, and the owner is told so first. [done] gets a plain sentence to show.
     */
    fun exportData(done: (String) -> Unit) {
        val saver = fileSaver ?: return done("Open Pocketnode on the screen to export.")
        val name = "pocketnode-export-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip"
        saver(name, "application/zip") { uri ->
            if (uri == null) done("Not saved.")
            else io.execute {
                started.await()
                val counts = runCatching {
                    val chats = store.sessions().map { s -> com.pocketnode.core.ExportChat(s.id, s.title, s.lastActivityMillis, store.timeline(s.id)) }
                    val reminders = Reminders.scheduler(appContext).store.list()
                    val bytes = com.pocketnode.core.DataExport.zip(chats, reminders, packKv.get("com.pocketnode.notes", "data"), System.currentTimeMillis(), com.pocketnode.core.PackData.SPECS.associate { it.packId to packKv.get(it.packId, it.key) })
                    appContext.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("no stream")
                    chats.size to reminders.size
                }
                counts.onSuccess { (c, r) -> activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Data exported", "$c chats, $r reminders, and the notes and to-dos were saved, unlocked, in a zip file the owner chose.") }
                main.post { done(if (counts.isSuccess) "Saved. The file holds ${counts.getOrThrow().first} chats and is not locked." else "The export could not be saved.") }
            }
        }
    }

    /** Set by the main screen: shows Android's file picker and reports the files chosen (an empty list if cancelled). */
    @Volatile var filePicker: ((Array<String>, (List<android.net.Uri>) -> Unit) -> Unit)? = null

    /** Set by the main screen: opens the phone's speech recognition and reports the words heard (null if nothing was heard or there is no speech service). */
    @Volatile var speechPicker: (((String?) -> Unit) -> Unit)? = null

    /** Opens the camera for the owner to take a photo to attach. [onResult] gets the attachment, or a plain message if nothing was taken. */
    fun attachFromCamera(onResult: (List<Attached>, String?) -> Unit) = io.execute {
        started.await()
        val result = try {
            val stored = camera.capture()
            listOf(Attached(stored.ref, "Photo", true)) to null
        } catch (e: IllegalStateException) {
            emptyList<Attached>() to (if (e.message == CameraCapturer.NO_PHOTO) "No photo was taken." else e.message)
        } catch (e: Exception) {
            emptyList<Attached>() to "The photo could not be saved."
        }
        main.post { onResult(result.first, result.second) }
    }

    /** For developer tools: attaches a picture or a text file from memory without the pickers. */
    fun attachBytes(label: String, bytes: ByteArray, isPhoto: Boolean, onResult: (List<Attached>, String?) -> Unit) = io.execute {
        started.await()
        val result = try {
            if (isPhoto) listOf(Attached(photoFiles.store(bytes).ref, label, true)) to null
            else listOf(Attached(attachedFiles.store(label, bytes.decodeToString()), label, false)) to null
        } catch (e: Exception) { emptyList<Attached>() to "Could not attach ${label}." }
        main.post { onResult(result.first, result.second) }
    }

    /** Shows the file picker and attaches what the owner chooses: photos, the first pages of PDFs, and text files. */
    fun attachFromFiles(onResult: (List<Attached>, String?) -> Unit) {
        val pick = filePicker ?: return onResult(emptyList(), "Open the app to attach files.")
        main.post {
            pick(FileIntake.PICKER_TYPES) { uris ->
                if (uris.isEmpty()) { onResult(emptyList(), null); return@pick }
                attachFromUris(uris, onResult)
            }
        }
    }

    /** Reads files another app handed over (a share, or the picker) and attaches what can be read. At most five at a time. */
    fun attachFromUris(uris: List<android.net.Uri>, onResult: (List<Attached>, String?) -> Unit) = io.execute {
        started.await()
        val items = mutableListOf<Attached>()
        val notes = mutableListOf<String>()
        for (uri in uris.take(5)) {
            try { val r = intake.intake(uri); items += r.items; r.note?.let { notes += it } }
            catch (e: IllegalArgumentException) { notes += e.message.orEmpty() }
            catch (e: Exception) { notes += "A file could not be read." }
        }
        if (uris.size > 5) notes += "Only the first 5 files were attached."
        main.post { onResult(items, notes.joinToString(" ").ifBlank { null }) }
    }

    // ---- Asking the owner before private content is read -----------------------------------

    class AskRequest(val title: String, val message: String, val allow: String, val deny: String)

    /** A question for the owner, shown over whatever screen is open. Null when nothing is being asked. */
    var pendingAsk by mutableStateOf<AskRequest?>(null)
        private set
    private val askLock = Any()
    private var askFuture: java.util.concurrent.CompletableFuture<Boolean>? = null

    /**
     * Called from the assistant's thread before it reads something private (photos, contacts, calendar, location, the app list).
     * Waits for the owner's answer. Returns null if they allowed it, or a sentence saying why not.
     */
    private fun hostOf(url: String?): String = runCatching { java.net.URI(url.orEmpty()).host }.getOrNull() ?: url.orEmpty().ifBlank { "unknown" }

    private fun askOwner(q: Question): String? {
        val answer = askOwnerNow(q)
        activity.add(com.pocketnode.core.ActivityKind.ASKED, q.title, if (answer == null) "You allowed it." else answer.substringBefore(". Do not").take(160), problem = answer != null)
        return answer
    }

    private fun askOwnerNow(q: Question): String? {
        val title = q.title
        val message = q.message
        if (visibleActivities <= 0) return "The owner can only be asked while Pocketnode is open on the screen, so this was not allowed. Do not try again."
        val answer = java.util.concurrent.CompletableFuture<Boolean>()
        synchronized(askLock) { if (askFuture != null) return "The owner is already being asked something else. Do not try again."; askFuture = answer }
        main.post { pendingAsk = AskRequest(title, message, q.allow, q.deny) }
        val asked = System.currentTimeMillis()
        android.util.Log.i("Pocketnode", "asking the owner: $title")
        return try {
            if (answer.get(120, java.util.concurrent.TimeUnit.SECONDS)) { android.util.Log.i("Pocketnode", "owner allowed after ${(System.currentTimeMillis() - asked) / 1000}s: $title"); null }
            else { android.util.Log.i("Pocketnode", "owner declined after ${(System.currentTimeMillis() - asked) / 1000}s: $title"); "The owner chose not to allow this. Do not try again unless they ask for it." }
        } catch (e: Exception) {
            android.util.Log.i("Pocketnode", "owner did not answer: $title")
            "The owner did not answer, so this was not allowed. Do not try again."
        } finally {
            synchronized(askLock) { askFuture = null }
            main.post { pendingAsk = null }
        }
    }

    fun answerAsk(allow: Boolean) { synchronized(askLock) { askFuture?.complete(allow) } }

    // ---- Email account -----------------------------------------------------------------------

    /** The saved mailbox without its password, for the settings screen. Null if none is set up. */
    var mailAccountInfo by mutableStateOf<MailAccount?>(null)
        private set

    private fun mailAccountOrNull(): MailAccount? {
        val raw = secrets.get(K_MAIL) ?: return null
        return runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            fun str(k: String) = o.getValue(k).jsonPrimitive.content
            fun sec(k: String) = MailSecurity.valueOf(str(k))
            MailAccount(str("address"), str("name"), str("smtpHost"), str("smtpPort").toInt(), sec("smtpSecurity"), str("imapHost"), str("imapPort").toInt(), sec("imapSecurity"), str("username"), str("password"))
        }.getOrNull()
    }

    fun loadMailAccount() = io.execute { mailAccountInfo = mailAccountOrNull()?.copy(password = "") }

    /**
     * Checks the login with the mail server (sending and reading) and saves the account if sending works. [onResult] gets a
     * message for the owner: null on success, or what is wrong. The password is kept in the Keystore and never shown again.
     */
    fun saveMailAccount(a: MailAccount, onResult: (String?, String?) -> Unit) = io.execute {
        var problem: String? = null
        var readNote: String? = null
        try {
            if (!com.pocketnode.core.MailCodec.isAddress(a.address)) throw MailException("That is not an email address.")
            val password = a.password.ifBlank { mailAccountOrNull()?.password.orEmpty() }
            if (password.isBlank()) throw MailException("Enter the password. For Gmail, Outlook, Yahoo and iCloud, make an app password and use that.")
            SmtpClient(a.smtpHost, a.smtpPort, a.smtpSecurity).checkLogin(a.username, password)
            try { ImapClient(a.imapHost, a.imapPort, a.imapSecurity).checkLogin(a.username, password) } catch (e: MailException) { readNote = "Sending works, but reading your inbox did not: ${e.message}" }
            val o = kotlinx.serialization.json.buildJsonObject {
                put("address", a.address.trim()); put("name", a.name.trim()); put("smtpHost", a.smtpHost.trim()); put("smtpPort", a.smtpPort.toString()); put("smtpSecurity", a.smtpSecurity.name)
                put("imapHost", a.imapHost.trim()); put("imapPort", a.imapPort.toString()); put("imapSecurity", a.imapSecurity.name); put("username", a.username.trim()); put("password", password)
            }
            secrets.put(K_MAIL, o.toString())
        } catch (e: MailException) { problem = e.message } catch (e: Exception) { problem = "Something went wrong. ${e.message.orEmpty()}".trim() }
        if (problem == null) mailAccountInfo = mailAccountOrNull()?.copy(password = "")
        main.post { onResult(problem, readNote) }
    }

    fun removeMailAccount() = io.execute { secrets.remove(K_MAIL); mailAccountInfo = null }

    // ---- Reminders ---------------------------------------------------------------------------

    /** The reminders waiting to go off, soonest first. */
    var reminders by mutableStateOf<List<com.pocketnode.core.Reminder>>(emptyList())
        private set

    fun loadReminders() = io.execute { reminders = Reminders.scheduler(appContext).store.list() }

    fun cancelReminder(id: Long) = io.execute {
        Reminders.scheduler(appContext).let { it.store.cancel(id); it.cancel(id) }
        reminders = Reminders.scheduler(appContext).store.list()
    }

    // ---- Phone permissions -----------------------------------------------------------------

    /** Set by the main screen: asks Android for runtime permissions and reports whether all were given (any one, for [anyOne]). */
    @Volatile var permissionRequester: ((Array<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null

    /** Phone operations the owner has switched on, as "packId|operation". Read from the screens. */
    var deviceGrants by mutableStateOf<Set<String>>(emptySet())
        private set

    /** For developer tools: switches every sensitive phone ability the pack declares on or off. */
    fun debugGrantAll(packId: String) = io.execute { packs.firstOrNull { it.manifest.id == packId }?.manifest?.declared().orEmpty().filter { it.scope == "device" && com.pocketnode.core.DeviceOps.isSensitive(it.target) }.forEach { broker.grant(packId, it) }; loadDeviceGrants(packId) }
    fun debugRevokeAll(packId: String) = io.execute { packs.firstOrNull { it.manifest.id == packId }?.manifest?.declared().orEmpty().filter { it.scope == "device" }.forEach { broker.revoke(packId, it) }; loadDeviceGrants(packId) }

    fun loadDeviceGrants(packId: String) = io.execute {
        if (!::broker.isInitialized) return@execute
        val declared = packs.firstOrNull { it.manifest.id == packId }?.manifest?.declared().orEmpty().filter { it.scope == "device" }
        val on = declared.filter { broker.authorize(packId, declared.toSet(), setOf(it)) is com.pocketnode.core.Decision.Allowed }.map { "$packId|${it.target}" }
        deviceGrants = deviceGrants.filterNot { it.startsWith("$packId|") }.toSet() + on
    }

    /** Switches a phone operation on or off for a pack. Switching a sensitive one on first asks Android; [onResult] gets a message on failure. */
    fun setDevicePermission(packId: String, op: String, on: Boolean, onResult: (String?) -> Unit) {
        val permission = Permission("device", op)
        if (!on) {
            io.execute { broker.revoke(packId, permission); activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Permission switched off", "${packs.firstOrNull { it.manifest.id == packId }?.manifest?.name ?: packId}: ${com.pocketnode.core.DeviceOps.get(op)?.label ?: op}"); loadDeviceGrants(packId); main.post { onResult(null) } }
            return
        }
        val needed = AndroidDeviceHost.androidPermissions(op).toTypedArray()
        val finish = { ok: Boolean, why: String? ->
            io.execute {
                if (ok) { broker.grant(packId, permission); activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Permission switched on", "${packs.firstOrNull { it.manifest.id == packId }?.manifest?.name ?: packId}: ${com.pocketnode.core.DeviceOps.get(op)?.label ?: op}") }
                loadDeviceGrants(packId)
                main.post { onResult(if (ok) null else why) }
            }
        }
        val missing = needed.filter { androidx.core.content.ContextCompat.checkSelfPermission(appContext, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { finish(true, null); return }
        val ask = permissionRequester ?: return finish(false, "Open the app to allow this.")
        main.post {
            ask(missing.toTypedArray()) { result ->
                // Location is satisfied by either precise or approximate access; everything else needs all of them.
                val ok = if (op == "location.get") result.values.any { it } else result.values.all { it }
                finish(ok, "Android did not allow it. You can allow it in the phone's Settings under Apps, Pocketnode, Permissions.")
            }
        }
    }

    // ---- Reliable delivery (relay) -------------------------------------------------------

    /** device id, secret, route id, hook secret. All four are URL-safe tokens, so a bar separates them safely. */
    private fun relayLink(): RelayLink? = secrets.get(K_RELAY_LINK)?.split('|')?.takeIf { it.size == 4 }?.let { RelayLink(RelayCredentials(it[0], it[1]), it[2], it[3]) }

    private fun saveRelayLink(l: RelayLink) = secrets.put(K_RELAY_LINK, listOf(l.credentials.deviceId, l.credentials.secret, l.routeId, l.hookSecret).joinToString("|"))

    /** Turns reliable delivery on or off. [onResult] gets null on success or a message for the user. */
    fun setTelegramRelay(on: Boolean, onResult: (String?) -> Unit) {
        io.execute {
            val message = try {
                if (on) turnRelayOnNow() else turnRelayOffNow()
                null
            } catch (e: RelayException) {
                e.message
            } catch (e: Exception) {
                friendlyTelegramError(e.message.orEmpty())
            }
            reloadConfig()
            // The old mode's last request may have failed just before the switch; do not leave that on screen.
            if (message == null) secrets.get(K_TELEGRAM_NAME)?.takeIf { it.isNotEmpty() }?.let { telegram = TelegramState.Connected(it) }
            supervisor?.wake()
            main.post { onResult(message) }
        }
    }

    private fun turnRelayOnNow() {
        if (!relayAvailable) throw RelayException("Reliable delivery is not available yet.", 0)
        val token = secrets.get(K_TELEGRAM_TOKEN) ?: throw IllegalStateException("Connect the bot first.")
        newTelegramSandbox { token }.use { sandbox ->
            val existing = relayLink()?.credentials
            // Starting over from the same phone identity keeps one device record on the relay.
            relayLink()?.let { old -> runCatching { relayClient.deleteRoute(old.credentials, old.routeId) } }
            saveRelayLink(RelaySetup(relayClient).enable(existing, sandbox))
        }
        secrets.put(K_TELEGRAM_MODE, "relay")
        refreshPushRegistration()
    }

    private var lastRelayRecovery = 0L

    /**
     * If the relay no longer knows this phone (for example it lost its data), register again and
     * set the route up from scratch instead of asking the user to do it. Tried at most every ten minutes.
     */
    private fun pollRelayRecovering(runner: RelayInboxRunner) {
        try {
            runner.pollOnce()
        } catch (e: RelayException) {
            val now = System.currentTimeMillis()
            if (!e.notRecognized || now - lastRelayRecovery < 600_000) throw e
            lastRelayRecovery = now
            android.util.Log.i("Pocketnode", "relay did not recognize this phone, registering again")
            turnRelayOnNow()
            reloadConfig()
        }
    }

    private fun turnRelayOffNow() {
        val token = secrets.get(K_TELEGRAM_TOKEN)
        val link = relayLink()
        secrets.put(K_TELEGRAM_MODE, "direct")
        if (link != null && token != null) newTelegramSandbox { token }.use { RelaySetup(relayClient).disable(link, it) }
    }

    // ---- Push wake-up --------------------------------------------------------------------

    /** Called when Firebase gives this phone a new token. Tells the relay, if reliable delivery is on. */
    fun onPushToken(token: String) {
        io.execute {
            secrets.put(K_PUSH_TOKEN, token)
            registerPushWithRelay(token)
        }
    }

    /** A wake-up arrived: check the relay right now instead of waiting for the next poll. */
    fun wakeFromPush() {
        io.execute {
            if (telegramThread?.isAlive != true) startTelegramIfConfigured()
            if (whatsappThread?.isAlive != true) startWhatsAppIfConfigured()
            waSupervisor?.wake()
            supervisor?.wake()
        }
    }

    /** Gets the current token (needs Google Play services; silently skipped on phones without it) and gives it to the relay. */
    private fun refreshPushRegistration() {
        val token = try { com.google.android.gms.tasks.Tasks.await(com.google.firebase.messaging.FirebaseMessaging.getInstance().token, 20, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { return }
        secrets.put(K_PUSH_TOKEN, token)
        registerPushWithRelay(token)
    }

    private fun registerPushWithRelay(token: String) {
        for (creds in listOfNotNull(relayLink()?.credentials, waLink()?.credentials)) {
            try { relayClient.setPush(creds, "fcm", token) } catch (e: Exception) { android.util.Log.w("Pocketnode", "push registration failed: ${e.message}") }
        }
    }


    // ---- Network -------------------------------------------------------------------------

    private val lastAutoRetry = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Tries again the owner's chats whose last reply failed only for want of a connection. Not while the assistant is switched off. */
    fun retryWaitingChats() {
        if (paused || !::store.isInitialized) return
        reads.execute {
            storeReady.await()
            val now = System.currentTimeMillis()
            val waiting = com.pocketnode.core.OfflineQueue.waiting(store.sessions(), now, lastAutoRetry)
            if (waiting.isEmpty()) return@execute
            waiting.forEach { lastAutoRetry[it.id] = now }
            activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Back online", "${waiting.size} ${if (waiting.size == 1) "chat was" else "chats were"} waiting for a connection and ${if (waiting.size == 1) "is" else "are"} being tried again.")
            main.post { AppModel.resumeWaiting(waiting.map { it.id }) }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Wakes the poller the moment a usable network appears instead of waiting out a timer. */
    @Synchronized
    private fun startNetworkWatcher() {
        if (networkWatcherStarted) return
        networkWatcherStarted = true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var wasOnline = true
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (online) supervisor?.wake()
                // Back online after being offline: answer the chats that were left waiting for a connection.
                if (online && !wasOnline) retryWaitingChats()
                wasOnline = online
            }
            override fun onLost(network: Network) { wasOnline = false }
        })
    }

    // ---- Conversations -------------------------------------------------------------------

    fun loadSessions(onResult: (List<SessionSummary>) -> Unit) = reads.execute { storeReady.await(); val r = store.sessions(); main.post { onResult(r) } }
    fun loadHistory(sessionId: String, onResult: (List<Msg>) -> Unit) = reads.execute { storeReady.await(); val r = store.timeline(sessionId); main.post { onResult(r) } }

    /** The answer so far for each chat that is being answered right now, shown while the model is still writing. Memory only. */
    val partials = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** Passes the growing answer to [partials], at most about twelve times a second so the screen is not redrawn for every word. */
    private class PartialSink(private val sessionId: String, private val forward: ((String) -> Unit)? = null) : com.pocketnode.core.EventSink {
        private val latest = java.util.concurrent.atomic.AtomicReference<String?>(null)
        @Volatile private var closed = false

        override fun emit(event: com.pocketnode.core.RunEvent) {
            if (event !is com.pocketnode.core.RunEvent.Partial || closed) return
            forward?.let { f -> val t = event.text; main.post { f(t) } }
            if (latest.getAndSet(event.text) == null) main.postDelayed({ val t = latest.getAndSet(null); if (!closed && t != null) { partials[sessionId] = t; if (BuildConfig.DEBUG) android.util.Log.d("Pocketnode", "partial answer: ${t.length} characters") } }, 80)
        }

        /** Stops showing the partial answer, a moment after the saved one is on screen so nothing blinks. */
        fun close() { closed = true; main.postDelayed({ partials.remove(sessionId) }, 400) }
    }

    /** [title] names a new chat (a call is named by its time); [onPartial] gets the answer as it is being written, on the main thread. */
    @Volatile private var sendBegan = 0L
    fun send(sessionId: String, text: String, title: String? = null, onPartial: ((String) -> Unit)? = null, onDone: (RunResult) -> Unit) {
        val queuedAt = System.currentTimeMillis()
        val token = com.pocketnode.core.ModelCancel.begin()
        agent.execute {
            com.pocketnode.core.ModelCancel.enter(token)
            started.await()
            sendBegan = System.currentTimeMillis()
            if (VoiceCall.active) android.util.Log.i("Pocketnode", "send: waited ${sendBegan - queuedAt} ms for the agent thread")
            val rt = checkNotNull(runtime)
            if (store.history(sessionId).isEmpty()) store.setSessionTitle(sessionId, title ?: com.pocketnode.core.Attachments.preview(text).ifBlank { "New chat" }.take(40))
            val sink = PartialSink(sessionId, onPartial)
            val result = try { rt.run(sessionId, text, sink) } finally { sink.close(); com.pocketnode.core.ModelCancel.leave() }
            main.post { onDone(result); if (VoiceCall.forgets(sessionId)) deleteChat(sessionId) { AppModel.refreshChats() } }
        }
    }

    /** Tries the last failed reply again from where it stopped. A photo already taken is not taken again. */
    fun retry(sessionId: String, onDone: (RunResult?) -> Unit) {
        agent.execute {
            started.await()
            val sink = PartialSink(sessionId)
            val result = try { checkNotNull(runtime).retry(sessionId, sink) } finally { sink.close() }
            main.post { onDone(result) }
        }
    }

    private fun removeChatNow(sessionId: String) {
        runCatching {
            store.timeline(sessionId).filterIsInstance<com.pocketnode.core.Msg.User>().forEach { m ->
                val p = com.pocketnode.core.Attachments.parse(m.text)
                p.photos.forEach { photoFiles.delete(it) }
                p.files.forEach { attachedFiles.delete(it) }
            }
            store.deleteSession(sessionId)
        }
    }

    /** Removes a private chat without a record of its text; Activity only notes that a private chat was closed. */
    fun discardChat(sessionId: String, onDone: () -> Unit = {}) = io.execute {
        started.await()
        removeChatNow(sessionId)
        main.post(onDone)
    }

    /** Opens the share sheet with one chat as plain text. Photos and files are not included. The owner chooses where it goes. */
    fun shareChat(sessionId: String, title: String) = io.execute {
        started.await()
        val text = com.pocketnode.core.DataExport.chatText(com.pocketnode.core.ExportChat(sessionId, title, 0L, store.timeline(sessionId)), System.currentTimeMillis())
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Chat shared", "One chat was put on the share sheet as text, at the owner's request. Where it went is up to the app the owner chose.")
        main.post {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, title.ifBlank { "Pocketnode chat" }).putExtra(android.content.Intent.EXTRA_TEXT, text)
            appContext.startActivity(android.content.Intent.createChooser(send, "Share chat").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var autoDelete: com.pocketnode.core.AutoDelete

    /** "Delete old chats": the chosen number of days, 0 for never. Kept as state so the Settings row redraws. */
    var autoDeleteDays by mutableStateOf(0)
        private set

    /** Says how many chats a choice would delete right now, so the owner knows before agreeing. */
    fun oldChatCount(days: Int, onResult: (Int) -> Unit) = reads.execute {
        storeReady.await()
        val n = autoDelete.expired(store.sessions(), System.currentTimeMillis(), days).size
        main.post { onResult(n) }
    }

    fun chooseAutoDelete(days: Int) {
        autoDeleteDays = days
        io.execute {
            started.await(); autoDelete.days = days
            activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Delete old chats", "Set to ${com.pocketnode.core.AutoDelete.describe(days)}.")
            sweepOldChatsNow()
        }
    }

    /** Deletes chats that have gone unused for the chosen time (pinned ones stay). Runs when the app starts and when it comes back. */
    fun sweepOldChats() { if (::autoDelete.isInitialized && autoDeleteDays > 0) io.execute { started.await(); sweepOldChatsNow() } }

    private fun sweepOldChatsNow() {
        val old = autoDelete.expired(store.sessions(), System.currentTimeMillis())
        if (old.isEmpty()) return
        old.forEach { removeChatNow(it.id) }
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Old chats deleted", "${old.size} ${if (old.size == 1) "chat" else "chats"} not used for ${autoDelete.days} days ${if (old.size == 1) "was" else "were"} deleted, with ${if (old.size == 1) "its" else "their"} photos and attached files.")
        main.post { AppModel.refreshChats() }
    }

    /** Removes one conversation and the photos and attached files it holds. Files the assistant made stay in Files. */
    fun deleteChat(sessionId: String, onDone: () -> Unit) = io.execute {
        started.await()
        removeChatNow(sessionId)
        activity.add(com.pocketnode.core.ActivityKind.SYSTEM, "Chat deleted", "One chat was deleted by the owner, with its photos and attached files.")
        main.post(onDone)
    }

    fun renameChat(sessionId: String, title: String, onDone: () -> Unit) = io.execute { started.await(); store.setSessionTitle(sessionId, title.trim().take(60)); main.post(onDone) }

    fun pinChat(sessionId: String, pinned: Boolean, onDone: () -> Unit) = io.execute { started.await(); store.setPinned(sessionId, pinned); main.post(onDone) }

    fun searchChats(query: String, onResult: (List<com.pocketnode.core.SessionHit>) -> Unit) = reads.execute { storeReady.await(); val r = store.searchSessions(query); main.post { onResult(r) } }

    fun deleteAllConversations(onDone: () -> Unit) = io.execute { store.deleteAllSessions(); main.post(onDone) }

    // ---- Device --------------------------------------------------------------------------

    private fun tier(): Budget {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gb = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024.0 * 1024 * 1024)
        return when { gb >= 7 -> Budget.TierA; gb >= 3.5 -> Budget.TierB; else -> Budget.TierC }
    }

    /** Chosen once from the phone's memory; cheap enough to read while drawing. */
    val tierName: String by lazy {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val gb = ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024.0 * 1024 * 1024)
        when { gb >= 7 -> "Full"; gb >= 3.5 -> "Balanced"; else -> "Light" }
    }

    private const val K_PROVIDER = "model_provider"
    private const val K_ANTHROPIC_KEY = "anthropic_api_key"
    private const val K_ANTHROPIC_MODEL = "anthropic_model"
    private const val K_OLLAMA_URL = "ollama_url"
    private const val K_OLLAMA_MODEL = "ollama_model"
    private const val K_OPENAI_URL = "openai_url"
    private const val K_OPENAI_KEY = "openai_key"
    private const val K_OPENAI_MODEL = "openai_model"
    private const val K_TELEGRAM_TOKEN = "telegram_bot_token"
    private const val K_TELEGRAM_NAME = "telegram_bot_name"
    private const val K_TELEGRAM_MODE = "telegram_mode"
    private const val K_RELAY_LINK = "relay_link"
    private const val K_WA_LINK = "wa_relay_link"
    private const val K_MAIL = "mail_account"
    private const val K_WA_NUMBER = "wa_number"
    private const val K_NODE_SEED = "node_seed"
    private const val K_NODE_CREDS = "node_relay"
    private const val K_PUSH_TOKEN = "push_token"
    private const val DEFAULT_OLLAMA_MODEL = "llama3.1:8b-instruct-q4_K_M"
}
