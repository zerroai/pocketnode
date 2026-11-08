package com.pocketnode.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import kotlin.math.roundToInt

/** Where each marked button is on screen right now. The tour reads this to know what to point at. */
object TourTargets {
    val bounds: SnapshotStateMap<String, Rect> = mutableStateMapOf()
}

/** Marks a button so the tour can find it. The mark disappears when the button leaves the screen. */
fun Modifier.tourTarget(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { TourTargets.bounds.remove(id) } }
    onGloballyPositioned { TourTargets.bounds[id] = it.boundsInRoot() }
}

private class TourStep(val target: String?, val title: String, val body: String, val prepare: () -> Unit = {}, val feature: String? = null)

private fun goMain(tab: Int) { while (AppModel.stack.size > 1) AppModel.pop(); AppModel.tab = tab }

private val steps = listOf(
    TourStep(null, "Welcome to Pocketnode", "A one minute tour of the buttons you will use. Tap Next, or skip it and come back from Settings.", prepare = { goMain(0) }),
    TourStep("tab_chats", "Chats", "Every conversation lives here, newest first. Each one is a separate thread with your assistant.", prepare = { goMain(0) }),
    TourStep("chats_plus", "Start a new chat", "Tap the plus to begin. Talk to it the way you would text a helpful friend.", prepare = { goMain(0) }),
    TourStep("chats_call", "Talk instead of typing", "Tap here for a voice call. It listens, thinks and answers out loud, and you can speak over it to stop it. You choose what is kept: the words stay on this phone, or nothing at all. The sound is never recorded. Change it in Settings, under Keep what was said in calls.", prepare = { goMain(0) }, feature = "voice_call"),
    TourStep("composer_field", "Say what you want", "Type here. Ask for anything: the weather, a reminder, a PDF, a spreadsheet.", prepare = { if (AppModel.current !is com.pocketnode.app.Route.Thread) { goMain(0); AppModel.newChat() } }),
    TourStep("composer_plus", "Add a file", "Attach a photo, a PDF or a text file, and the assistant reads it. You can also share things into Pocketnode from other apps."),
    TourStep("composer_camera", "Take a photo", "Snap something and ask about it, like a receipt or a sign."),
    TourStep("composer_send", "Speak or send", "With nothing typed this is the microphone. Once you type, it becomes the send button."),
    TourStep("suggestions", "Not sure what to ask?", "Tap any idea and it is sent for you. They only show things your installed packs can do."),
    TourStep("tab_files", "Files", "PDFs and spreadsheets the assistant makes are kept here, locked. Tap Save to put a copy on your phone, or Share to send it.", prepare = { goMain(1) }),
    TourStep("tab_packs", "Packs", "Packs give your assistant abilities: weather, email, files, reminders and more. Add or remove them whenever you like.", prepare = { goMain(2) }),
    TourStep("settings_assistant", "The big switch", "Switch the assistant off here and nothing runs, even from Telegram. While it works, a Stop button appears in the chat.", prepare = { goMain(0); AppModel.push(com.pocketnode.app.Route.Settings) }),
    TourStep("settings_activity", "See everything it did", "Every tool it used, and every request that left your phone, is listed here. It stays on this phone."),
    TourStep("settings_briefing", "Morning briefing", "Turn this on for a short summary of your day each morning."),
    TourStep(null, "You are ready", "Ask for something small to start, like a reminder in 5 minutes. You can replay this tour from Settings, under Take the tour.", prepare = { goMain(0) }),
).filter { it.feature == null || com.pocketnode.app.AppFeatures.on(it.feature) }

/** The animated first-use tour. It points at the real buttons and moves between screens by itself. */
object Tour {
    var step by mutableStateOf<Int?>(null)
        private set

    val active: Boolean get() = step != null

    fun start() { step = 0; steps[0].prepare() }

    fun next() { val s = step ?: return; if (s >= steps.lastIndex) end() else { step = s + 1; steps[s + 1].prepare() } }

