package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.pocketnode.app.Attached
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.ChatRow
import com.pocketnode.app.CoreHost
import com.pocketnode.app.Provider
import com.pocketnode.app.Route
import com.pocketnode.app.TelegramState
import com.pocketnode.app.WhatsAppState
import com.pocketnode.core.Msg
import com.pocketnode.core.PackManifest
import com.pocketnode.core.Permission
import com.pocketnode.core.ProbeResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timeLabel(context: android.content.Context, millis: Long): String {
    if (millis <= 0) return ""
    return when {
        DateUtils.isToday(millis) -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        System.currentTimeMillis() - millis < 6 * DateUtils.DAY_IN_MILLIS -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(millis))
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
internal fun LargeTitleHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp).padding(horizontal = Dim.margin).padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, Type.largeTitle, MC.colors.text, Modifier.weight(1f))
        if (trailing != null) trailing()
        // The profile button: Settings and the other menus live behind it.
        Pressable({ AppModel.push(Route.Profile) }, Modifier.size(Dim.touch).tourTarget("profile_button"), description = "Profile and settings") { pressed ->
            Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MC.colors.accentSoft), contentAlignment = Alignment.Center) { Icon(Glyph.Person, if (pressed) MC.colors.textSecondary else MC.colors.accent, size = 20.dp) }
            }
        }
    }
}

@Composable
internal fun EmptyState(glyph: Glyph, title: String, body: String, action: (@Composable () -> Unit)? = null) {
    val c = MC.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(glyph, c.textTertiary, size = 40.dp, weight = 1.5f)
        Spacer(Modifier.height(16.dp))
        Label(title, Type.headline, c.text, align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Label(body, Type.subhead, c.textSecondary, align = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

// ---- Chats ---------------------------------------------------------------------------------

@Composable
fun ChatsScreen() {
    val c = MC.colors
    val context = LocalContext.current
    val rows = AppModel.chats.filterNot { it.isCall }
    val busy = AppModel.busy
    val hasModel = CoreHost.hasModel()
    var actionsFor by remember { mutableStateOf<ChatRow?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(c.grouped), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        item {
            LargeTitleHeader(stringResource(R.string.scr_chats)) {
                Row {
                    if (rows.isNotEmpty()) Pressable({ if (AppModel.searchOpen) AppModel.closeSearch() else AppModel.searchOpen = true }, Modifier.size(Dim.touch).tourTarget("chats_search"), description = "Search chats") { pressed ->
                        Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Search, if (pressed) c.textSecondary else c.accent, size = 24.dp, weight = 2f) }
                    }
                    if (com.pocketnode.app.AppFeatures.on("voice_call")) Pressable({ AppModel.push(Route.Call) }, Modifier.size(Dim.touch).tourTarget("chats_call"), description = "Voice call") { pressed ->
                        Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Voice, if (pressed) c.textSecondary else c.accent, size = 24.dp, weight = 2f) }
                    }
                    Pressable({ AppModel.newChat() }, Modifier.size(Dim.touch).tourTarget("chats_plus"), description = "New chat") { pressed ->
                        Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Plus, if (pressed) c.textSecondary else c.accent, size = 26.dp, weight = 2f) }
                    }
                }
            }
        }
        if (AppModel.searchOpen) item {
            Box(Modifier.padding(horizontal = Dim.margin).padding(bottom = 12.dp)) {
                SearchBar(AppModel.searchQuery, { AppModel.searchQuery = it; AppModel.runSearch() }, { AppModel.closeSearch() }, placeholder = stringResource(R.string.scr_search_chats))
            }
        }
        if (!hasModel && CoreHost.config.ready) item {
            Group(Modifier.padding(horizontal = Dim.margin).padding(bottom = 12.dp)) {
                ListRow(stringResource(R.string.scr_connect_a_model), subtitle = stringResource(R.string.scr_your_assistant_needs_a_model_to_reply), chevron = true, onClick = { AppModel.push(Route.ModelSettings) }, dividerInset = null)
            }
        }
        if (rows.isEmpty() && !AppModel.chatsLoaded) item {
            // The locked database is still opening; without this the list is blank for a few seconds after a cold start.
            Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) { Spinner(c.textSecondary, 28.dp) }
        } else if (rows.isEmpty()) item {
            EmptyState(Glyph.Chat, stringResource(R.string.scr_no_conversations_yet), stringResource(R.string.scr_start_a_chat_with_your_assistant)) {
                PrimaryButton(stringResource(R.string.scr_new_chat), { AppModel.newChat() }, Modifier.widthIn(max = 200.dp))
            }
        } else {
            val shown = if (AppModel.searchOpen && AppModel.searchQuery.isNotBlank()) AppModel.searchHits else rows
            if (AppModel.searchOpen && AppModel.searchQuery.isNotBlank() && shown.isEmpty()) item {
                Label(stringResource(R.string.scr_no_chat_has_those_words), Type.subhead, c.textSecondary, Modifier.padding(horizontal = Dim.margin + 4.dp, vertical = 8.dp))
            } else {
                // One row at a time, so a long history is not all built at once. The first and last row carry the card's rounded corners.
                itemsIndexed(shown, key = { _, row -> row.id }) { _, row ->
                    Box(Modifier.animateItem().padding(horizontal = Dim.margin, vertical = 3.dp).fillMaxWidth()) {
                        ChatListRow(row, timeLabel(context, row.activityMillis), row.id in busy, onMore = { actionsFor = row })
                    }
                }
            }
        }
    }
    actionsFor?.let { row -> ChatActions(row, onClose = { actionsFor = null }) }
}

/** The things you can do to one chat. Used from the list (long press) and from the chat's own top bar. */
@Composable
fun ChatActions(row: ChatRow, onClose: () -> Unit) {
    var step by remember(row.id) { mutableStateOf("menu") }
    when (step) {
        "menu" -> ActionDialog(
            row.title,
            listOf(
                DialogAction(stringResource(R.string.scr_rename)) { step = "rename" },
                DialogAction(if (row.pinned) stringResource(R.string.scr_unpin) else stringResource(R.string.scr_pin_to_the_top)) { AppModel.pinChat(row.id, !row.pinned); onClose() },
                DialogAction(stringResource(R.string.scr_share_this_chat_as_text)) { CoreHost.shareChat(row.id, row.title); onClose() },
                DialogAction(stringResource(R.string.scr_delete), destructive = true) { step = "delete" },
            ),
            onClose,
        )
        "rename" -> PromptDialog(stringResource(R.string.scr_rename_chat), row.title, stringResource(R.string.scr_save), { AppModel.renameChat(row.id, it); onClose() }, onClose)
        else -> ConfirmDialog(
            stringResource(R.string.scr_delete_this_chat), stringResource(R.string.scr_this_removes_the_chat_and_the_photos_and),
            stringResource(R.string.scr_delete), { AppModel.deleteChat(row.id); onClose() }, onClose, destructive = true,
        )
    }
}

