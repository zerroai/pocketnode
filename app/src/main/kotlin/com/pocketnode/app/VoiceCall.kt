package com.pocketnode.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketnode.core.MODEL_ERROR
import com.pocketnode.core.RunOutcome
import com.pocketnode.core.SpokenText
import java.util.Locale

/**
 * A voice call with the assistant: it listens, you stop talking, it thinks, it answers out loud, and it listens again, with no screen
 * taps in between. Every turn goes through the same chat path as typed messages (own routing, tools, approvals, usage limit), and the call is
 * kept as a chat named by its time (session ids start with `call:`), listed under Profile, Calls. The phone's own speech recogniser hears you
 * (it may send the audio to its provider, like the keyboard's dictation) and a speech voice on the phone speaks. Only while the app is on
 * screen: Android does not let a hidden app keep the microphone open.
 *
 * Speed: plain conversation skips the step that decides on a tool (see `ToolSelector.confident`), and the answer is spoken sentence by
 * sentence while the model is still writing it.
 *
 * Interrupting: speak over it, or tap the circle. While it talks the recogniser stays open; words that only repeat what the assistant is
 * saying are ignored (the microphone can hear the loudspeaker), anything else stops the voice and is taken as your next words.
 */
object VoiceCall {
    enum class Phase { Off, Listening, Thinking, Speaking }

