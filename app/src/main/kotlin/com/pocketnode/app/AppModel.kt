package com.pocketnode.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketnode.app.ui.Glyph
import com.pocketnode.app.ui.ThemeMode
import com.pocketnode.core.Entitlement
import com.pocketnode.core.EntitlementStore
import com.pocketnode.core.Msg

sealed interface Route {
    data object Welcome : Route
    data object SetupModel : Route
    data object SetupFinish : Route
    data object Main : Route
    data class Thread(val sessionId: String) : Route
    data object ModelSettings : Route
    data object Profile : Route
    data object Settings : Route
    data object Appearance : Route
    data class Pack(val id: String) : Route
    data object MailAccount : Route
    data object Reminders : Route
    data object Activity : Route
    data object Briefing : Route
    data object ConnectedApps : Route
    data object BankAlerts : Route
    data class Agent(val id: String) : Route
    /** The drag-and-connect agent builder; a null id starts a new agent. */
    data class AgentBuilder(val id: String?) : Route
    data class AgentCanvas(val id: String?) : Route
    data object Call : Route
    data object Calls : Route
}

data class ChatRow(
    val id: String,
    val title: String,
    val preview: String,
    val activityMillis: Long,
    val glyph: Glyph,
    val fromUser: Boolean,
    val pinned: Boolean = false,
) {
    val readOnly: Boolean get() = id.startsWith("telegram:")
    val isCall: Boolean get() = id.startsWith("call:")
}

/** UI state: navigation, preferences, and the conversation data the screens show. */
object AppModel {
    const val LOCAL_SESSION = "local"
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appContext: Context
    private lateinit var entitlementStore: EntitlementStore

    val stack = mutableStateListOf<Route>()
    var forward by mutableStateOf(true)
        private set
    var tab by mutableIntStateOf(0)
    var onboarded by mutableStateOf(false)
        private set
    var theme by mutableStateOf(ThemeMode.System)
        private set

    var entitlement by mutableStateOf(Entitlement(com.pocketnode.core.Plan.None))
        private set

    /** False until the first list of chats has been read, so a screen never claims "No conversations" while it is still loading. */
    var chatsLoaded by mutableStateOf(false)
        private set
    var chats by mutableStateOf<List<ChatRow>>(emptyList())
        private set
    var openSession by mutableStateOf<String?>(null)
        private set
    var messages by mutableStateOf<List<Msg>>(emptyList())
        private set
    var busy by mutableStateOf<Set<String>>(emptySet())
        private set