    fun back() { val s = step ?: return; if (s > 0) { step = s - 1; steps[s - 1].prepare() } }

    fun end() { step = null; goMain(0); AppModel.tourDone() }
}

@Composable
fun TourOverlay() {
    val index = Tour.step ?: return
    val c = MC.colors
    val step = steps[index]
    val target = step.target?.let { TourTargets.bounds[it] }
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val pad = with(density) { 8.dp.toPx() }
    // With no button to point at, the hole is a small point in the middle and the dimming does the talking.
    val hole = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) } ?: Rect(screenW / 2, screenH * 0.4f, screenW / 2, screenH * 0.4f)
    val spec = tween<Float>(420, easing = FastOutSlowInEasing)
    val l by animateFloatAsState(hole.left, spec, label = "l")
    val t by animateFloatAsState(hole.top, spec, label = "t")
    val r by animateFloatAsState(hole.right, spec, label = "r")
    val b by animateFloatAsState(hole.bottom, spec, label = "b")
    val shown by animateFloatAsState(if (target != null) 1f else 0f, tween(300), label = "shown")

    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "ring")
    val tap by pulse.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 1600; 1f at 0; 1f at 500; 0.72f at 750 using FastOutSlowInEasing; 1f at 1000 using FastOutSlowInEasing; 1f at 1600 }, RepeatMode.Restart),
        label = "tap",
    )

    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color.Black.copy(alpha = 0.72f))
            val corner = CornerRadius(16.dp.toPx())
            drawRoundRect(Color.Black, Offset(l, t), Size((r - l).coerceAtLeast(0f), (b - t).coerceAtLeast(0f)), corner, blendMode = BlendMode.Clear, alpha = shown)
            // A ring that grows out of the button and fades, over and over, so the eye lands on it.
            val grow = ring * 14.dp.toPx()
            drawRoundRect(Color.White, Offset(l - grow, t - grow), Size((r - l) + 2 * grow, (b - t) + 2 * grow), CornerRadius(16.dp.toPx() + grow), style = Stroke(2.dp.toPx()), alpha = (1f - ring) * 0.9f * shown)
        }
        // The finger: a soft white dot that presses down on the button, as if tapping it.
        if (target != null) {
            val dot = 32.dp
            // On a wide row the finger rests near the right edge so the words stay readable; on a small button it sits just below centre.
            val wide = (r - l) > with(density) { 200.dp.toPx() }
            val fx = if (wide) r - with(density) { 52.dp.toPx() } else (l + r) / 2 + with(density) { 6.dp.toPx() }
            val fy = (t + b) / 2 + with(density) { 10.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((fx - dot.toPx() / 2).roundToInt(), (fy - dot.toPx() / 2).roundToInt()) }
                    .size(dot).scale(tap).alpha(0.88f).clip(CircleShape).background(Color.White),
            )
        }
        val below = (target?.center?.y ?: 0f) < screenH * 0.5f
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = if (target == null) Arrangement.Center else if (below) Arrangement.Bottom else Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TourCard(index, step)
        }
    }
    LaunchedEffect(index) { /* a step change restarts the finger and the ring from the top */ }
}

@Composable
private fun TourCard(index: Int, step: TourStep) {
    val c = MC.colors
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface).padding(18.dp)) {
        Label(step.title, Type.headline, c.text)
        Spacer(Modifier.height(6.dp))
        Label(step.body, Type.subhead, c.textSecondary)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.indices.forEach { i -> Box(Modifier.size(if (i == index) 8.dp else 6.dp).clip(CircleShape).background(if (i == index) c.accent else c.separator)) }
            }
            if (index < steps.lastIndex) TextAction("Skip", { Tour.end() }, color = c.textSecondary)
            if (index > 0) TextAction("Back", { Tour.back() })
            Spacer(Modifier.width(4.dp))
            Pressable({ Tour.next() }, Modifier.clip(RoundedCornerShape(20.dp)).background(c.ink)) {
                Label(if (index == steps.lastIndex) "Done" else "Next", Type.subhead, c.onInk, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}
