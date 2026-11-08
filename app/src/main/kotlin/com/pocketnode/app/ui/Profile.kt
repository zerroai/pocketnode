package com.pocketnode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.Route

// The profile page, opened from the round button at the top right of each tab. Settings and the other menus are listed here.

@Composable
fun ProfileScreen() {
    val c = MC.colors
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar("Profile", onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Glyph.Person, c.accent, c.accentSoft, 72.dp)
                    Spacer(Modifier.height(10.dp))
                    Label("This phone", Type.headline, c.text)
                    Label(if (CoreHost.paused) "Assistant switched off" else "Assistant on", Type.subhead, c.textSecondary)
                }
                Group {
                    ListRow("Settings", subtitle = "Assistant, model, email, briefing, data", chevron = true, onClick = { AppModel.push(Route.Settings) }, leading = { IconTile(Glyph.Sliders, c.accent, c.accentSoft) })
                    ListRow("Activity", subtitle = "What it did and what left this phone", chevron = true, onClick = { AppModel.push(Route.Activity) }, leading = { IconTile(Glyph.Clock, c.accent, c.accentSoft) })
                    if (com.pocketnode.app.AppFeatures.on("voice_call")) ListRow("Calls", subtitle = "Voice calls and what was said", chevron = true, onClick = { AppModel.push(Route.Calls) }, leading = { IconTile(Glyph.Voice, c.accent, c.accentSoft) })
                    ListRow("Connected apps", subtitle = "Apps that give the assistant tools", chevron = true, onClick = { AppModel.push(Route.ConnectedApps) }, leading = { IconTile(Glyph.Link, c.accent, c.accentSoft) })
                    ListRow("Appearance", value = AppModel.theme.label, chevron = true, onClick = { AppModel.push(Route.Appearance) }, leading = { IconTile(Glyph.Sun, c.accent, c.accentSoft) })
                    ListRow("Take the tour", subtitle = "A quick walk through the buttons", chevron = true, onClick = { Tour.start() }, dividerInset = null, leading = { IconTile(Glyph.Bolt, c.accent, c.accentSoft) })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Settings opened from the profile page: the same list that used to be a tab, with a back button. */
@Composable
fun SettingsRouteScreen() {
    val c = MC.colors
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar("Settings", onBack = { AppModel.pop() })
            Box(Modifier.weight(1f)) { SettingsScreen(showTitle = false) }
        }
    }
}