@Composable
private fun ChatListRow(row: ChatRow, time: String, working: Boolean, onMore: () -> Unit) {
    val c = MC.colors
    Pressable({ AppModel.openThread(row.id) }, Modifier.fillMaxWidth(), onLongClick = onMore) { pressed ->
        Row(
            Modifier.pressScale(pressed).fillMaxWidth().clip(RoundedCornerShape(Dim.radiusGroup)).background(if (pressed) c.pressed else c.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val channel = if (row.id.startsWith("telegram:") || row.id.startsWith("whatsapp:")) row.glyph else null
            ChatAvatar(row.id, row.title, channel, 48.dp)
            Spacer(Modifier.size(12.dp))
            val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
            Column(Modifier.weight(1f)) {
                // With large text the time moves under the title so the title keeps its room.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Label(row.title, Type.headline, c.text, Modifier.weight(1f, fill = false), maxLines = if (large) 2 else 1)
                        if (row.pinned) { Spacer(Modifier.size(6.dp)); Icon(Glyph.Pin, c.textTertiary, size = 14.dp) }
                    }
                    if (!large) {
                        Spacer(Modifier.size(8.dp))
                        if (working) Spinner(c.textSecondary, 14.dp) else Label(time, Type.footnote, c.textTertiary, maxLines = 1)
                    }
                }
                if (large) { if (working) Spinner(c.textSecondary, 14.dp) else Label(time, Type.footnote, c.textTertiary, maxLines = 1) }
                Label(row.preview, Type.subhead, c.textSecondary, maxLines = 2)
            }
        }
    }
}

// ---- Thread --------------------------------------------------------------------------------

@Composable
fun ThreadScreen(sessionId: String) {
    val c = MC.colors
    val messages = AppModel.messages
    val working = sessionId in AppModel.busy
    val readOnly = sessionId.startsWith("telegram:") || sessionId.startsWith("whatsapp:")
    val channelName = if (sessionId.startsWith("whatsapp:")) "WhatsApp" else stringResource(R.string.scr_telegram)
    val private = AppModel.isPrivate(sessionId)
    val title = if (private) stringResource(R.string.scr_private_chat) else AppModel.chats.firstOrNull { it.id == sessionId }?.title ?: if (readOnly) "$channelName chat" else stringResource(R.string.scr_new_chat)
    val hasModel = CoreHost.hasModel()
    val threadRow = AppModel.chats.firstOrNull { it.id == sessionId }
    var threadMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val visible = messages.filter { it is Msg.User || it is Msg.Assistant || it is Msg.ToolResult }
    // While a reply is being worked on, pick up the tools it uses and the photos it takes as they happen.
    LaunchedEffect(working) { while (working) { kotlinx.coroutines.delay(1200); AppModel.refreshThread() } }
    // While the answer is being written, keep its end in view without animating on every word.
    val partialLength = CoreHost.partials[sessionId]?.length ?: 0
    LaunchedEffect(partialLength) {
        if (partialLength > 0) listState.scrollToItem(visible.size + (if (working) 1 else 0) - 1)
    }
    LaunchedEffect(visible.size, working) {
        val count = visible.size + if (working) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    Frame(c.background) {
        if (threadMenu && threadRow != null && !private) ChatActions(threadRow) { threadMenu = false }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(title, onBack = { AppModel.pop() }, divider = true, trailing = if (threadRow != null && !private) ({
                Pressable({ threadMenu = true }, Modifier.size(Dim.touch), description = stringResource(R.string.scr_chat_options)) { pressed -> Box(Modifier.size(Dim.touch).alpha(if (pressed) 0.5f else 1f), contentAlignment = Alignment.Center) { Icon(Glyph.More, c.accent, size = 24.dp, weight = 2.4f) } }
            }) else null)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visible.isEmpty() && !working) {
                    // Scrolls when the screen is short (a phone on its side), and stays centred when it is not.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().defaultMinSize(minHeight = maxHeight).verticalScroll(androidx.compose.foundation.rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Logo(40.dp, c.textTertiary)
                        Spacer(Modifier.height(16.dp))
                        Label(stringResource(R.string.scr_ask_anything), Type.callout, c.textSecondary)
                        if (!readOnly) Suggestions()
                        if (!readOnly && !private && sessionId.startsWith("local:")) {
                            Spacer(Modifier.height(12.dp))
                            Pressable({ AppModel.makePrivate() }, description = stringResource(R.string.scr_make_this_a_private_chat)) { pressed -> Box(Modifier.defaultMinSize(minHeight = Dim.touch).padding(horizontal = 12.dp).alpha(if (pressed) 0.5f else 1f), contentAlignment = Alignment.Center) { Label(stringResource(R.string.scr_make_this_a_private_chat), Type.callout, c.accent) } }
                        } else if (private) {
                            Spacer(Modifier.height(8.dp))
                            Label(stringResource(R.string.scr_deleted_when_you_leave_it_your_model_pro), Type.footnote, c.textTertiary, modifier = Modifier.padding(horizontal = 32.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Dim.margin, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        itemsIndexed(visible) { i, m -> MessageView(m, onRetry = if (!readOnly && !working && i == visible.lastIndex) ({ AppModel.retry() }) else null, canAskAgain = !readOnly && !working && i == visible.lastIndex) }
                        if (working) item {
                            val partial = CoreHost.partials[sessionId].orEmpty()
                            if (partial.isNotEmpty()) MessageView(Msg.Assistant(partial), onRetry = null)
                            else Box(Modifier.padding(vertical = 4.dp)) { Spinner(c.textSecondary, 16.dp) }
                        }
                        val last = visible.lastOrNull()
                        if (!working && !readOnly && last is Msg.Assistant && last.calls.isEmpty() && last.text.length >= 240 && !last.text.startsWith(com.pocketnode.core.MODEL_ERROR)) item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(stringResource(R.string.scr_shorter), stringResource(R.string.scr_say_more), stringResource(R.string.scr_give_an_example)).forEach { f ->
                                    Pressable({ AppModel.send(f) }, Modifier.clip(RoundedCornerShape(18.dp)).background(c.fill), description = f) {
                                        Box(Modifier.defaultMinSize(minHeight = Dim.touch).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Label(f, Type.subhead, c.accent) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            when {
                readOnly -> Label("Replies in this chat are sent through $channelName.", Type.caption, c.textSecondary, Modifier.fillMaxWidth().padding(12.dp), align = TextAlign.Center)
                !hasModel && CoreHost.config.ready -> Row(Modifier.fillMaxWidth().padding(start = Dim.margin, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(stringResource(R.string.scr_connect_a_model_to_start_chatting), Type.subhead, c.textSecondary, Modifier.weight(1f))
                    TextAction(stringResource(R.string.scr_connect), { AppModel.push(Route.ModelSettings) })
                }
                else -> Column {
                    if (working) Row(Modifier.fillMaxWidth().padding(horizontal = Dim.margin), verticalAlignment = Alignment.CenterVertically) {
                        Label(stringResource(R.string.scr_working_on_it), Type.footnote, c.textSecondary, Modifier.weight(1f))
                        TextAction(stringResource(R.string.scr_stop), { CoreHost.stopNow() }, color = c.error)
                    }
                    Composer(enabled = !working) { AppModel.send(it) }
                }
            }
        }
    }
}

@Composable
private fun MessageView(m: Msg, onRetry: (() -> Unit)? = null, canAskAgain: Boolean = false) {
    val c = MC.colors
    var viewing by remember { mutableStateOf<String?>(null) }
    viewing?.let { PhotoViewer(it) { viewing = null } }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val holdToOpen = Modifier.combinedClickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = { menu = true })
    fun copy(t: String) { clipboard.setText(androidx.compose.ui.text.AnnotatedString(t)); android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show() }
    fun share(t: String) { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, t), null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    when (m) {
        is Msg.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val sent = remember(m.text) { com.pocketnode.core.Attachments.parse(m.text) }
            if (menu) ActionDialog(stringResource(R.string.scr_your_message), buildList {
                if (sent.text.isNotBlank()) { add(DialogAction(stringResource(R.string.scr_copy)) { copy(sent.text); menu = false }); add(DialogAction(stringResource(R.string.scr_share)) { share(sent.text); menu = false }) }
                add(DialogAction(stringResource(R.string.scr_edit_and_send_again)) { AppModel.editAndResend(m); menu = false })
            }, { menu = false })
            Column(Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(topStart = Dim.radiusBubble, topEnd = Dim.radiusBubble, bottomStart = Dim.radiusBubble, bottomEnd = 6.dp)).then(holdToOpen).background(c.ink).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (sent.photos.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sent.photos.forEach { PhotoThumb(it, if (sent.photos.size == 1) 220.dp else 100.dp, crop = sent.photos.size > 1) { viewing = it } }
                    }
                    if (sent.files.isNotEmpty() || sent.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                sent.files.forEach { ref ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Icon(Glyph.File, c.onInk, size = 18.dp)
                        Spacer(Modifier.size(8.dp))
                        Label(com.pocketnode.core.Attachments.fileSource(ref)?.name ?: stringResource(R.string.scr_file_no_longer_stored), Type.subhead, c.onInk, maxLines = 1)
                    }
                }
                if (sent.text.isNotBlank()) Label(sent.text, Type.body, c.onInk)
            }
        }
        is Msg.Assistant -> {
            val error = m.text.removePrefix(stringResource(R.string.scr_model_error)).takeIf { m.text.startsWith(stringResource(R.string.scr_model_error)) }
            if (error != null) {
                Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
                    InlineStatus(StatusKind.Error, stringResource(R.string.scr_the_assistant_could_not_reply))
                    Spacer(Modifier.height(2.dp))
                    Label(error, Type.subhead, c.textSecondary, Modifier.padding(start = 28.dp))
                    if (com.pocketnode.core.OfflineQueue.isWaiting(m.text)) Label(stringResource(R.string.scr_it_will_try_again_by_itself_when_you_are), Type.subhead, c.textSecondary, Modifier.padding(start = 28.dp, top = 2.dp))
                    if (onRetry != null) { Spacer(Modifier.height(4.dp)); TextAction(stringResource(R.string.scr_try_again), onRetry, Modifier.padding(start = 20.dp)) }
                }
            } else {
                val shown = com.pocketnode.core.MarkdownLite.hideInternalLinks(m.text)
                if (menu) ActionDialog(stringResource(R.string.scr_reply), buildList {
                    add(DialogAction(stringResource(R.string.scr_copy)) { copy(shown); menu = false })
                    add(DialogAction(stringResource(R.string.scr_share)) { share(shown); menu = false })
                    if (canAskAgain) add(DialogAction(stringResource(R.string.scr_ask_again)) { AppModel.askAgain(); menu = false })
                }, { menu = false })
                RichLabel(shown, Type.body, c.text, Modifier.fillMaxWidth().widthIn(max = 560.dp).then(holdToOpen))
            }
        }
        is Msg.ToolResult -> ToolActivity(m)
    }
}

@Composable
private fun Composer(enabled: Boolean, onSend: (String) -> Unit) {
    val c = MC.colors
    var text by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(AppModel.draft) { AppModel.takeDraft().takeIf { it.isNotEmpty() }?.let { d -> text = if (text.isBlank()) d else text.trimEnd() + " " + d } }
    val pending = AppModel.attachments
    val canSend = enabled && (text.isNotBlank() || pending.isNotEmpty())
    val canAttach = enabled && !AppModel.attaching
    Column(Modifier.fillMaxWidth()) {
        AppModel.attachNotice?.let { InlineStatus(StatusKind.Info, it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (pending.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pending.forEach { a -> AttachmentChip(a) { AppModel.removeAttachment(a.ref) } }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            RoundButton(Glyph.Plus, canAttach, AppModel.attaching, "composer_plus") { AppModel.attachFiles() }
            Spacer(Modifier.size(6.dp))
            RoundButton(Glyph.Camera, canAttach, false, "composer_camera") { AppModel.attachCamera() }
            Spacer(Modifier.size(8.dp))
            Box(Modifier.weight(1f).defaultMinSize(minHeight = 44.dp).tourTarget("composer_field").clip(RoundedCornerShape(Dim.radiusComposer)).background(c.fill).padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Label(if (pending.isEmpty()) stringResource(R.string.scr_message) else stringResource(R.string.scr_add_a_message_or_just_send), Type.body, c.textTertiary)
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accent), maxLines = 5)
            }
            Spacer(Modifier.size(8.dp))
            if (!canSend && text.isEmpty()) {
                // Nothing to send yet: the button is the microphone.
                RoundButton(Glyph.Mic, enabled, false, "composer_send") { AppModel.listen() }
            } else Pressable({ if (canSend) { onSend(text); text = "" } }, Modifier.size(Dim.touch).tourTarget("composer_send"), enabled = canSend, description = "Send") { pressed ->
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (canSend) c.ink.copy(alpha = if (pressed) 0.8f else 1f) else c.fill), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Send, if (canSend) c.onInk else c.textTertiary, size = 22.dp, weight = 2.25f)
                }
            }
        }
    }
}

