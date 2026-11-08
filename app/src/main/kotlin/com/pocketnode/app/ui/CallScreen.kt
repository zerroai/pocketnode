package com.pocketnode.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pocketnode.app.AppModel
import com.pocketnode.app.VoiceCall
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private val CallBlack = Color(0xFF0A0A0C)
private val CallSilver = Color(0xFFD5D8DE)
private val CallLight = Color(0xFFF5F6F8)
private val CallMid = Color(0xFFA5A9B2)
private val CallEnd = Color(0xFFC4122F)

/**
 * The voice call screen. Always black and silver: the Pocketnode ring and dot is the circle. It swells with your voice while it listens,
 * the dot circles the ring while it thinks, and the ring ripples with waves while it speaks.
 */
@Composable
fun CallScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        VoiceCall.start(context)
        // Android does not let a hidden app keep the microphone open, so leaving the screen ends the call.
        val watch = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) VoiceCall.end() }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch); view.keepScreenOn = false; VoiceCall.end() }
    }
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(VoiceCall.startedAt, VoiceCall.phase) {
        while (VoiceCall.phase != VoiceCall.Phase.Off) { seconds = (System.currentTimeMillis() - VoiceCall.startedAt) / 1000; delay(500) }
    }
    val phase = VoiceCall.phase
    val status = when {
        phase == VoiceCall.Phase.Off && VoiceCall.note.isNotBlank() -> "Call not started"
        phase == VoiceCall.Phase.Off -> "Starting"
        VoiceCall.muted && phase == VoiceCall.Phase.Listening -> "Muted"
        phase == VoiceCall.Phase.Listening && !VoiceCall.ready -> "Getting ready"
        phase == VoiceCall.Phase.Listening -> "Listening"
        phase == VoiceCall.Phase.Thinking -> if (com.pocketnode.app.CoreHost.pendingAsk != null) "Waiting for your answer on the screen" else "Thinking"
        else -> "Speaking, tap to interrupt"
    }
    Column(Modifier.fillMaxSize().background(CallBlack).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().padding(top = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("Pocketnode", Type.headline, CallLight)
            if (phase != VoiceCall.Phase.Off) Label("%d:%02d".format(seconds / 60, seconds % 60), Type.subhead, CallMid)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Pressable({ VoiceCall.interrupt() }, Modifier.size(280.dp), enabled = phase == VoiceCall.Phase.Speaking, description = "The assistant. Tap to interrupt while it speaks.") { _ ->
                Orb(phase, VoiceCall.level, Modifier.size(280.dp))
            }
        }
        Column(Modifier.fillMaxWidth().height(170.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
            Label(status, Type.subhead, CallMid, align = TextAlign.Center)
            val line = when {
                VoiceCall.note.isNotBlank() -> VoiceCall.note
                phase == VoiceCall.Phase.Speaking && VoiceCall.said.isNotBlank() -> VoiceCall.said
                VoiceCall.heard.isNotBlank() -> VoiceCall.heard
                else -> ""
            }
            if (line.isNotBlank()) Box(Modifier.padding(top = 10.dp)) { Label(line, Type.body, CallLight, align = TextAlign.Center, maxLines = 4) }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CallButton(Glyph.Mic, if (VoiceCall.muted) "Unmute" else "Mute", if (VoiceCall.muted) CallLight else Color(0xFF2A2C32), if (VoiceCall.muted) CallBlack else CallLight, enabled = phase != VoiceCall.Phase.Off) { VoiceCall.toggleMute() }
            CallButton(Glyph.Close, "End call", CallEnd, Color.White) { VoiceCall.end(); AppModel.pop() }
        }
    }
}

@Composable
private fun CallButton(glyph: Glyph, label: String, fill: Color, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(onClick, Modifier.size(72.dp), enabled = enabled, description = label) { _ ->
            Box(Modifier.size(72.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) { Icon(glyph, tint, size = 30.dp, weight = 2f) }
        }
        Box(Modifier.padding(top = 8.dp)) { Label(label, Type.footnote, CallMid) }
    }
}

/** The Pocketnode mark (a ring with an opening and a dot in it) drawn large and moving. Geometry is the same as the logo: ring radius 30, gap from -75 to -15 degrees, dot radius 8 at -45 degrees. */
@Composable
private fun Orb(phase: VoiceCall.Phase, rawLevel: Float, modifier: Modifier) {
    // The loudness arrives in jumps; ease it so the circle moves like breathing, not flicker.
    val level by androidx.compose.animation.core.animateFloatAsState(rawLevel, tween(140), label = "level")
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "wave")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val unit = size.minDimension / 2 / 1.9f      // the ring radius is 30 logo units; leave room around it
        val ringR = 30f / 30f * unit
        val speaking = phase == VoiceCall.Phase.Speaking
        val listening = phase == VoiceCall.Phase.Listening
        val swell = when {
            listening -> 1f + 0.16f * level
            speaking -> 1f + 0.05f * sin(wave * 2 * PI).toFloat() + 0.03f * sin(wave * 6 * PI).toFloat()
            else -> 1f + 0.03f * breathe
        }
        val metal = Brush.linearGradient(listOf(Color(0xFFFFFFFF), CallSilver, Color(0xFF8F949E)), Offset(c.x - unit, c.y - unit), Offset(c.x + unit, c.y + unit))
        // Ripples: they follow your voice, or run by themselves while the assistant speaks.
        val ripples = if (listening) 2 else if (speaking) 3 else 0
        for (i in 0 until ripples) {
            val p = if (speaking) (wave + i / ripples.toFloat()) % 1f else (level + i * 0.35f).coerceAtMost(1f)
            val strength = if (speaking) (1f - p) * 0.35f else level * (0.30f - i * 0.1f)
            drawCircle(CallSilver.copy(alpha = strength.coerceIn(0f, 1f)), radius = ringR * swell * (1.05f + 0.5f * p), center = c, style = Stroke(width = unit * 0.10f))
        }
        val turn = if (phase == VoiceCall.Phase.Thinking) spin else 0f
        rotate(turn, c) {
            val r = ringR * swell
            val stroke = Stroke(width = unit * 0.30f, cap = StrokeCap.Round)
            // The ring: 300 degrees, starting at -15 degrees and going clockwise to -75 degrees (the opening is at the top right).
            drawArc(metal, startAngle = -15f, sweepAngle = 300f, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = stroke)
            val a = (-45.0 * PI / 180.0)
            val dot = Offset(c.x + (r * kotlin.math.cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
            drawCircle(metal, radius = unit * 8f / 30f * (1f + if (listening) 0.25f * level else 0f), center = dot)
        }
    }
}
