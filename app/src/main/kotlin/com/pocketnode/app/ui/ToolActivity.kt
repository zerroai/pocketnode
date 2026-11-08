package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketnode.core.Images
import com.pocketnode.core.Msg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a tool did, in words a person can read, with any photos it produced. */
private class Summary(val title: String, val detail: String, val failed: Boolean, val photos: List<String>, val document: String? = null, val mapPlace: String? = null, val mapDirections: Boolean = false)

private fun failedText(text: String) = text.startsWith("Error:") || text.startsWith("Denied:") || text.startsWith("Unknown tool")

private fun friendly(name: String): String = when {
    name == "camera.look" -> "Camera"
    name == "photos.latest" -> "Photos"
    name.startsWith("phone.") || name.startsWith("apps.") -> "Phone"
    else -> name.substringBefore('.').replaceFirstChar { it.uppercase() }
}

private fun summarize(m: Msg.ToolResult): Summary {
    val failed = failedText(m.text)
    val photos = if (failed) emptyList() else Images.referencesIn(m.text)
    val raw = m.text.removePrefix("Error: ").removePrefix("Denied: ").trim().lineSequence().firstOrNull().orEmpty()
    // The tool's messages are written for the model; say the same things to the owner in their own terms.
    val problem = when {
        raw.contains("chose not to allow") -> "You chose not to allow this"
        raw.contains("can only be asked while") -> "Pocketnode has to be open to ask you"
        raw.contains("did not answer") -> "You did not answer, so it was not allowed"
        raw.contains("has not granted") -> "Switched off in the pack's permissions"
        raw.contains("Open Pocketnode on the screen") -> "Pocketnode has to be open for this"
        else -> raw.take(160)
    }
    return when (m.name) {
        "camera.look" -> when {
            photos.isNotEmpty() -> Summary("Camera", "Photo taken", false, photos)
            failed && problem.contains("No photo was taken", true) -> Summary("Camera opened", "Nothing was taken", true, emptyList())
            failed -> Summary("Camera", problem.ifBlank { "It could not be used" }, true, emptyList())
            else -> Summary("Camera", "Done", false, emptyList())
        }
        "photos.latest" -> when {
            photos.isNotEmpty() -> Summary("Photos", if (photos.size == 1) "Looked at 1 photo" else "Looked at ${photos.size} photos", false, photos)
            failed -> Summary("Photos", problem.ifBlank { "They could not be read" }, true, emptyList())
            else -> Summary("Photos", "Done", false, emptyList())
        }
        "document.pdf", "document.spreadsheet", "document.file" -> {
            val ref = Regex("mc-doc:[0-9a-f]{16}").find(m.text)?.value
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(m.text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            fun num(k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            val detail = when (m.name) {
                "document.pdf" -> num("pages")?.let { if (it == 1) "1 page" else "$it pages" }
                "document.spreadsheet" -> num("rows")?.let { r -> (num("columns") ?: 0).let { c -> if (c > 0) "$r rows, $c columns" else "$r rows" } }
                else -> null
            }
            if (!failed && ref != null) Summary(if (m.name == "document.spreadsheet") "Spreadsheet made" else if (m.name == "document.file") "File saved" else "Document made", detail ?: "Ready to open", false, emptyList(), ref)
            else Summary("Document", if (failed) problem.ifBlank { "It did not work" } else "Done", failed, emptyList())
        }
        "phone.map" -> {
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(m.text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            fun str(k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            val place = str("place")?.takeIf { it.isNotBlank() }
            val directions = str("directions") == "true"
            Summary("Phone", if (failed) problem.ifBlank { "It did not work" } else str("done") ?: "Done", failed, emptyList(), null, if (failed) null else place, directions)
        }
        else -> if (m.name.startsWith("app.")) com.pocketnode.app.AppToolApps.describe(m.name).let { d -> Summary(d?.first ?: "App", if (failed) problem.ifBlank { "It did not work" } else d?.second ?: "Done", failed, emptyList()) }
        else Summary(friendly(m.name), if (failed) problem.ifBlank { "It did not work" } else phoneDetail(m.name, m.text), failed, photos)
    }
}

/** A short, specific line for the phone's own tools, taken from what the phone answered. Anything unexpected is just "Done". */
private fun phoneDetail(name: String, text: String): String {
    val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
    fun str(k: String) = (o?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
    return when (name) {
        "phone.flashlight" -> "Flashlight ${str("flashlight") ?: "switched"}"
        "apps.open" -> str("opened")?.let { "Opened $it" } ?: "Done"
        "phone.alarm" -> str("alarm")?.let { "Alarm set for $it" } ?: "Done"
        "phone.timer" -> str("timerSeconds")?.toIntOrNull()?.let { s -> if (s % 60 == 0) "Timer set for ${s / 60} min" else "Timer set for $s sec" } ?: "Done"
        "phone.notify" -> "Notification shown"
        "phone.speak" -> "Speaking out loud"
        "phone.copy" -> "Copied to the clipboard"
        "phone.status" -> listOfNotNull(str("batteryPercent")?.let { "Battery $it%" }, str("network")).joinToString(", ").ifBlank { "Done" }
        "notes.add" -> "Note saved"
        "notes.delete" -> "Note deleted"
        "notes.list", "notes.search" -> (o?.get("notes") as? kotlinx.serialization.json.JsonArray ?: o?.get("matches") as? kotlinx.serialization.json.JsonArray)?.size?.let { if (it == 1) "1 note found" else "$it notes found" } ?: "Done"
        "todo.add" -> "Added to your to-do list"
        "todo.done" -> "Marked as done"
        "calendar.add", "contacts.add", "phone.call", "phone.text", "phone.email", "phone.share", "phone.open_link", "phone.map" -> str("done") ?: "Done"
        "calendar.events" -> (o?.get("events") as? kotlinx.serialization.json.JsonArray)?.size?.let { if (it == 1) "1 event found" else "$it events found" } ?: "Done"
        "contacts.search" -> (o?.get("contacts") as? kotlinx.serialization.json.JsonArray)?.size?.let { if (it == 1) "1 contact found" else "$it contacts found" } ?: "Done"
        "reminders.add", "reminder.add" -> str("when")?.let { w -> "Set for $w" + (str("repeat")?.let { r -> ", " + (if (r == "weekdays") "every weekday" else r) } ?: "") } ?: "Reminder set"
        "reminders.cancel", "reminder.cancel" -> "Reminder cancelled"
        "reminders.list", "reminder.list" -> (o?.get("reminders") as? kotlinx.serialization.json.JsonArray)?.size?.let { if (it == 1) "1 reminder waiting" else "$it reminders waiting" } ?: "Done"
        "location.where" -> "Found where the phone is"
        else -> "Done"
    }
}

@Composable
fun ToolActivity(m: Msg.ToolResult) {
    val c = MC.colors
    val s = remember(m) { summarize(m) }
    var viewing by remember { mutableStateOf<String?>(null) }
    // A place shows as a small map you can tap, not as a line saying it was opened.
    if (s.mapPlace != null) { MapCard(s.mapPlace, s.mapDirections); return }
    Column(Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(Dim.radiusGroup)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.failed) IconTile(Glyph.Alert, c.textSecondary, c.fill) else IconTile(toolGlyph(m.name), c.accent, c.accentSoft)
            Spacer(Modifier.size(12.dp))
            Column {
                Label(s.title, Type.subhead, c.text)
                Label(s.detail, Type.footnote, c.textSecondary)
            }
        }
        s.document?.let { DocumentRow(it) }
        if (s.photos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.photos.forEach { ref -> PhotoThumb(ref, if (s.photos.size == 1) 220.dp else 96.dp, crop = s.photos.size > 1) { viewing = ref } }
            }
        }
    }
    viewing?.let { PhotoViewer(it) { viewing = null } }
}

/** A made document inside the chat: tap to read it, or send it somewhere. */
@Composable
private fun DocumentRow(ref: String) {
    val c = MC.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val file = remember(ref) { com.pocketnode.app.CoreHost.assetFiles.info(ref) }
    Spacer(Modifier.height(10.dp))
    if (file == null) {
        Label(stringResource(R.string.tool_this_document_is_no_longer_stored), Type.footnote, c.textTertiary)
        return
    }
    var problem by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.fill).clickable {
        problem = if (com.pocketnode.app.FileActions.open(context, ref)) null else com.pocketnode.app.FileActions.NO_APP
    }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
        val actions = @Composable {
            TextAction(stringResource(R.string.tool_save), { com.pocketnode.app.CoreHost.saveFile(ref) { problem = it } })
            Spacer(Modifier.size(12.dp))
            TextAction("Share", { if (!com.pocketnode.app.FileActions.share(context, ref)) problem = "That file could not be unlocked." })
        }
        Icon(Glyph.File, c.accent, size = 22.dp)
        Spacer(Modifier.size(10.dp))
        // With large text the buttons go under the name so the name is not broken mid-word.
        Column(Modifier.weight(1f)) {
            Label(file.name, Type.subhead, c.text)
            Label(com.pocketnode.app.AssetFiles.kind(file.name) + ", tap to open", Type.caption, c.textSecondary)
            if (large) Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        if (!large) actions()
    }
    problem?.let { Spacer(Modifier.height(6.dp)); Label(it, Type.caption, c.textSecondary) }
}

/** A saved photo as a small picture. If Android has cleared it from the app's cache, it says so instead of showing a hole. */
@Composable
fun PhotoThumb(ref: String, size: Dp, crop: Boolean = true, onClick: () -> Unit) {
    val c = MC.colors
    val bitmap by produceState<ImageBitmap?>(null, ref) {
        value = withContext(Dispatchers.IO) {
            Images.source(ref)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    val bmp = bitmap
    if (!crop && bmp != null) {
        // Keep the picture's own shape, as wide as [size] allows and no taller than a bit more than that.
        Image(bmp, contentDescription = stringResource(R.string.tool_photo), contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = size).heightIn(max = size * 1.3f).clip(shape).clickable(onClick = onClick))
    } else {
        Box(Modifier.size(size).clip(shape).background(c.fill).clickable(enabled = bmp != null, onClick = onClick), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, contentDescription = stringResource(R.string.tool_photo), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Label(stringResource(R.string.tool_photo_no_longer_stored), Type.caption, c.textTertiary, Modifier.padding(8.dp))
        }
    }
}

@Composable
fun PhotoViewer(ref: String, onClose: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, ref) {
        value = withContext(Dispatchers.IO) { Images.source(ref)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, contentDescription = stringResource(R.string.tool_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