/** A round button beside the message box. Shows a spinner while the camera or file picker is busy. */
@Composable
private fun RoundButton(glyph: Glyph, enabled: Boolean, busy: Boolean, target: String? = null, onClick: () -> Unit) {
    val c = MC.colors
    val description = when (glyph) { Glyph.Plus -> stringResource(R.string.scr_attach_a_photo_or_file); Glyph.Camera -> stringResource(R.string.scr_take_a_photo); Glyph.Mic -> stringResource(R.string.scr_speak_your_message); else -> "" }
    Pressable(onClick, Modifier.size(Dim.touch).let { if (target != null) it.tourTarget(target) else it }, enabled = enabled, description = description) { pressed ->
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (pressed) c.separator else c.fill), contentAlignment = Alignment.Center) {
            if (busy) Spinner(c.textSecondary, 18.dp) else Icon(glyph, if (enabled) c.text else c.textTertiary, size = 22.dp, weight = 1.9f)
        }
    }
}

/** What is waiting to be sent: a small picture or a file name, each with a way to take it off. */
@Composable
private fun AttachmentChip(a: Attached, onRemove: () -> Unit) {
    val c = MC.colors
    Box {
        if (a.photo) PhotoThumb(a.ref, 64.dp) {}
        else Row(Modifier.height(64.dp).widthIn(max = 200.dp).clip(RoundedCornerShape(12.dp)).background(c.fill).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyph.File, c.textSecondary, size = 20.dp)
            Spacer(Modifier.size(8.dp))
            Label(a.label, Type.subhead, c.text, maxLines = 2)
        }
        Pressable(onRemove, Modifier.align(Alignment.TopEnd).size(Dim.touch), description = stringResource(R.string.scr_remove_attachment)) { _ ->
            Box(Modifier.size(Dim.touch), contentAlignment = Alignment.TopEnd) { Box(Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(c.ink), contentAlignment = Alignment.Center) { Icon(Glyph.Close, c.onInk, size = 12.dp, weight = 2.4f) } }
        }
    }
}