    var phase by mutableStateOf(Phase.Off)
        private set
    /** How loud you are, 0 to 1, for the moving circle. */
    var level by mutableFloatStateOf(0f)
        private set
    /** Your words as they are recognised. */
    var heard by mutableStateOf("")
        private set
    /** The sentence being spoken. */
    var said by mutableStateOf("")
        private set
    var muted by mutableStateOf(false)
        private set
    /** A short plain line for the screen: what is wrong or what it is waiting for. */
    var note by mutableStateOf("")
        private set
    var startedAt by mutableStateOf(0L)
        private set
    /** What is kept when a call ends: the words, as text on this phone (the default), or nothing at all. The sound is never recorded. */
    enum class Keep { Words, Nothing }
    var keep by mutableStateOf(Keep.Words)
        private set
    fun loadKeep(ctx: Context) { keep = if (ctx.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE).getString("call_keep", "words") == "nothing") Keep.Nothing else Keep.Words }
    fun chooseKeep(ctx: Context, k: Keep) { keep = k; ctx.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("call_keep", if (k == Keep.Nothing) "nothing" else "words").apply() }
    /** True when a finished call must not stay on the phone. Used again after a late reply, which would otherwise bring the chat back. */
    fun forgets(id: String) = id.startsWith("call:") && !active && keep == Keep.Nothing

    /** The chat that holds this call, for the transcript. */
    var sessionId by mutableStateOf<String?>(null)
        private set
    /** True once the phone's recogniser is open and listening (it beeps then); before that the screen says it is getting ready. */
    var ready by mutableStateOf(false)
        private set

    /** True while a call is on; the system prompt asks for short spoken answers. */
    @Volatile var active = false
        private set

    private const val GOOGLE_TTS = "com.google.android.tts"
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var recognizer: SpeechRecognizer? = null
    private var recognizing = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: Any? = null
    private var generation = 0          // bumps when a turn is cancelled, so a late reply is ignored
    private var errors = 0
    private var smooth = 0f
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false

    // One answer being spoken.
    private val spoken = mutableListOf<String>()      // sentences handed to the voice so far
    private var finished = 0                           // sentences the voice has finished
    private var replyComplete = false
    private var tSubmit = 0L
    private var speechEndedAt = 0L
    private var listenAskedAt = 0L
    private var queuedInVoice = 0                     // sentences already given to the voice

    private fun mark(what: String) { android.util.Log.i("Pocketnode", "call timing: $what at +${System.currentTimeMillis() - tSubmit} ms") }

    fun start(context: Context) {
        if (active) return
        if (!AppFeatures.on("voice_call")) { note = "Voice calls are not part of this version of the app."; return }
        loadKeep(context)
        val ctx = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { note = "This phone has no speech recogniser."; return }
        if (!CoreHost.hasModel()) { note = "Connect a model first, in Settings."; return }
        app = ctx
        note = ""
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin(ctx)
        else CoreHost.permissionRequester?.invoke(arrayOf(Manifest.permission.RECORD_AUDIO)) { granted ->
            main.post { if (granted[Manifest.permission.RECORD_AUDIO] == true) begin(ctx) else note = "The microphone is off for Pocketnode, so it cannot hear you." }
        } ?: run { note = "Allow the microphone for Pocketnode in the phone's settings." }
    }

    private fun begin(ctx: Context) {
        if (active) return
        active = true
        generation++
        errors = 0; heard = ""; said = ""; muted = false; level = 0f
        sessionId = "call:" + System.currentTimeMillis().toString(36)
        startedAt = System.currentTimeMillis()
        requestFocus(ctx)
        callAudio(true)
        // Listening starts at once; the speaking voice takes a few seconds to wake up, so it starts beside it.
        listen()
        startVoice(ctx, preferred = if (installed(ctx, GOOGLE_TTS)) GOOGLE_TTS else null)
    }

    /** The speaking voice. Google's own engine sounds far less robotic than the maker's default when it is on the phone. */
    private fun startVoice(ctx: Context, preferred: String?) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(ctx, { status ->
            main.post {
                if (!active) { runCatching { engine?.shutdown() }; return@post }
                if (status != TextToSpeech.SUCCESS) {
                    runCatching { engine?.shutdown() }
                    if (preferred != null) { startVoice(ctx, null); return@post }
                    note = "This phone has no voice to speak with."; end(); return@post
                }
                val e = engine ?: return@post
                tts = e; ttsReady = true
                chooseVoice(e)
                e.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                e.setOnUtteranceProgressListener(speaking)
                android.util.Log.i("Pocketnode", "call: voice ready ${System.currentTimeMillis() - startedAt} ms after the call began")
                // An answer that came before the voice was ready is spoken now.
                val waiting = spoken.drop(finished + queuedInVoice)
                queuedInVoice = spoken.size
                waiting.forEachIndexed { i, t -> e.speak(t, TextToSpeech.QUEUE_ADD, null, "call-$generation-${finished + i}") }
            }
        }, preferred)
    }

    /** The best installed voice for the phone's language: same country first, then higher quality. */
    private fun chooseVoice(engine: TextToSpeech) {
        val here = Locale.getDefault()
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == here.language && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val best: Voice? = voices.sortedWith(compareByDescending<Voice> { if (it.locale.country == here.country) 1 else 0 }.thenByDescending { it.quality }.thenBy { it.latency }).firstOrNull()
        android.util.Log.i("Pocketnode", "call voice: engine=${engine.defaultEngine} chosen=${best?.name} q=${best?.quality} net=${best?.isNetworkConnectionRequired} of ${voices.size}: ${voices.map { it.name + ":" + it.quality }.take(24)}")
        if (best != null) runCatching { engine.voice = best }
    }

    fun end() {
        if (!active && phase == Phase.Off) return
        val endedId = sessionId
        active = false
        generation++
        phase = Phase.Off; level = 0f; heard = ""; said = ""
        runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null; recognizing = false
        runCatching { tts?.stop(); tts?.shutdown() }; tts = null; ttsReady = false
        spoken.clear(); finished = 0
        callAudio(false)
        abandonFocus()
        if (endedId != null && forgets(endedId)) CoreHost.deleteChat(endedId) { AppModel.refreshChats() }
    }

    fun toggleMute() {
        muted = !muted
        if (muted) { runCatching { recognizer?.cancel() }; recognizing = false; if (phase == Phase.Listening) level = 0f }
        else if (phase == Phase.Listening) listen() else if (phase == Phase.Speaking) listenWhileSpeaking()
    }

    /** Tap on the circle: stop talking and listen. */
    fun interrupt() {
        if (!active || phase != Phase.Speaking) return
        turnWords = ""
        takeTurn()
        runCatching { recognizer?.cancel() }; recognizing = false
        listen()
    }

    /** Stops the voice and drops what was still to be said. */
    private fun takeTurn() {
        turnWords = ""
        generation++
        runCatching { tts?.stop() }
        spoken.clear(); finished = 0; queuedInVoice = 0; replyComplete = false; said = ""
    }

    // ---- Listening ----------------------------------------------------------------------------

    private fun listen() {
        if (!active) return
        // A restart of the recogniser (it ends after every quiet second or so) must not blink the screen: keep the circle as it is.
        if (phase != Phase.Listening) { phase = Phase.Listening; heard = ""; level = 0f; ready = false }
        if (muted) return
        startRecognizer()
    }

    private fun startRecognizer() {
        val ctx = app ?: return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(hearing); recognizer = it }
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        recognizing = true; listenAskedAt = System.currentTimeMillis()
        runCatching { r.startListening(ask) }.onFailure { recognizing = false; note = "Listening failed. Ending the call."; end() }
    }

    private val hearing = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { android.util.Log.i("Pocketnode", "call: recogniser ready ${System.currentTimeMillis() - listenAskedAt} ms after it was asked"); ready = true; if (phase == Phase.Listening) note = "" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            if (phase != Phase.Listening) return
            val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            smooth = smooth * 0.6f + target * 0.4f
            level = smooth
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (phase == Phase.Listening) level = 0f }
        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isBlank()) return
            when (phase) {
                Phase.Listening -> heard = t
                Phase.Thinking -> { android.util.Log.i("Pocketnode", "call: heard while thinking \"${t.take(50)}\""); heard = t }
                Phase.Speaking -> {
                    val own = echo(t)
                    android.util.Log.i("Pocketnode", "call: heard while speaking \"${t.take(50)}\" echo=$own")
                    // Speaking over the assistant: stop the voice at once and keep this same listening going as your next words.
                    if (!own) { takeTurn(); phase = Phase.Listening; heard = t; level = 0.5f }
                }
                else -> {}
            }
        }
        override fun onResults(results: Bundle?) {
            recognizing = false
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            errors = 0
            when (phase) {
                Phase.Listening -> when {
                    t.isBlank() -> listenLater(150)
                    // The tail of the assistant's own voice, heard just after it stopped, is not a new turn.
                    System.currentTimeMillis() - speechEndedAt < 6000 && echo(t) -> { android.util.Log.i("Pocketnode", "call: ignored its own voice \"${t.take(50)}\""); listenLater(150) }
                    else -> submit(t)
                }
                Phase.Speaking -> { if (t.isNotBlank() && !echo(t)) { takeTurn(); submit(t) } else keepListeningWhileSpeaking() }
                Phase.Thinking -> if (t.isNotBlank()) { android.util.Log.i("Pocketnode", "call: spoke over the thinking: \"${t.take(50)}\""); submit(t) } else main.postDelayed({ if (active && phase == Phase.Thinking && !recognizing && !muted) startRecognizer() }, 300)
                else -> {}
            }
        }
        override fun onError(error: Int) {
            recognizing = false
            android.util.Log.i("Pocketnode", "call: recogniser error $error in $phase")
            if (!active) return
            if (phase == Phase.Speaking) { keepListeningWhileSpeaking(); return }
            if (phase == Phase.Thinking) { main.postDelayed({ if (active && phase == Phase.Thinking && !recognizing && !muted) startRecognizer() }, 400); return }
            if (phase != Phase.Listening) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenLater(150)       // nobody spoke: keep waiting
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> { runCatching { recognizer?.cancel() }; listenLater(600) }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { note = "The microphone is off for Pocketnode."; end() }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    if (++errors >= 3) { note = "Cannot reach the phone's speech service. Check the connection."; end() } else { note = "Having trouble hearing you. Trying again."; listenLater(1200) }
                }
                else -> if (++errors >= 4) { note = "Listening stopped working. Ending the call."; end() } else listenLater(500)
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun listenLater(ms: Long) { val g = generation; main.postDelayed({ if (active && g == generation && phase == Phase.Listening && !recognizing) listen() }, ms) }

    // ---- Thinking -----------------------------------------------------------------------------

    private var turnWords = ""                     // what was said for the turn still being thought up

    private fun submit(said: String) {
        val id = sessionId ?: return
        // Speaking over a turn that is still being thought up adds to it: nothing said so far is lost.
        val words = if (phase == Phase.Thinking && turnWords.isNotBlank()) "$turnWords $said" else said
        turnWords = words
        // A turn still being thought up is dropped: its model request is cut so this one does not wait behind it.
        if (phase == Phase.Thinking) com.pocketnode.core.ModelCancel.cancel()
        // Stop this listening, then start a fresh one: speaking while it thinks drops this turn and starts the next.
        runCatching { recognizer?.cancel() }; recognizing = false
        heard = words; phase = Phase.Thinking; level = 0f; note = ""
        main.postDelayed({ if (active && phase == Phase.Thinking && !recognizing && !muted) startRecognizer() }, 250)
        spoken.clear(); finished = 0; queuedInVoice = 0; replyComplete = false
        val g = ++generation
        tSubmit = System.currentTimeMillis(); mark("words ready: \"${words.take(60)}\"")
        val title = "Call " + java.text.SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(java.util.Date(startedAt))
        CoreHost.send(id, words, title, onPartial = { text -> if (active && g == generation) speakFinished(text) }) { result ->
            main.post {
                if (!active || g != generation) return@post
                note = ""; turnWords = ""
                mark("reply received (${result.text.length} letters)")
                val reply = result.text
                if (result.outcome == RunOutcome.FAILED || reply.startsWith(MODEL_ERROR)) {
                    if (spoken.isEmpty()) { note = friendly(reply); enqueue("Sorry, I could not get an answer.") }
                } else {
                    SpokenText.sentences(reply).drop(spoken.size).forEach { enqueue(it) }
                    if (spoken.isEmpty()) enqueue("Done.")
                }
                replyComplete = true
                checkEnd()
            }
        }
    }

    private fun friendly(reply: String) = reply.removePrefix(MODEL_ERROR).take(120)

    // ---- Speaking -----------------------------------------------------------------------------

    /** The answer so far, while the model is still writing: hand each finished sentence to the voice. */
    private fun speakFinished(partial: String) {
        val done = SpokenText.finished(partial)
        if (done.size > spoken.size && spoken.isEmpty()) mark("first sentence ready")
        done.drop(spoken.size).forEach { enqueue(it) }
    }

    private fun enqueue(sentence: String) {
        val engine = tts
        if (phase != Phase.Speaking) {
            phase = Phase.Speaking
            // Keep the recogniser open while it talks, so speaking over it stops it.
            if (!muted) listenWhileSpeaking()
        }
        val index = spoken.size
        spoken += sentence
        if (engine == null || !ttsReady) return      // spoken as soon as the voice is ready
        queuedInVoice = spoken.size
        engine.speak(sentence, TextToSpeech.QUEUE_ADD, null, "call-$generation-$index")
    }

    private fun checkEnd() {
        if (!active || phase != Phase.Speaking || !replyComplete || finished < spoken.size) return
        said = ""
        speechEndedAt = System.currentTimeMillis()
        runCatching { recognizer?.cancel() }; recognizing = false
        listen()
    }

    private val speaking = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post {
                val (gen, index) = parse(utteranceId) ?: return@post
                if (!active || gen != generation) return@post
                android.util.Log.i("Pocketnode", "call: voice began sentence $index, ${spoken.getOrNull(index)?.length} letters")
                if (index == 0) mark("voice started")
                said = spoken.getOrNull(index).orEmpty()
            }
        }
        override fun onDone(utteranceId: String?) { advance(utteranceId) }
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { advance(utteranceId) }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        val p = id?.split("-") ?: return null
        return (p.getOrNull(1)?.toIntOrNull() ?: return null) to (p.getOrNull(2)?.toIntOrNull() ?: return null)
    }

    private fun advance(utteranceId: String?) {
        main.post {
            val (gen, _) = parse(utteranceId) ?: return@post
            if (!active || gen != generation) return@post
            finished++
            checkEnd()
        }
    }

    // ---- Speaking over the assistant -------------------------------------------------------------

    private fun listenWhileSpeaking() {
        if (!active || muted || recognizing) return
        startRecognizer()
    }

    private fun keepListeningWhileSpeaking() {
        val g = generation
        main.postDelayed({ if (active && g == generation && phase == Phase.Speaking) listenWhileSpeaking() }, 200)
    }

    /** True when the heard words are mostly the ones the assistant is saying (its own voice reaching the microphone). */
    private fun echo(heard: String): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9']+")).filter { it.length > 1 }
        val h = words(heard)
        if (h.isEmpty()) return true
        val said = spoken.flatMap { words(it) }.toSet()
        return h.count { it in said }.toFloat() / h.size >= 0.6f
    }

    // ---- Audio --------------------------------------------------------------------------------

    private fun installed(ctx: Context, pkg: String): Boolean = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** In a call the phone treats the microphone and the speaker the way it does in a phone call, which turns on its echo cancelling. */
    @Suppress("DEPRECATION")
    private fun callAudio(on: Boolean) {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (on) {
            savedMode = am.mode; savedSpeaker = am.isSpeakerphoneOn
            runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION; am.isSpeakerphoneOn = !headphones(am) }
            silenceBeep(am, true)
        } else { runCatching { am.isSpeakerphoneOn = savedSpeaker; am.mode = savedMode }; silenceBeep(am, false) }
    }

    private val BEEP_STREAMS = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
    private const val MUTED_KEY = "call_muted_streams"

    /**
     * The phone's recogniser plays a short "ready" beep every time it opens the microphone, which in a call only confuses. The streams it can play
     * on are muted for the length of the call (the assistant's own voice uses the call stream, so it is not affected) and put back after. What was
     * muted is written down, so a call that ended with the app being killed is put right the next time the app starts.
     */
    @Suppress("DEPRECATION")
    private fun silenceBeep(am: AudioManager, on: Boolean) {
        val prefs = app?.getSharedPreferences("app", Context.MODE_PRIVATE) ?: return
        if (on) {
            val muted = BEEP_STREAMS.filter { st -> runCatching { !am.isStreamMute(st) }.getOrDefault(false) && runCatching { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); true }.getOrDefault(false) }
            prefs.edit().putString(MUTED_KEY, muted.joinToString(",")).apply()
            android.util.Log.i("Pocketnode", "call: muted streams $muted to silence the recogniser beep")
        } else restoreAudio(app ?: return)
    }

    /** Puts back any stream a call muted. Called when a call ends and when the app starts. */
    fun restoreAudio(ctx: Context) {
        val prefs = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        val list = prefs.getString(MUTED_KEY, "").orEmpty()
        if (list.isBlank()) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        list.split(",").mapNotNull { it.toIntOrNull() }.forEach { runCatching { am.adjustStreamVolume(it, AudioManager.ADJUST_UNMUTE, 0) } }
        prefs.edit().remove(MUTED_KEY).apply()
    }

    private fun headphones(am: AudioManager): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    /** Other music or video pauses while the call is on. */
    @Suppress("DEPRECATION")
    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focus = req
            runCatching { am.requestAudioFocus(req) }
        } else {
            focus = true
            runCatching { am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) }
        }
    }

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val f = focus
        if (android.os.Build.VERSION.SDK_INT >= 26 && f is AudioFocusRequest) runCatching { am.abandonAudioFocusRequest(f) }
        else if (f != null) runCatching { am.abandonAudioFocus(null) }
        focus = null
    }

    /** For the debug receiver: act as if the owner had said these words, so the thinking and speaking steps can be tried without a voice. */
    fun debugSay(words: String) { if (active && AppFeatures.on("voice_call")) submit(words) }
}
