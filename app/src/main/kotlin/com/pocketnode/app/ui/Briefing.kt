package com.pocketnode.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.MorningBriefing
import com.pocketnode.core.BriefingSettings

private val times = listOf(6 to 0, 6 to 30, 7 to 0, 7 to 30, 8 to 0, 8 to 30, 9 to 0)

/** A short summary of the day, prepared each morning and delivered as a notification. Off until switched on. */
@Composable
fun BriefingScreen() {
    val c = MC.colors
    val context = LocalContext.current
    var s by remember { mutableStateOf(MorningBriefing.settings(context)) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val update = { next: BriefingSettings -> s = next; MorningBriefing.save(context, next) }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar("Morning briefing", onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Group {
                    ListRow("Send me a briefing each morning", trailing = { Toggle(s.enabled, { on -> update(s.copy(enabled = on)) }) }, dividerInset = null)
                }
                SectionHeader("Time")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    times.forEach { (h, m) ->
                        val chosen = s.hour == h && s.minute == m
                        SmallButton(BriefingSettings(hour = h, minute = m).timeText() + if (chosen) ", chosen" else "", { update(s.copy(hour = h, minute = m)) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Field("Your place, for the weather", s.place, { update(s.copy(place = it.take(80))) }, placeholder = "Lagos", imeAction = ImeAction.Done)
                SectionFooter("It includes the weather for that place and the reminders waiting for today. Leave the place empty to skip the weather.")
                Spacer(Modifier.height(8.dp))
                SecondaryButton(if (testing) "Preparing" else "Send me one now", {
                    testing = true; result = null
                    MorningBriefing.run(context) { id, text ->
                        if (id != null && text.isNotBlank()) MorningBriefing.notify(context, id, text)
                        testing = false
                        result = if (id != null && text.isNotBlank()) "Sent. Look for the notification." else if (CoreHost.paused) "The assistant is switched off." else if (!CoreHost.hasModel()) "Connect a model first." else "It could not be prepared. Try again."
                    }
                }, enabled = !testing)
                result?.let { Spacer(Modifier.height(8.dp)); Label(it, Type.footnote, c.textSecondary, Modifier.padding(horizontal = 4.dp)) }
                SectionFooter("Each briefing sends your place and your reminders for the day to your model provider, once. Nothing is sent while the assistant is switched off. Android may deliver it a few minutes late.")
            }
        }
    }
}