// ---- Packs ---------------------------------------------------------------------------------

private fun telegramSummary(s: TelegramState) = when (s) {
    is TelegramState.Connected -> "Connected as @${s.username}"
    TelegramState.Connecting -> "Connecting"
    TelegramState.Waiting -> "Waiting for a connection"
    is TelegramState.Problem -> s.message
    TelegramState.Off -> "Needs a bot token"
}

private fun whatsappSummary(s: WhatsAppState) = when (s) {
    is WhatsAppState.Connected -> if (s.number.isBlank()) "Connected" else "Connected as ${s.number}"
    WhatsAppState.Connecting -> "Connecting"
    WhatsAppState.Waiting -> "Waiting for a connection"
    is WhatsAppState.Problem -> s.message
    WhatsAppState.Off -> "Needs setup"
}

private fun glyphFor(manifest: PackManifest) = when {
    manifest.id == CoreHost.TELEGRAM_PACK -> Glyph.Plane
    manifest.kind == "channel" -> Glyph.Chat
    else -> Glyph.Packs
}

private fun packSubtitle(m: PackManifest): String = when {
    m.id == CoreHost.TELEGRAM_PACK -> telegramSummary(CoreHost.telegram)
    m.id == CoreHost.WHATSAPP_PACK -> whatsappSummary(CoreHost.whatsapp)
    m.tools.isNotEmpty() -> if (m.tools.size == 1) "1 tool" else "${m.tools.size} tools"
    else -> m.summary
}

/** The permissions a pack declared, in words a person can read. */
private fun describe(p: Permission): String = when {
    p.scope == "net" -> "Connects to ${p.target}"
    p.scope == "secret" -> "Keeps a secret: ${p.target.split('_').joinToString(" ") { if (it.lowercase() in setOf("api", "url", "id")) it.uppercase() else it }}"
    p.scope == "device" -> com.pocketnode.core.DeviceOps.get(p.target)?.label ?: "Uses ${p.target}"
    p.scope == "device.camera" -> "Uses the camera"
    p.scope.startsWith("device.location") -> "Uses your location"
    p.scope.startsWith("device.contacts") -> "Reads your contacts"
    p.scope == "device.notify" -> "Shows notifications"
    else -> "Uses ${p.scope}"
}

@Composable
fun PacksScreen() {
    val c = MC.colors
    val installed = CoreHost.packs
    LaunchedEffect(Unit) { CoreHost.refreshPacks() }
    LazyColumn(Modifier.fillMaxSize().background(c.grouped), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        item { LargeTitleHeader(stringResource(R.string.scr_packs)) }
        item {
            Column(Modifier.padding(horizontal = Dim.margin)) {
                if (installed.isNotEmpty()) Group {
                    installed.forEachIndexed { i, p ->
                        ListRow(
                            p.manifest.displayName, subtitle = packSubtitle(p.manifest), leading = { Avatar(packGlyph(p.manifest.id, p.manifest.kind), 40.dp) }, chevron = true,
                            onClick = { AppModel.push(Route.Pack(p.manifest.id)) }, dividerInset = if (i == installed.lastIndex) null else 68.dp,
                        )
                    }
                } else Label(stringResource(R.string.scr_no_packs_installed), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

@Composable
fun PackScreen(id: String) {
    val c = MC.colors
    val pack = CoreHost.packs.firstOrNull { it.manifest.id == id }
    val busy = id in CoreHost.packBusy
    var confirm by remember { mutableStateOf(false) }
    var alsoData by remember { mutableStateOf(false) }
    var permissionError by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(id) { CoreHost.loadDeviceGrants(id) }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(pack?.manifest?.displayName ?: stringResource(R.string.scr_pack), onBack = { AppModel.pop() })
            if (pack == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Glyph.Packs, stringResource(R.string.scr_this_pack_is_not_installed), stringResource(R.string.scr_it_may_have_been_removed))
                }
            } else {
                val m = pack.manifest
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(packGlyph(m.id, m.kind), 56.dp)
                        Spacer(Modifier.size(16.dp))
                        Column {
                            Label(m.displayName, Type.title, c.text)
                            Label(packSubtitle(m), Type.subhead, if (m.id == CoreHost.TELEGRAM_PACK && CoreHost.telegram is TelegramState.Problem) c.error else c.textSecondary)
                        }
                    }
                    if (m.summary.isNotBlank()) { Spacer(Modifier.height(12.dp)); Label(m.summary, Type.callout, c.textSecondary, Modifier.padding(horizontal = 4.dp)) }
                    Spacer(Modifier.height(20.dp))
                    if (m.id == CoreHost.TELEGRAM_PACK) TelegramSection()
                    if (m.id == CoreHost.WHATSAPP_PACK) WhatsAppSection()
                    if (m.id != CoreHost.TELEGRAM_PACK && m.id != CoreHost.WHATSAPP_PACK) PackSecretsSection(m)
                    if (m.tools.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.scr_tools))
                        Group {
                            m.tools.forEachIndexed { i, t -> ListRow(t.name, subtitle = t.description, dividerInset = if (i == m.tools.lastIndex) null else 16.dp) }
                        }
                    }
                    SectionHeader(stringResource(R.string.scr_permissions))
                    val perms = m.declared().toList()
                    Group {
                        if (perms.isEmpty()) ListRow(stringResource(R.string.scr_needs_no_special_access), dividerInset = null)
                        perms.forEachIndexed { i, p ->
                            val divider = if (i == perms.lastIndex) null else 16.dp
                            val sensitive = p.scope == "device" && com.pocketnode.core.DeviceOps.isSensitive(p.target)
                            if (sensitive) {
                                val key = "${m.id}|${p.target}"
                                var asking by remember { mutableStateOf(false) }
                                ListRow(
                                    describe(p), dividerInset = divider,
                                    subtitle = if (asking) stringResource(R.string.scr_waiting_for_android) else if (key in CoreHost.deviceGrants) stringResource(R.string.scr_allowed) else stringResource(R.string.scr_off_until_you_allow_it),
                                    trailing = { Toggle(key in CoreHost.deviceGrants, { want -> asking = true; permissionError = null; CoreHost.setDevicePermission(m.id, p.target, want) { msg -> asking = false; permissionError = msg } }, enabled = !asking) },
                                )
                            } else ListRow(describe(p), dividerInset = divider)
                        }
                    }
                    permissionError?.let { Spacer(Modifier.height(8.dp)); InlineStatus(StatusKind.Error, it, Modifier.padding(horizontal = 4.dp)) }
                    SectionFooter(stringResource(R.string.scr_a_pack_can_only_use_what_it_lists_here_a))
                    SectionHeader(stringResource(R.string.scr_about))
                    Group {
                        ListRow(stringResource(R.string.scr_version), value = m.version)
                    }
                }
            }
        }
    }
}

