package com.pocketnode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.pocketnode.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_SESSION = "open_session"; const val EXTRA_NEW_CHAT = "new_chat"; const val EXTRA_TALK = "talk" }

    private var pendingPermission: ((Map<String, Boolean>) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermission?.invoke(result)
        pendingPermission = null
    }

    private var pendingFiles: ((List<android.net.Uri>) -> Unit)? = null
    private val fileLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingFiles?.invoke(uris)
        pendingFiles = null
    }

    private var pendingSave: ((android.net.Uri?) -> Unit)? = null
    private val saveLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        pendingSave?.invoke(if (r.resultCode == RESULT_OK) r.data?.data else null)
        pendingSave = null
    }

    private var pendingUnlock: ((Boolean) -> Unit)? = null
    private val unlockLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        pendingUnlock?.invoke(r.resultCode == RESULT_OK)
        pendingUnlock = null
    }

    private var pendingSpeech: ((String?) -> Unit)? = null
    private val speechLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val words = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        pendingSpeech?.invoke(words)
        pendingSpeech = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CoreHost.init(this)
        // A feature this build has switched off also loses its entry points in the phone's own settings (the quick tile, the notification access list).
        for ((cls, id) in listOf(TalkTile::class.java to "voice_talk", BankAlertListener::class.java to "bank_alerts"))
            packageManager.setComponentEnabledSetting(android.content.ComponentName(this, cls), if (AppFeatures.on(id)) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        CoreHost.permissionRequester = { permissions, done -> pendingPermission = done; permissionLauncher.launch(permissions) }
        CoreHost.filePicker = { types, done -> pendingFiles = done; fileLauncher.launch(types) }
        CoreHost.fileSaver = { name, mime, done ->
            pendingSave = done
            val ask = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType(mime).putExtra(android.content.Intent.EXTRA_TITLE, name)
            try { saveLauncher.launch(ask) } catch (e: android.content.ActivityNotFoundException) { pendingSave = null; done(null) }
        }
        // The phone's own speech service listens (it may send the audio to its provider, as the keyboard's dictation does); Pocketnode only receives the words it hears.
        CoreHost.speechPicker = { done ->
            pendingSpeech = done
            val ask = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak to your assistant")
            try { speechLauncher.launch(ask) } catch (e: android.content.ActivityNotFoundException) { pendingSpeech = null; done(null) }
        }
        AppModel.init(this)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        ScreenLock.isSecure = { keyguard.isDeviceSecure }
        ScreenLock.confirm = { done ->
            // The phone's own screen asks; this app only hears yes or no. With no lock set there is nothing to ask, so it passes.
            @Suppress("DEPRECATION")
            val ask = keyguard.createConfirmDeviceCredentialIntent("Pocketnode", "Confirm it is you to open the app.")
            if (ask == null) done(true) else { pendingUnlock = done; unlockLauncher.launch(ask) }
        }
        AppModel.onSecureChange = { on -> if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        if (AppModel.blockScreenshots) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        // The service keeps the assistant alive in the background once setup is done.
        if (AppModel.onboarded) CoreService.start(this)
        setContent { AppRoot() }
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** A share from another app: text goes into the message box, photos and files are attached. Nothing is sent until the owner presses send. */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_SESSION)?.let { id ->
            // A notification (the morning briefing) asked to open one chat.
            intent.removeExtra(EXTRA_SESSION)
            AppModel.openChatFromOutside(id)
            return
        }
        if (intent.getBooleanExtra(EXTRA_NEW_CHAT, false)) {
            // The quick settings tile asked for a new chat.
            intent.removeExtra(EXTRA_NEW_CHAT)
            AppModel.openNewChatFromOutside()
            return
        }
        val action = intent.action
        if (action == android.content.Intent.ACTION_ASSIST || action == android.content.Intent.ACTION_VOICE_COMMAND || intent.getBooleanExtra(EXTRA_TALK, false)) {
            intent.removeExtra(EXTRA_TALK); intent.action = null
            AppModel.talkToAssistant()
            return
        }
        if (action != android.content.Intent.ACTION_SEND && action != android.content.Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        val uris = if (action == android.content.Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))
        if (text.isNullOrBlank() && uris.isEmpty()) return
        // The sender's permission to read the file lasts only as long as this intent, so read it now.
        intent.action = null
        AppModel.receiveShare(text, uris)
    }

    override fun onStart() { super.onStart(); AppModel.returnedToApp() }

    override fun onStop() { AppModel.leftApp(); super.onStop() }

    override fun onDestroy() {
        ScreenLock.confirm = null
        AppModel.onSecureChange = null
        CoreHost.permissionRequester = null
        CoreHost.filePicker = null
        CoreHost.fileSaver = null
        CoreHost.speechPicker = null
        super.onDestroy()
    }
}
