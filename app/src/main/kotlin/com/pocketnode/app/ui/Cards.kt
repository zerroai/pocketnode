package com.pocketnode.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Cards that show what a tool did inside the chat, instead of one line of words.

/** The small icon that stands for a tool's family. */
internal fun toolGlyph(name: String): Glyph = when {
    name == "phone.map" -> Glyph.MapPin
    name.startsWith("reminder") || name == "phone.alarm" || name == "phone.timer" -> Glyph.Clock
    name.startsWith("notes.") || name.startsWith("todo.") || name.startsWith("library.") -> Glyph.Note
    name.startsWith("calendar.") -> Glyph.Calendar
    name.startsWith("contacts.") -> Glyph.Person
    name == "phone.flashlight" -> Glyph.Bolt
    name == "phone.status" -> Glyph.Battery
    name.startsWith("weather.") -> Glyph.Sun
    name == "phone.open_link" || name.startsWith("web.") || name.startsWith("news.") -> Glyph.Link
    name == "camera.look" || name == "photos.latest" -> Glyph.Camera
    name.startsWith("document.") -> Glyph.File
    name.startsWith("location.") -> Glyph.MapPin
    else -> Glyph.Packs
}

/** The icon for a pack in lists, chosen from its id; a pack with no match keeps the general one. */
internal fun packGlyph(id: String, kind: String): Glyph = when (id.substringAfterLast('.')) {
    "telegram" -> Glyph.Plane
    "whatsapp" -> Glyph.Chat
    "phone" -> Glyph.Phone
    "calendar" -> Glyph.Calendar
    "camera" -> Glyph.Camera
    "contacts" -> Glyph.Person
    "documents", "data" -> Glyph.File
    "mail" -> Glyph.Send
    "knowledge", "web", "news" -> Glyph.Link
    "library", "notes", "study", "budget", "invoices", "finance" -> Glyph.Note
    "location", "safety" -> Glyph.MapPin
    "reminders" -> Glyph.Clock
    "weather" -> Glyph.Sun
    "utilities", "math" -> Glyph.Bolt
    else -> if (kind == "channel") Glyph.Chat else Glyph.Packs
}

/** A rounded tile holding an icon, the way most tool rows start. */
@Composable
internal fun IconTile(glyph: Glyph, tint: Color, background: Color, size: Dp = 38.dp) {
    // The house tile (brand/icons/README.md, style A): a silver metal glyph on a black rounded tile with a thin silver edge.
    // [tint] and [background] are kept so call sites still compile; the tile has fixed brand colors.
    val shape = RoundedCornerShape(size * 0.225f)
    val metal = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC9CDD4), Color(0xFF8F949E)))
    Box(
        Modifier.size(size).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2B31), Color(0xFF070708))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xFF8E939D), Color(0xFF2E3036))), shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(glyph.res), contentDescription = null,
            modifier = Modifier.size(size * 0.56f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent { drawContent(); drawRect(metal, blendMode = BlendMode.SrcIn) },
        )
    }
}

/**
 * A place, drawn as a small map: streets, a park, a stretch of water and a pin (or a route when directions were asked for).
 * It is a picture made on the phone, not a live map, so it needs no network and sends nothing. Tapping it opens the real maps app.
 * The streets are laid out from the place's name, so two places do not look the same.
 */
@Composable
fun MapCard(place: String, directions: Boolean) {
    val c = MC.colors
    val context = LocalContext.current
    val open = {
        val q = Uri.encode(place)
        val uri = if (directions) "https://www.google.com/maps/dir/?api=1&destination=$q" else "geo:0,0?q=$q"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Unit
    }
    val label = if (directions) "Directions to $place. Opens the maps app." else "$place on the map. Opens the maps app."
    Pressable(open, Modifier.widthIn(max = 560.dp).fillMaxWidth(), description = label) { pressed ->
        Column(Modifier.pressScale(pressed).clip(RoundedCornerShape(Dim.radiusGroup)).background(c.surface).border(1.dp, c.separator, RoundedCornerShape(Dim.radiusGroup))) {
            MapArt(place, directions, Modifier.fillMaxWidth().height(148.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(place, Type.headline, c.text, maxLines = 2)
                    Label(if (directions) "Directions" else "On the map", Type.footnote, c.textSecondary)
                }
                Spacer(Modifier.size(12.dp))
                Box(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Label("Open", Type.subhead, c.accent)
                }
            }
        }
    }
}

@Composable
private fun MapArt(place: String, directions: Boolean, modifier: Modifier) {
    val c = MC.colors
    val seed = remember(place) { place.lowercase().hashCode() }
    Canvas(modifier) { drawMap(seed, directions, c) }
}