/** Who may use the assistant through this chat app: people waiting for approval, and people already approved. */
@Composable
private fun ChatAccessSection(packId: String, appName: String) {
    val c = MC.colors
    var pending by remember { mutableStateOf<List<com.pocketnode.core.ChannelPerson>>(emptyList()) }
    var allowed by remember { mutableStateOf<List<com.pocketnode.core.ChannelPerson>>(emptyList()) }
    LaunchedEffect(packId) { while (true) { CoreHost.channelPeople(packId) { p, a -> pending = p; allowed = a }; kotlinx.coroutines.delay(2000) } }
    fun who(p: com.pocketnode.core.ChannelPerson) = p.name.ifBlank { "Chat ${p.chatId.take(12)}" }
    SectionHeader(stringResource(R.string.scr_who_can_use_it))
    if (pending.isNotEmpty()) {
        Group {
            pending.forEachIndexed { i, p ->
                ListRow(
                    who(p), subtitle = if (p.firstMessage.isBlank()) stringResource(R.string.scr_wants_to_use_your_assistant) else "Said: ${p.firstMessage}", dividerInset = if (i == pending.lastIndex) null else 16.dp,
                    trailing = { Row { SmallButton(stringResource(R.string.scr_approve), { CoreHost.approveChannelPerson(packId, p.chatId); pending = pending - p; allowed = allowed + p }); Spacer(Modifier.width(8.dp)); SmallButton(stringResource(R.string.scr_ignore), { CoreHost.dismissChannelPerson(packId, p.chatId); pending = pending - p }) } },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    Group {
        if (allowed.isEmpty()) ListRow(stringResource(R.string.scr_nobody_yet), subtitle = "Send your assistant a message from $appName, then approve yourself here.", dividerInset = null)
        allowed.forEachIndexed { i, p ->
            ListRow(who(p), subtitle = stringResource(R.string.scr_approved), dividerInset = if (i == allowed.lastIndex) null else 16.dp, trailing = { SmallButton(stringResource(R.string.scr_remove), { CoreHost.revokeChannelPerson(packId, p.chatId); allowed = allowed - p }) })
        }
    }
    SectionFooter(stringResource(R.string.scr_only_chats_you_approve_get_answers_anyon))
}

@Composable
private fun TelegramSection() {
    val c = MC.colors
    val state = CoreHost.telegram
    var token by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val connected = state is TelegramState.Connected || state is TelegramState.Problem || state == TelegramState.Connecting || state == TelegramState.Waiting
    if (!connected) {
        Field(stringResource(R.string.scr_bot_token), token, { token = it; error = null }, secure = true, placeholder = stringResource(R.string.scr_paste_the_token), imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = {})
        SectionFooter(stringResource(R.string.scr_create_a_bot_with_botfather_in_telegram))
        Spacer(Modifier.height(16.dp))
        if (checking) InlineStatus(StatusKind.Info, stringResource(R.string.scr_checking_the_token), working = true) else if (error != null) InlineStatus(StatusKind.Error, error!!)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(stringResource(R.string.scr_connect), {
            checking = true; error = null
            CoreHost.connectTelegram(token) { r -> checking = false; if (r is ProbeResult.Failed) error = r.reason else token = "" }
        }, enabled = token.isNotBlank() && !checking, loading = checking)
    } else {
        if (CoreHost.relayAvailable) {
            var switching by remember { mutableStateOf(false) }
            var relayError by remember { mutableStateOf<String?>(null) }
            Group {
                ListRow(
                    stringResource(R.string.scr_reliable_delivery),
                    subtitle = stringResource(R.string.scr_messages_wait_on_a_relay_while_your_phon),
                    dividerInset = null,
                    trailing = { if (switching) Spinner(c.textSecondary, 22.dp) else Toggle(CoreHost.config.relayOn, { want -> switching = true; relayError = null; CoreHost.setTelegramRelay(want) { msg -> switching = false; relayError = msg } }) },
                )
            }
            relayError?.let { Spacer(Modifier.height(8.dp)); InlineStatus(StatusKind.Error, it, Modifier.padding(horizontal = 4.dp)) }
            Spacer(Modifier.height(16.dp))
        }
        ChatAccessSection(CoreHost.TELEGRAM_PACK, stringResource(R.string.scr_telegram))
        Spacer(Modifier.height(16.dp))
        Group { ListRow(stringResource(R.string.scr_disconnect_bot), titleColor = c.error, onClick = { confirm = true }, dividerInset = null) }
    }
    if (confirm) ConfirmDialog(
        title = stringResource(R.string.scr_disconnect_telegram), message = stringResource(R.string.scr_your_assistant_will_stop_replying_in_tel_637),
        confirmLabel = stringResource(R.string.scr_disconnect), destructive = true, onConfirm = { confirm = false; CoreHost.disconnectTelegram() }, onDismiss = { confirm = false },
    )
}

@Composable
internal fun SmallButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = MC.colors
    Pressable(onClick, enabled = enabled) { pressed ->
        Box(Modifier.height(36.dp).pressScale(pressed).clip(RoundedCornerShape(18.dp)).background(c.accentSoft).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Label(text, Type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), if (enabled) c.accent else c.textTertiary)
        }
    }
}

private fun count(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

@Composable
fun SettingsScreen(showTitle: Boolean = true) {
    LaunchedEffect(Unit) { CoreHost.loadReminders(); CoreHost.loadMailAccount() }
    val c = MC.colors
    var confirm by remember { mutableStateOf(false) }
    var lockNote by remember { mutableStateOf<String?>(null) }
    var limitDialog by remember { mutableStateOf(false) }
    var oldDialog by remember { mutableStateOf(false) }
    var crashDialog by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    val ctxForCrash = LocalContext.current
    var oldPick by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var importPlan by remember { mutableStateOf<com.pocketnode.core.ImportPlan?>(null) }
    var erasing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(c.grouped), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp)) {
        if (showTitle) item { LargeTitleHeader(stringResource(R.string.scr_settings)) }
        item {
            Column(Modifier.padding(horizontal = Dim.margin)) {
                Group {
                    ListRow(
                        "Assistant", modifier = Modifier.tourTarget("settings_assistant"), subtitle = if (CoreHost.paused) "Switched off. Nothing runs, including Telegram and WhatsApp." else "On",
                        trailing = { Toggle(!CoreHost.paused, { on -> CoreHost.switchAssistant(!on) }) },
                    )
                    ListRow("Activity", modifier = Modifier.tourTarget("settings_activity"), subtitle = "What it did and what left this phone", chevron = true, onClick = { AppModel.push(Route.Activity) })
                    ListRow(stringResource(R.string.scr_take_the_tour), subtitle = stringResource(R.string.scr_a_quick_walk_through_the_buttons), chevron = true, onClick = { Tour.start() }, dividerInset = null)
                }
                Spacer(Modifier.height(12.dp))
                Group {
                    ListRow(stringResource(R.string.scr_model), value = CoreHost.modelSummary(), chevron = true, onClick = { AppModel.push(Route.ModelSettings) })
                    ListRow(stringResource(R.string.scr_daily_limit), value = CoreHost.usageSummary(), chevron = true, onClick = { limitDialog = true })
                    ListRow(
                        stringResource(R.string.scr_data_saver), subtitle = if (CoreHost.dataSaver) stringResource(R.string.scr_on_new_photos_are_made_smaller_before_th) else stringResource(R.string.scr_off_turn_on_to_send_smaller_photos_and_u),
                        trailing = { Toggle(CoreHost.dataSaver, { on -> CoreHost.chooseDataSaver(on) }) }, dividerInset = 16.dp,
                    )
                    if (com.pocketnode.app.AppFeatures.on("voice_call")) {
                        val ctx = LocalContext.current
                        androidx.compose.runtime.LaunchedEffect(Unit) { com.pocketnode.app.VoiceCall.loadKeep(ctx) }
                        ListRow(
                            "Keep what was said in calls", subtitle = if (com.pocketnode.app.VoiceCall.keep == com.pocketnode.app.VoiceCall.Keep.Words) "On. The words of each call stay on this phone, in Calls. The sound is never recorded." else "Off. A call is erased from this phone when it ends.",
                            trailing = { Toggle(com.pocketnode.app.VoiceCall.keep == com.pocketnode.app.VoiceCall.Keep.Words, { on -> com.pocketnode.app.VoiceCall.chooseKeep(ctx, if (on) com.pocketnode.app.VoiceCall.Keep.Words else com.pocketnode.app.VoiceCall.Keep.Nothing) }) }, dividerInset = 16.dp,
                        )
                    }
                }
                SectionFooter(stringResource(R.string.scr_the_daily_limit_counts_about_four_letter))
                Spacer(Modifier.height(12.dp))
                Group {
                    ListRow("Morning briefing", modifier = Modifier.tourTarget("settings_briefing"), value = LocalContext.current.let { ctx -> com.pocketnode.app.MorningBriefing.settings(ctx).let { if (it.enabled) it.timeText() else "Off" } }, chevron = true, onClick = { AppModel.push(Route.Briefing) })
                    ListRow(stringResource(R.string.scr_reminders), value = CoreHost.reminders.size.takeIf { it > 0 }?.toString(), chevron = true, onClick = { AppModel.push(Route.Reminders) })
                    ListRow(stringResource(R.string.scr_email_account), value = CoreHost.mailAccountInfo?.address ?: stringResource(R.string.scr_not_set_up), chevron = true, onClick = { AppModel.push(Route.MailAccount) })
                    if (com.pocketnode.app.AppFeatures.on("bank_alerts")) ListRow("Bank alerts", chevron = true, onClick = { AppModel.push(Route.BankAlerts) })
                    ListRow("Connected apps", chevron = true, onClick = { AppModel.push(Route.ConnectedApps) }, dividerInset = null)
                }
                SectionHeader(stringResource(R.string.scr_display))
                Group { ListRow(stringResource(R.string.scr_appearance), value = AppModel.theme.label, chevron = true, onClick = { AppModel.push(Route.Appearance) }, dividerInset = null) }
                SectionHeader(stringResource(R.string.scr_this_phone))
                Group { ListRow(stringResource(R.string.scr_performance_profile), value = CoreHost.tierName, dividerInset = null) }
                SectionFooter(stringResource(R.string.scr_chosen_from_this_phone_s_memory_it_sets))
                SectionHeader(stringResource(R.string.scr_background))
                val context = LocalContext.current
                Group {
                    ListRow(
                        stringResource(R.string.scr_notification_sounds), subtitle = stringResource(R.string.scr_reminders_the_morning_briefing_and_messa), chevron = true, dividerInset = 16.dp,
                        onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    )
                    ListRow(
                        stringResource(R.string.scr_battery_settings), subtitle = stringResource(R.string.scr_if_replies_stop_when_the_screen_is_off_a), chevron = true, dividerInset = null,
                        onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    )
                }
                SectionHeader(stringResource(R.string.scr_security))
                Group {
                    ListRow(
                        stringResource(R.string.scr_app_lock), subtitle = if (AppModel.appLock) stringResource(R.string.scr_on_asks_for_your_screen_lock_when_you_op) else stringResource(R.string.scr_ask_for_your_screen_lock_when_you_open_t),
                        trailing = { Toggle(AppModel.appLock, { on -> AppModel.toggleAppLock(on) { lockNote = it } }) },
                        onClick = { AppModel.toggleAppLock(!AppModel.appLock) { lockNote = it } },
                    )
                    ListRow(
                        stringResource(R.string.scr_block_screenshots), subtitle = stringResource(R.string.scr_also_hides_the_app_in_the_list_of_recent),
                        trailing = { Toggle(AppModel.blockScreenshots, { on -> AppModel.blockScreenshotsOn(on) }) }, dividerInset = null,
                        onClick = { AppModel.blockScreenshotsOn(!AppModel.blockScreenshots) },
                    )
                }
                lockNote?.let { SectionFooter(it) }
                SectionHeader(stringResource(R.string.scr_data))
                Group {
                    ListRow(stringResource(R.string.scr_export_my_data), subtitle = stringResource(R.string.scr_chats_reminders_notes_and_to_dos_in_one), chevron = true, onClick = { exporting = true })
                    ListRow(stringResource(R.string.scr_import_my_data), subtitle = stringResource(R.string.scr_bring_back_chats_reminders_and_notes_fro), chevron = true, onClick = { CoreHost.readImport { plan, problem -> if (plan != null) importPlan = plan else if (problem != null) exportNote = problem } })
                    ListRow(stringResource(R.string.scr_delete_all_conversations), titleColor = c.error, onClick = { confirm = true })
                    ListRow(stringResource(R.string.scr_delete_old_chats), value = com.pocketnode.core.AutoDelete.describe(CoreHost.autoDeleteDays).replaceFirstChar { it.uppercase() }, subtitle = stringResource(R.string.scr_pinned_chats_stay_photos_and_files_attac), onClick = { oldDialog = true })
                    ListRow(stringResource(R.string.scr_erase_my_data), titleColor = c.error, subtitle = stringResource(R.string.scr_chats_files_photos_reminders_notes_and_t), onClick = { erasing = true })
                    ListRow(stringResource(R.string.scr_reset_the_app), titleColor = c.error, subtitle = stringResource(R.string.scr_everything_including_your_model_key_the), onClick = { resetting = true }, dividerInset = null)
                }
                exportNote?.let { SectionFooter(it) }
                SectionHeader(stringResource(R.string.scr_about))
                val crashes = remember(crashDialog) { com.pocketnode.app.CrashLog.count(ctxForCrash) }
                Group {
                    ListRow(stringResource(R.string.scr_version), value = "0.1.0", dividerInset = 16.dp)
                    ListRow(stringResource(R.string.scr_what_s_new), chevron = true, onClick = { newDialog = true }, dividerInset = if (crashes > 0) 16.dp else null)
                    if (crashes > 0) ListRow(stringResource(R.string.scr_crash_reports), value = crashes.toString(), subtitle = stringResource(R.string.scr_kept_on_this_phone_nothing_is_sent_unles), chevron = true, onClick = { crashDialog = true }, dividerInset = null)
                }
                SectionFooter(stringResource(R.string.scr_your_keys_are_stored_in_the_android_keys))
            }
        }
    }
    if (limitDialog) ActionDialog(
        stringResource(R.string.scr_daily_limit),
        com.pocketnode.core.UsageMeter.CHOICES.map { v -> DialogAction((if (v == CoreHost.usageLimit) stringResource(R.string.scr_now) else "") + com.pocketnode.core.UsageMeter.describe(v).replaceFirstChar { it.uppercase() }) { CoreHost.chooseUsageLimit(v); limitDialog = false } },
        { limitDialog = false },
    )
    importResult?.let { ReadDialog("Import finished", it) { importResult = null } }
    if (newDialog) ReadDialog(stringResource(R.string.scr_what_s_new), com.pocketnode.core.Changelog.text()) { newDialog = false }
    if (crashDialog) ActionDialog(
        stringResource(R.string.scr_crash_reports),
        listOf(
            DialogAction(stringResource(R.string.scr_share_the_latest_report)) { com.pocketnode.app.CrashLog.share(ctxForCrash); crashDialog = false },
            DialogAction(stringResource(R.string.scr_delete_all_reports), destructive = true) { com.pocketnode.app.CrashLog.deleteAll(ctxForCrash); crashDialog = false },
        ),
        { crashDialog = false },
    )
    if (oldDialog) ActionDialog(
        stringResource(R.string.scr_delete_old_chats),
        com.pocketnode.core.AutoDelete.CHOICES.map { v -> DialogAction((if (v == CoreHost.autoDeleteDays) stringResource(R.string.scr_now) else "") + com.pocketnode.core.AutoDelete.describe(v).replaceFirstChar { it.uppercase() }) {
            oldDialog = false
            // Say how many chats would go before agreeing, so a choice never deletes a surprise.
            CoreHost.oldChatCount(v) { n -> if (n > 0) oldPick = v to n else CoreHost.chooseAutoDelete(v) }
        } },
        { oldDialog = false },
    )
    oldPick?.let { (days, n) -> ConfirmDialog(
        title = "Delete $n old ${if (n == 1) "chat" else "chats"} now?",
        message = "${if (n == 1) stringResource(R.string.scr_one_chat_has) else "$n chats have"} not been used for ${if (days == 7) "a week" else if (days == 365) "a year" else "$days days"}. ${if (n == 1) stringResource(R.string.scr_it) else stringResource(R.string.scr_they)} will be deleted now, with the photos and files attached, and anything that gets this old later is deleted too. Pinned chats stay. This cannot be undone.",
        confirmLabel = stringResource(R.string.scr_delete), destructive = true, onConfirm = { oldPick = null; CoreHost.chooseAutoDelete(days) }, onDismiss = { oldPick = null },
    ) }
    if (erasing) ConfirmDialog(
        title = stringResource(R.string.scr_erase_your_data), message = stringResource(R.string.scr_this_removes_every_chat_file_photo_remin),
        confirmLabel = stringResource(R.string.scr_erase), destructive = true, onConfirm = { erasing = false; AppModel.eraseData { exportNote = it } }, onDismiss = { erasing = false },
    )
    if (resetting) {
        val ctx = LocalContext.current
        ConfirmDialog(
            title = stringResource(R.string.scr_reset_the_app_846), message = stringResource(R.string.scr_this_removes_everything_the_app_stores_i),
            confirmLabel = "Reset", destructive = true, onConfirm = { resetting = false; if (!CoreHost.resetApp(ctx)) exportNote = "Android would not reset the app. Clear its storage in the phone's settings instead." }, onDismiss = { resetting = false },
        )
    }
    importPlan?.let { plan ->
        ConfirmDialog(
            title = stringResource(R.string.scr_import_this_file),
            message = "It holds ${count(plan.chats.size, "chat")}, ${count(plan.reminders.size, "reminder")}, ${count(plan.notes.notes.size, "note")} and ${count(plan.notes.todos.size, "to-do")}${plan.packs.keys.mapNotNull { com.pocketnode.core.PackData.spec(it)?.title }.takeIf { it.isNotEmpty() }?.let { ", and data from: ${it.joinToString(", ")}" }.orEmpty()}. Nothing you already have is changed or replaced, and reminders that are already past are skipped. Photos and attached files are not in an export.",
            confirmLabel = stringResource(R.string.scr_import), onConfirm = { importPlan = null; CoreHost.applyImport(plan) { msg -> exportNote = msg; importResult = msg; AppModel.refreshChats() } }, onDismiss = { importPlan = null },
        )
    }
    if (exporting) ConfirmDialog(
        title = stringResource(R.string.scr_export_your_data), message = stringResource(R.string.scr_you_choose_where_to_save_one_zip_file_wi),
        confirmLabel = stringResource(R.string.scr_choose_where), onConfirm = { exporting = false; CoreHost.exportData { exportNote = it } }, onDismiss = { exporting = false },
    )
    if (confirm) ConfirmDialog(
        title = stringResource(R.string.scr_delete_all_conversations_862), message = stringResource(R.string.scr_this_removes_every_chat_from_this_phone),
        confirmLabel = stringResource(R.string.scr_delete), destructive = true, onConfirm = { confirm = false; AppModel.clearConversations() }, onDismiss = { confirm = false },
    )
}

@Composable
fun AppearanceScreen() {
    val c = MC.colors
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.scr_appearance), onBack = { AppModel.pop() })
            Column(Modifier.padding(horizontal = Dim.margin).padding(top = 8.dp)) {
                Group {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        ChoiceRow(mode.label, AppModel.theme == mode, { AppModel.chooseTheme(mode) }, dividerInset = if (i == ThemeMode.entries.lastIndex) null else 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun ModelSettingsScreen() {
    val c = MC.colors
    val state = remember { ModelFormState(CoreHost.config.provider) }
    var forgetKey by remember { mutableStateOf(false) }
    if (forgetKey) ConfirmDialog(
        title = stringResource(R.string.scr_forget_your_key), message = stringResource(R.string.scr_the_key_is_deleted_from_this_phone_and_t),
        confirmLabel = stringResource(R.string.scr_forget), destructive = true, onConfirm = { forgetKey = false; CoreHost.forgetModelKeys(); AppModel.pop() }, onDismiss = { forgetKey = false },
    )
    Frame(c.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(stringResource(R.string.scr_model), onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(top = 8.dp, bottom = 24.dp)) {
                ModelFormFields(state, showTier = true, onSubmit = { state.submit { AppModel.pop() } })
                if (CoreHost.hasModel()) {
                    Spacer(Modifier.height(16.dp))
                    TextAction(stringResource(R.string.scr_disconnect_model), { CoreHost.disconnectModel(); AppModel.pop() }, color = c.error)
                    Spacer(Modifier.height(4.dp))
                    TextAction(stringResource(R.string.scr_forget_my_key), { forgetKey = true }, color = c.error)
                    Label(stringResource(R.string.scr_disconnect_keeps_your_key_so_you_can_swi), Type.footnote, c.textSecondary, Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Dim.margin).padding(top = 8.dp, bottom = 16.dp)) {
                StatusLine(state)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(stringResource(R.string.scr_save), { state.submit { AppModel.pop() } }, enabled = state.canSubmit, loading = state.checking)
            }
        }
    }
}

@Composable
private fun WhatsAppSection() {
    val c = MC.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val setup = CoreHost.whatsappSetup
    var token by remember { mutableStateOf("") }
    var phoneId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    fun copy(text: String) {
        (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("WhatsApp", text))
    }
    if (setup == null) {
        if (!CoreHost.relayAvailable) {
            InlineStatus(StatusKind.Info, stringResource(R.string.scr_whatsapp_needs_the_relay_which_is_not_av))
        } else {
            Field(stringResource(R.string.scr_access_token), token, { token = it; error = null }, secure = true, placeholder = stringResource(R.string.scr_from_your_meta_app))
            Spacer(Modifier.height(12.dp))
            Field(stringResource(R.string.scr_phone_number_id), phoneId, { phoneId = it; error = null }, placeholder = stringResource(R.string.scr_from_whatsapp_setup_in_meta))
            Spacer(Modifier.height(12.dp))
            Field(stringResource(R.string.scr_app_secret), appSecret, { appSecret = it; error = null }, secure = true, placeholder = stringResource(R.string.scr_app_settings_basic), imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = {})
            SectionFooter(stringResource(R.string.scr_uses_the_official_whatsapp_business_clou))
            Spacer(Modifier.height(16.dp))
            if (working) InlineStatus(StatusKind.Info, stringResource(R.string.scr_checking_the_details), working = true) else if (error != null) InlineStatus(StatusKind.Error, error!!)
            Spacer(Modifier.height(12.dp))
            PrimaryButton(stringResource(R.string.scr_connect), {
                working = true; error = null
                CoreHost.connectWhatsApp(token, phoneId, appSecret) { msg -> working = false; if (msg != null) error = msg else { token = ""; phoneId = ""; appSecret = "" } }
            }, enabled = token.isNotBlank() && phoneId.isNotBlank() && appSecret.isNotBlank() && !working, loading = working)
        }
    } else {
        SectionHeader(stringResource(R.string.scr_finish_in_meta))
        Group {
            ListRow(stringResource(R.string.scr_callback_url), subtitle = setup.callbackUrl, dividerInset = 16.dp, onClick = { copy(setup.callbackUrl) })
            ListRow(stringResource(R.string.scr_verify_token), subtitle = setup.verifyToken, dividerInset = null, onClick = { copy(setup.verifyToken) })
        }
        SectionFooter(stringResource(R.string.scr_in_your_meta_app_open_whatsapp_configura))
        Spacer(Modifier.height(16.dp))
        ChatAccessSection(CoreHost.WHATSAPP_PACK, "WhatsApp")
        Spacer(Modifier.height(16.dp))
        Group { ListRow(stringResource(R.string.scr_disconnect_whatsapp), titleColor = c.error, onClick = { confirm = true }, dividerInset = null) }
    }
    if (confirm) ConfirmDialog(
        title = stringResource(R.string.scr_disconnect_whatsapp_960), message = stringResource(R.string.scr_your_assistant_will_stop_replying_in_wha),
        confirmLabel = stringResource(R.string.scr_disconnect), destructive = true, onConfirm = { confirm = false; CoreHost.disconnectWhatsApp() }, onDismiss = { confirm = false },
    )
}

/** Fields for the keys a pack asks for (for example a search service's API key). Values are kept in the Keystore and never shown again. */
@Composable
private fun PackSecretsSection(m: PackManifest) {
    val names = m.permissions["secret"].orEmpty()
    if (names.isEmpty()) return
    val c = MC.colors
    androidx.compose.runtime.LaunchedEffect(m.id) { CoreHost.loadPackSecrets(m.id) }
    SectionHeader(stringResource(R.string.scr_settings))
    names.forEachIndexed { i, name ->
        val key = "${m.id}|$name"
        val saved = key in CoreHost.packSecretsSet
        var value by remember(m.id, name) { mutableStateOf("") }
        Field(
            name.split('_').joinToString(" ") { if (it.lowercase() in setOf("api", "url", "id")) it.uppercase() else it }.replaceFirstChar { it.uppercase() }, value, { value = it }, secure = true,
            placeholder = if (saved) stringResource(R.string.scr_saved_leave_empty_to_keep_it) else stringResource(R.string.scr_paste_it_here),
            imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = {},
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton(stringResource(R.string.scr_save), { CoreHost.setPackSecret(m.id, name, value); value = "" }, enabled = value.isNotBlank())
            if (saved) SmallButton(stringResource(R.string.scr_remove), { CoreHost.setPackSecret(m.id, name, "") })
        }
        if (i != names.lastIndex) Spacer(Modifier.height(12.dp))
    }
    if (m.setup.isNotBlank()) SectionFooter(m.setup)
    Spacer(Modifier.height(8.dp))
}

/** Things worth trying, shown in an empty chat. Only what the installed packs can really do is offered. */
@Composable
private fun Suggestions() {
    val c = MC.colors
    val have = CoreHost.packs.map { it.manifest.id }.toSet()
    val ideas = buildList {
        if ("com.pocketnode.weather" in have) add(stringResource(R.string.scr_what_is_the_weather_like_today))
        if ("com.pocketnode.reminders" in have) add(stringResource(R.string.scr_remind_me_to_stretch_in_30_minutes))
        if ("com.pocketnode.phone" in have) add(stringResource(R.string.scr_turn_on_the_flashlight))
        if ("com.pocketnode.notes" in have) add(stringResource(R.string.scr_save_a_note_buy_milk_and_eggs))
        if ("com.pocketnode.documents" in have) add(stringResource(R.string.scr_make_a_one_page_pdf_of_a_packing_list_fo))
        if ("com.pocketnode.math" in have) add(stringResource(R.string.scr_how_many_days_until_25_december))
        add(stringResource(R.string.scr_what_can_you_help_me_with))
    }.take(5)
    Spacer(Modifier.height(24.dp))
    Column(Modifier.padding(horizontal = Dim.margin).widthIn(max = 420.dp).tourTarget("suggestions"), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ideas.forEach { idea ->
            Pressable({ AppModel.send(idea) }, Modifier.clip(RoundedCornerShape(18.dp)).background(c.fill)) {
                Box(Modifier.defaultMinSize(minHeight = Dim.touch).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { Label(idea, Type.subhead, c.accent) }
            }
        }
    }
}


// ---- Calls ---------------------------------------------------------------------------------

/** Voice calls, kept apart from typed chats. Open one to read what was said; the menu renames or deletes it. */
@Composable
fun CallsScreen() {
    val c = MC.colors
    val context = LocalContext.current
    val rows = AppModel.chats.filter { it.isCall }
    var actionsFor by remember { mutableStateOf<ChatRow?>(null) }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar("Calls", onBack = { AppModel.pop() })
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp)) {
                if (rows.isEmpty()) item {
                    EmptyState(Glyph.Voice, "No calls yet", "Tap the voice button on the Chats page to talk with your assistant. The words of each call are kept here.") {
                        PrimaryButton("Start a call", { AppModel.push(Route.Call) }, Modifier.widthIn(max = 200.dp))
                    }
                } else {
                    item { Label("Only the words are kept, as text on this phone. The sound is not recorded.", Type.footnote, c.textSecondary, Modifier.padding(horizontal = Dim.margin + 4.dp, vertical = 8.dp)) }
                    itemsIndexed(rows, key = { _, row -> row.id }) { _, row ->
                        Box(Modifier.padding(horizontal = Dim.margin, vertical = 3.dp).fillMaxWidth()) {
                            ChatListRow(row, timeLabel(context, row.activityMillis), row.id in AppModel.busy, onMore = { actionsFor = row })
                        }
                    }
                }
            }
        }
    }
    actionsFor?.let { row -> ChatActions(row, onClose = { actionsFor = null }) }
}