    fun init(context: Context) {
        if (stack.isNotEmpty()) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        entitlementStore = PrefsEntitlementStore(prefs)
        entitlement = entitlementStore.load()
        onboarded = prefs.getBoolean("onboarded", false)
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System)
        appLock = prefs.getBoolean("app_lock", false)
        blockScreenshots = prefs.getBoolean("block_screenshots", false)
        locked = com.pocketnode.core.AppLockPolicy.lockedAtStart(appLock, onboarded) // a fresh start always asks
        lockLog("start: appLock=$appLock locked=$locked")
        stack.add(if (onboarded) Route.Main else Route.Welcome)
        // A private chat left over from a run that was cut short (the app was closed from the recents list) is removed by CoreHost's start-up, before any unfinished request can be resumed.
    }

    // ---- Private chats ---------------------------------------------------------------------

    /** Chats the owner marked private. Each is deleted, with its photos and files, as soon as the owner leaves it and no answer is being written. */
    private var privateChats by mutableStateOf<Set<String>>(emptySet())

    fun isPrivate(id: String) = id in privateChats

    /** Marks the open, empty chat as private. */
    fun makePrivate() {
        val id = openSession ?: return
        if (!id.startsWith("local:")) return
        privateChats = privateChats + id
        prefs.edit().putStringSet("private_chats", privateChats).apply()
    }

    private fun dropIfPrivate(id: String) {
        if (id !in privateChats || id in busy) return
        privateChats = privateChats - id
        prefs.edit().putStringSet("private_chats", privateChats).apply()
        CoreHost.discardChat(id) { refreshChats() }
    }

    // ---- App lock and screenshots ----------------------------------------------------------

    /** When on, the app asks for the phone's screen lock when it opens and after [com.pocketnode.core.AppLockPolicy.GRACE_MILLIS] away. */
    var appLock by mutableStateOf(false)
        private set
    var blockScreenshots by mutableStateOf(false)
        private set
    /** True while the lock screen covers the app. */
    var locked by mutableStateOf(false)
    private var leftAt = 0L
    /** Set by the main activity: applies or removes the window flag that blocks screenshots and the recent-apps preview. */
    var onSecureChange: ((Boolean) -> Unit)? = null

    fun leftApp() { leftAt = android.os.SystemClock.elapsedRealtime() }

    fun returnedToApp(now: Long = android.os.SystemClock.elapsedRealtime()) {
        if (com.pocketnode.core.AppLockPolicy.shouldLockOnReturn(appLock, onboarded, locked, leftAt, now)) locked = true
        CoreHost.sweepOldChats()
        lockLog("returned after ${(now - leftAt) / 1000}s: locked=$locked")
    }

    /** Debug builds only: says what the lock decided, never anything about the credential. */
    private fun lockLog(message: String) { if (BuildConfig.DEBUG) android.util.Log.d("Pocketnode", "app lock: $message") }

    /** Shows the phone's own screen lock check. A pass opens the app. */
    fun requestUnlock() {
        val ask = ScreenLock.confirm ?: return
        lockLog("asking the phone")
        ask { ok -> lockLog("the phone said ${if (ok) "yes" else "no"}"); if (ok) locked = false }
    }

    /** Switching on needs one pass of the phone's screen lock, so a lock that cannot be opened is never set. [note] gets a sentence to show when it does not happen. */
    fun toggleAppLock(on: Boolean, note: (String?) -> Unit) {
        if (!on) { prefs.edit().putBoolean("app_lock", false).apply(); appLock = false; locked = false; note(null); return }
        if (!ScreenLock.isSecure()) { note("Set a screen lock (a PIN, pattern or password) in your phone's settings first, then switch this on."); return }
        val ask = ScreenLock.confirm ?: run { note("Open Pocketnode on the screen to switch this on."); return }
        ask { ok ->
            if (ok) { prefs.edit().putBoolean("app_lock", true).apply(); appLock = true; note("On. The app will ask for your screen lock when you open it after 30 seconds away.") }
            else note("Not switched on, because your screen lock was not confirmed.")
        }
    }

    fun blockScreenshotsOn(on: Boolean) {
        prefs.edit().putBoolean("block_screenshots", on).apply()
        blockScreenshots = on
        onSecureChange?.invoke(on)
    }

    // ---- Plan (placeholder: payments are not connected yet) -------------------------------

    /** Starts the free trial if no plan exists. Payment providers will replace this later. */
    fun startTrial() {
        if (entitlement.plan != com.pocketnode.core.Plan.None) return
        val e = Entitlement.startTrial(System.currentTimeMillis())
        entitlementStore.save(e)
        entitlement = e
    }

    fun planSummary(): String = "Free"

    // ---- Navigation ----------------------------------------------------------------------

    val current: Route get() = stack.last()

    /** A screen of a feature that this build has switched off cannot be opened, whoever asks. */
    private fun switchedOff(r: Route) = when (r) {
        Route.Call, Route.Calls -> !AppFeatures.on("voice_call")
        Route.BankAlerts -> !AppFeatures.on("bank_alerts")
        is Route.Agent, is Route.AgentBuilder, is Route.AgentCanvas -> !AppFeatures.on("agents")
        else -> false
    }

    fun push(route: Route) { if (switchedOff(route)) return; forward = true; stack.add(route) }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        val removed = stack.removeAt(stack.lastIndex)
        if (removed is Route.Thread) { openSession = null; messages = emptyList(); dropIfPrivate(removed.sessionId) }
        // Going back onto another chat (possible when chats were opened one over another) must load that chat, not show it empty.
        (current as? Route.Thread)?.let { openSession = it.sessionId; messages = emptyList(); refreshThread() }
        return true
    }

    /** True right after first-time setup, until the tour has been offered. */
    var tourPending by mutableStateOf(false)
        private set

    /** Records that the owner has been through (or skipped) the tour. */
    fun tourDone() { tourPending = false; prefs.edit().putBoolean("tour_seen", true).apply() }

    fun finishOnboarding() {
        onboarded = true
        if (!prefs.getBoolean("tour_seen", false)) tourPending = true
        prefs.edit().putBoolean("onboarded", true).apply()
        CoreService.start(appContext)
        forward = true
        stack.clear()
        stack.add(Route.Main)
    }

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        // The model can be asked from a service or a broadcast before the screens have started; save through the context then.
        (if (::prefs.isInitialized) prefs else CoreHost.appContextOrNull()?.getSharedPreferences("app", Context.MODE_PRIVATE))?.edit()?.putString("theme", mode.name)?.apply()
    }

    // ---- Conversations -------------------------------------------------------------------

    fun refreshChats() = CoreHost.loadSessions { list ->
        val rows = list.map { s ->
            val telegram = s.id.startsWith("telegram:")
            val whatsapp = s.id.startsWith("whatsapp:")
            ChatRow(
                id = s.id,
                title = s.title.ifBlank { if (telegram) "Telegram chat" else if (whatsapp) "WhatsApp chat" else "New chat" },
                preview = com.pocketnode.core.MarkdownLite.parse(com.pocketnode.core.MarkdownLite.hideInternalLinks(com.pocketnode.core.Attachments.preview(s.lastText))).joinToString("") { it.text }.replace('\n', ' '),
                activityMillis = s.lastActivityMillis,
                glyph = if (telegram) Glyph.Plane else if (whatsapp) Glyph.Chat else if (s.id.startsWith("call:")) Glyph.Voice else Glyph.Phone,
                fromUser = s.lastIsUser,
                pinned = s.pinned,
            )
        }
        chats = rows
        chatsLoaded = true
    }

    fun openThread(sessionId: String) {
        openSession = sessionId
        messages = emptyList()
        push(Route.Thread(sessionId))
        refreshThread()
    }

    /** Starts a conversation that has no messages yet. It appears in the list after the first message. */
    fun newChat() {
        val id = "local:" + System.currentTimeMillis().toString(36)
        openThread(id)
    }

    /** Opens one chat from a notification, over whatever was on screen. */
    fun openChatFromOutside(sessionId: String) {
        if (!onboarded) return
        stack.clear(); stack.add(Route.Main)
        openThread(sessionId)
    }

    /** Opens a new, empty chat over whatever was on screen, for the quick settings tile. */
    fun openNewChatFromOutside() {
        if (!onboarded) return
        stack.clear(); stack.add(Route.Main)
        newChat()
    }

    fun refreshThread() {
        val id = openSession ?: return
        CoreHost.loadHistory(id) { history ->
            // While a reply is pending the screen already shows the user's message; keep it if the store is behind.
            // A briefing is asked for with a long instruction; the chat shows it as a short label.
            val shown = if (id.startsWith("briefing:")) history.map { if (it is Msg.User && it.text.startsWith("Give me my morning briefing")) Msg.User("Morning briefing") else it } else history
            if (openSession == id && (history.size >= messages.size || id !in busy)) messages = shown
        }
    }

    /** Photos and files waiting to go with the next message. */
    var attachments by mutableStateOf<List<Attached>>(emptyList())
        private set

    /** Something to tell the owner about the last attach, such as a file that cannot be read. */
    var attachNotice by mutableStateOf<String?>(null)
        private set

    /** True while the camera or file picker is open or a file is being read. */
    var attaching by mutableStateOf(false)
        private set

    fun attachCamera() {
        if (attaching) return
        attaching = true; attachNotice = null
        CoreHost.attachFromCamera { items, note -> attaching = false; attachments = attachments + items; attachNotice = note }
    }

    fun attachFiles() {
        if (attaching) return
        attaching = true; attachNotice = null
        CoreHost.attachFromFiles { items, note -> attaching = false; attachments = attachments + items; attachNotice = note }
    }

    /** Words shared from another app, waiting to be put in the message box of the open chat. */
    var draft by mutableStateOf("")
        private set

    /** Listens through the phone's speech service and adds the words to the message box. */
    fun listen() {
        val pick = CoreHost.speechPicker ?: run { attachNotice = "Open the app to use the microphone."; return }
        attachNotice = null
        pick { words ->
            if (words.isNullOrBlank()) attachNotice = "Nothing was heard, or this phone has no speech recognition app."
            else draft = words
        }
    }

    /** Assistant entry (home-button gesture or the Talk shortcut): a new chat, the phone listens, and what was said is sent as the first message. */
    fun talkToAssistant() {
        if (!onboarded || !AppFeatures.on("voice_talk")) return
        openNewChatFromOutside()
        val pick = CoreHost.speechPicker ?: return
        pick { words -> if (!words.isNullOrBlank()) send(words) else attachNotice = "Nothing was heard, or this phone has no speech recognition app." }
    }

    fun takeDraft(): String { val d = draft; draft = ""; return d }

    /**
     * Something shared to Pocketnode from another app. It opens a new chat with the text in the message box and any photos or files
     * attached, but sends nothing: the owner says what to do with it and presses send.
     */
    fun receiveShare(text: String?, uris: List<android.net.Uri>) {
        if (!onboarded) return
        if (stack.size > 1 && stack.last() is Route.Thread && messages.isEmpty() && attachments.isEmpty()) { /* reuse the empty chat that is already open */ } else {
            stack.clear(); stack.add(Route.Main); newChat()
        }
        attachments = emptyList(); attachNotice = null
        draft = text?.trim().orEmpty().take(8_000)
        if (uris.isNotEmpty()) {
            attaching = true
            CoreHost.attachFromUris(uris) { items, note -> attaching = false; attachments = attachments + items; attachNotice = note }
        }
    }

    fun addAttachments(items: List<Attached>) { attachments = attachments + items }

    fun removeAttachment(ref: String) { attachments = attachments.filterNot { it.ref == ref } }

    fun send(text: String) {
        val id = openSession ?: return
        val typed = text.trim()
        if ((typed.isEmpty() && attachments.isEmpty()) || id in busy) return
        // The message carries a plain reference for each attachment; the screen and the models read them from there.
        val clean = (listOf(typed).filter { it.isNotEmpty() } + attachments.map { it.ref }).joinToString("\n")
        attachments = emptyList(); attachNotice = null
        sendRaw(id, clean)
    }

    /** Puts an earlier message back in the message box, with the photos and files that are still stored, to change and send again. */
    fun editAndResend(m: Msg.User) {
        val p = com.pocketnode.core.Attachments.parse(m.text)
        draft = p.text
        attachments = p.photos.filter { com.pocketnode.core.Images.source(it) != null }.map { Attached(it, "Photo", true) } +
            p.files.mapNotNull { ref -> com.pocketnode.core.Attachments.fileSource(ref)?.let { Attached(ref, it.name, false) } }
        attachNotice = if (p.photos.size + p.files.size > attachments.size) "Some of the photos or files in that message are no longer stored, so they were not put back." else null
    }

    /** Asks the same question again, as a new message, so the assistant answers it afresh. */
    fun askAgain() {
        val id = openSession ?: return
        if (id in busy) return
        val last = messages.lastOrNull { it is Msg.User } as? Msg.User ?: return
        sendRaw(id, last.text)
    }

    private fun sendRaw(id: String, clean: String) {
        messages = messages + Msg.User(clean)
        busy = busy + id
        CoreHost.send(id, clean) {
            busy = busy - id
            if (openSession != id) dropIfPrivate(id)
            refreshChats()
            refreshThread()
        }
    }

    /** Tries the last reply again after a model error. The error line leaves the screen at once. */
    fun retry() {
        val id = openSession ?: return
        if (id in busy) return
        val last = messages.lastOrNull()
        if (last !is Msg.Assistant || !last.text.startsWith(com.pocketnode.core.MODEL_ERROR)) return
        messages = messages.dropLast(1)
        busy = busy + id
        CoreHost.retry(id) {
            busy = busy - id
            refreshChats()
            refreshThread()
        }
    }

    /** Tries again, by itself, chats that were waiting for a connection. The error line leaves the screen of the open chat. */
    fun resumeWaiting(ids: List<String>) {
        for (id in ids) {
            if (id in busy) continue
            if (id == openSession && (messages.lastOrNull() as? Msg.Assistant)?.text?.startsWith(com.pocketnode.core.MODEL_ERROR) == true) messages = messages.dropLast(1)
            busy = busy + id
            CoreHost.retry(id) { busy = busy - id; refreshChats(); refreshThread() }
        }
    }

    /** Erases the owner's data (chats, files, reminders, notes, Activity) and empties the screens that show it. */
    fun eraseData(done: (String) -> Unit) = CoreHost.eraseData { message ->
        messages = emptyList(); chats = emptyList(); openSession = null
        refreshChats()
        done(message)
    }

    // ---- One chat: delete, rename, pin, search --------------------------------------------

    fun deleteChat(sessionId: String) = CoreHost.deleteChat(sessionId) {
        if (openSession == sessionId) { openSession = null; messages = emptyList(); if (current is Route.Thread) pop() }
        refreshChats(); runSearch()
    }

    fun renameChat(sessionId: String, title: String) = CoreHost.renameChat(sessionId, title) { refreshChats(); runSearch() }

    fun pinChat(sessionId: String, pinned: Boolean) = CoreHost.pinChat(sessionId, pinned) { refreshChats(); runSearch() }

    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<ChatRow>>(emptyList())

    fun closeSearch() { searchOpen = false; searchQuery = ""; searchHits = emptyList() }

    /** Looks for the typed words in titles and in every question and answer. Shows the matching chats with the words around the match. */
    fun runSearch() {
        val q = searchQuery.trim()
        if (!searchOpen || q.isEmpty()) { searchHits = emptyList(); return }
        CoreHost.searchChats(q) { hits ->
            if (searchQuery.trim() == q) searchHits = hits.map { h ->
                val s = h.session
                val telegram = s.id.startsWith("telegram:"); val whatsapp = s.id.startsWith("whatsapp:")
                ChatRow(s.id, s.title.ifBlank { if (telegram) "Telegram chat" else if (whatsapp) "WhatsApp chat" else "New chat" }, h.snippet, s.lastActivityMillis,
                    if (telegram) Glyph.Plane else if (whatsapp) Glyph.Chat else Glyph.Phone, s.lastIsUser, s.pinned)
            }
        }
    }

    fun clearConversations() = CoreHost.deleteAllConversations {
        messages = emptyList()
        chats = emptyList()
        refreshChats()
    }
}

/** Keeps the entitlement in the app's preferences. A payment provider will write through the same store. */
class PrefsEntitlementStore(private val prefs: android.content.SharedPreferences) : EntitlementStore {
    override fun load(): Entitlement {
        val plan = runCatching { com.pocketnode.core.Plan.valueOf(prefs.getString("plan", "None")!!) }.getOrDefault(com.pocketnode.core.Plan.None)
        val end = prefs.getLong("plan_end", -1L)
        return Entitlement(plan, prefs.getLong("plan_start", 0L), if (end < 0) null else end)
    }

    override fun save(entitlement: Entitlement) {
        prefs.edit().putString("plan", entitlement.plan.name).putLong("plan_start", entitlement.startedAtMillis)
            .putLong("plan_end", entitlement.endsAtMillis ?: -1L).apply()
    }
}
