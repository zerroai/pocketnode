package com.pocketnode.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.pocketnode.app.R
import com.pocketnode.app.AppModel
import com.pocketnode.app.AppToolApps
import com.pocketnode.app.CoreHost
import com.pocketnode.app.FoundApp
import com.pocketnode.core.AppToolParse

private fun effectText(e: String) = when (e) { "read" -> "reads only"; "change" -> "changes things in the app"; else -> "spends, sends or cannot be undone; asks you each time" }

/** Other apps on the phone that offer tools to the assistant. Each stays off until the owner turns it on here. */
@Composable
fun ConnectedAppsScreen() {
    val c = MC.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<FoundApp>?>(null) }
    var version by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<FoundApp?>(null) }
    LaunchedEffect(version) { AppToolApps.refresh(); apps = AppToolApps.discover(context) }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.ca_connected_apps), onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                val list = apps
                when {
                    list == null -> Spinner(c.textSecondary, 24.dp)
                    list.isEmpty() -> Label(stringResource(R.string.ca_no_apps_on_this_phone_offer_tools), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
                    else -> {
                        Group {
                            list.forEachIndexed { i, a ->
                                val ok = a.parse as? AppToolParse.Ok
                                val on = AppToolApps.isOn(context, a)
                                val sub = when {
                                    ok == null -> stringResource(R.string.ca_cannot_be_used) + (a.parse as AppToolParse.Bad).problems.first()
                                    AppToolApps.changed(context, a) -> stringResource(R.string.ca_changed_since_you_turned_it_on_tur)
                                    else -> ok.manifest.tools.size.let { if (it == 1) "1 tool" else "$it tools" } + if (on) ", on" else ", off"
                                }
                                ListRow(a.label, subtitle = sub, dividerInset = if (i == list.lastIndex) null else 16.dp,
                                    trailing = { if (ok != null) Toggle(on, { want -> if (want) confirm = a else { AppToolApps.setOn(context, a, false, { id, p -> CoreHost.grantApp(id, p) }, { id, p -> CoreHost.revokeApp(id, p) }); version++ } }) })
                            }
                        }
                        SectionFooter(stringResource(R.string.ca_an_app_stays_off_until_you_turn_it))
                    }
                }
            }
        }
    }
    confirm?.let { a ->
        val tools = (a.parse as AppToolParse.Ok).manifest.tools
        ConfirmDialog(
            "Turn on ${a.label}?",
            "The assistant will be able to use ${a.label}:\n" + tools.joinToString("\n") { "- ${it.description.take(80)} (${effectText(it.effect)})" },
            stringResource(R.string.ca_turn_on), { AppToolApps.setOn(context, a, true, { id, p -> CoreHost.grantApp(id, p) }, { id, p -> CoreHost.revokeApp(id, p) }); confirm = null; version++ }, { confirm = null },
        )
    }
}
