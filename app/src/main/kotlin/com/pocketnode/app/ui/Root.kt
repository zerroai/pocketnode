package com.pocketnode.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.Route
import kotlinx.coroutines.delay

private val tabs = listOf(TabItem("Chats", Glyph.Chat), TabItem("Files", Glyph.Files), TabItem("Packs", Glyph.Packs)) + (if (com.pocketnode.app.AppFeatures.on("agents")) listOf(TabItem("Agents", Glyph.Agents)) else emptyList())

@Composable
fun AppRoot() {
    PocketnodeTheme(AppModel.theme) {
        val c = MC.colors
        // While locked nothing of the app is built, so there is nothing behind the lock for a screen reader or a screenshot to find.
        if (AppModel.locked) { LockScreen(); return@PocketnodeTheme }
        BackHandler(enabled = AppModel.stack.size > 1) { AppModel.pop() }
        BackHandler(enabled = Tour.active) { Tour.end() }
        // Telegram messages arrive while the app is open; keep the lists current.
        LaunchedEffect(AppModel.current) {
            while (true) {
                if (AppModel.current is Route.Main || AppModel.current is Route.Thread) { AppModel.refreshChats(); AppModel.refreshThread() }
                delay(1500)
            }
        }
        // On a tablet or a phone turned sideways the screens keep a readable width, centred, instead of stretching edge to edge.
        Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        AnimatedContent(
            targetState = AppModel.current,
            modifier = Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().background(c.background),
            transitionSpec = {
                if (AppModel.forward) {
                    (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { -it / 10 } + fadeOut(tween(200)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 10 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(200)))
                }
            },
            label = "route",
        ) { route ->
            when (route) {
                Route.Welcome -> WelcomeScreen()
                Route.SetupModel -> SetupModelScreen()
                Route.SetupFinish -> SetupFinishScreen()
                Route.Main -> MainTabs()
                is Route.Thread -> ThreadScreen(route.sessionId)
                Route.ModelSettings -> ModelSettingsScreen()
                Route.Profile -> ProfileScreen()
                Route.Settings -> SettingsRouteScreen()
                Route.Appearance -> AppearanceScreen()
                is Route.Pack -> PackScreen(route.id)
                Route.MailAccount -> MailAccountScreen()
                Route.Reminders -> RemindersScreen()
                Route.Activity -> ActivityScreen()
                Route.Briefing -> BriefingScreen()
                Route.ConnectedApps -> ConnectedAppsScreen()
                Route.BankAlerts -> BankAlertsScreen()
                is Route.Agent -> AgentScreen(route.id)
                is Route.AgentBuilder -> AgentBuilderScreen(route.id)
                is Route.AgentCanvas -> AgentCanvasScreen(route.id)
                Route.Call -> CallScreen()
                Route.Calls -> CallsScreen()
            }
        }
        TourOverlay()
        }
        CoreHost.pendingAsk?.let { ask ->
            ConfirmDialog(ask.title, ask.message, ask.allow, onConfirm = { CoreHost.answerAsk(true) }, onDismiss = { CoreHost.answerAsk(false) }, cancelLabel = ask.deny)
        }
    }
}

/** Shown instead of the app until the phone's own screen lock has been passed. */
@Composable
private fun LockScreen() {
    val c = MC.colors
    // Ask once as soon as the lock appears; the button asks again if it was dismissed.
    LaunchedEffect(Unit) { delay(350); AppModel.requestUnlock() }
    Box(Modifier.fillMaxSize().background(c.background).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(56.dp, c.text)
            Spacer(Modifier.height(24.dp))
            Label("Pocketnode is locked", Type.title, c.text, align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Label("Use your phone's screen lock to open it.", Type.subhead, c.textSecondary, align = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Unlock", { AppModel.requestUnlock() }, Modifier.widthIn(max = 260.dp))
        }
    }
}

@Composable
private fun MainTabs() {
    val c = MC.colors
    // Right after first-time setup, offer the tour once.
    LaunchedEffect(AppModel.tourPending) { if (AppModel.tourPending) { delay(700); if (AppModel.tourPending) { Tour.start() } } }
    Box(Modifier.fillMaxSize().background(c.grouped), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxSize().widthIn(max = Dim.maxContent)) {
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                when (AppModel.tab) {
                    0 -> ChatsScreen()
                    1 -> FilesScreen()
                    2 -> PacksScreen()
                    else -> AgentsScreen()
                }
            }
            // The bar floats over the lists, which leave room for it at the bottom.
            TabBar(tabs, AppModel.tab, { AppModel.tab = it }, Modifier.align(Alignment.BottomCenter))
        }
    }
}