private fun DrawScope.drawMap(seed: Int, directions: Boolean, c: Palette) {
    val w = size.width; val h = size.height
    val rnd = java.util.Random(seed.toLong())
    fun jitter(amount: Float) = (rnd.nextFloat() - 0.5f) * 2f * amount
    // Ground, then softly tinted areas.
    drawRect(c.fill)
    val park = Path().apply {
        val x = w * (0.08f + rnd.nextFloat() * 0.18f); val y = h * (0.08f + rnd.nextFloat() * 0.2f)
        addRoundRect(androidx.compose.ui.geometry.RoundRect(x, y, x + w * 0.22f, y + h * 0.3f, CornerRadius(14.dp.toPx())))
    }
    drawPath(park, c.success.copy(alpha = if (c.dark) 0.20f else 0.16f))
    val water = Path().apply {
        moveTo(w, h * (0.58f + jitter(0.06f)))
        cubicTo(w * 0.78f, h * 0.5f, w * 0.7f, h * 0.9f, w * 0.5f, h * 1.02f)
        lineTo(w, h * 1.02f); close()
    }
    drawPath(water, c.accent.copy(alpha = if (c.dark) 0.18f else 0.12f))
    // City blocks: a grid of soft rounded tiles; the gaps between them read as streets.
    val cols = 5; val rows = 3
    val gap = 7.dp.toPx()
    val cw = (w - gap) / cols; val ch = (h - gap) / rows
    for (i in 0 until cols) for (j in 0 until rows) {
        val x = gap + i * cw + jitter(cw * 0.05f); val y = gap + j * ch + jitter(ch * 0.05f)
        drawRoundRect(c.surface.copy(alpha = if (c.dark) 0.55f else 0.85f), Offset(x, y), Size(cw - gap, ch - gap), CornerRadius(6.dp.toPx()))
    }
    // One main road across the card.
    val road = Path().apply {
        moveTo(-10f, h * (0.62f + jitter(0.1f)))
        cubicTo(w * 0.3f, h * (0.45f + jitter(0.1f)), w * 0.6f, h * (0.78f + jitter(0.1f)), w + 10f, h * (0.4f + jitter(0.1f)))
    }
    drawPath(road, c.separator.copy(alpha = 0.9f), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round))
    drawPath(road, c.surface, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
    if (directions) {
        val start = Offset(w * 0.17f, h * 0.76f); val end = Offset(w * 0.76f, h * 0.36f)
        val route = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(w * (0.38f + jitter(0.05f)), h * 0.86f, w * (0.46f + jitter(0.05f)), h * 0.3f, end.x, end.y)
        }
        drawPath(route, c.accent.copy(alpha = 0.22f), style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, c.accent, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.surface, 8.dp.toPx(), start); drawCircle(c.accent, 5.dp.toPx(), start)
        drawPin(c, end)
    } else {
        drawPin(c, Offset(w * (0.46f + jitter(0.08f)), h * 0.58f))
    }
}

/** A map pin whose tip is at [tip]. */
private fun DrawScope.drawPin(c: Palette, tip: Offset) {
    val r = 11.dp.toPx(); val lift = 26.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.18f), 7.dp.toPx(), tip)
    val head = Offset(tip.x, tip.y - lift)
    val body = Path().apply {
        moveTo(tip.x, tip.y)
        cubicTo(tip.x - r * 0.9f, tip.y - lift * 0.55f, head.x - r, head.y + r * 0.4f, head.x - r, head.y)
        arcTo(androidx.compose.ui.geometry.Rect(head.x - r, head.y - r, head.x + r, head.y + r), 180f, 180f, false)
        cubicTo(head.x + r, head.y + r * 0.4f, tip.x + r * 0.9f, tip.y - lift * 0.55f, tip.x, tip.y)
        close()
    }
    drawPath(body, c.accent)
    drawCircle(Color.White, 4.5.dp.toPx(), head)
}

private val AvatarHues = listOf(
    Color(0xFF6B707A), Color(0xFF8A8F99), Color(0xFFA5A9B2), Color(0xFF5B5F69), Color(0xFF7B808A), Color(0xFF9399A4), Color(0xFF656A74),
)

/**
 * The picture beside a chat: the first letter of its title on a soft silver or gray that stays the same for that chat (the brand has no hues).
 * Chats from Telegram and WhatsApp keep their own symbol. It is decoration; the title next to it is what a screen reader reads.
 */
@Composable
fun ChatAvatar(id: String, title: String, channelGlyph: Glyph?, size: Dp = 48.dp) {
    val c = MC.colors
    val hue = AvatarHues[(id.hashCode().toLong() and 0x7fffffff).toInt() % AvatarHues.size]
    val bg = hue.copy(alpha = if (c.dark) 0.24f else 0.16f)
    val ink = if (c.dark) lerp(hue, Color.White, 0.45f) else lerp(hue, Color.Black, 0.25f)
    val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        if (channelGlyph != null) Icon(channelGlyph, ink, size = size * 0.46f, weight = 1.9f)
        else if (initial != null) androidx.compose.foundation.text.BasicText(initial, style = Type.title.copy(color = ink, fontSize = (size.value * 0.42f).sp))
        else Icon(Glyph.Chat, ink, size = size * 0.46f, weight = 1.9f)
    }
}
