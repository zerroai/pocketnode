package com.pocketnode.app.ui

import android.content.Intent
import android.provider.Settings
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
import com.pocketnode.app.BankAlertSettings

/** Lets the assistant answer "what did I spend today" from the bank alerts on this phone. Off until the owner turns it on and picks the apps. */
@Composable
fun BankAlertsScreen() {
    val c = MC.colors
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val on = remember(version) { BankAlertSettings.enabled(context) }
    val sources = remember(version) { BankAlertSettings.sources(context) }
    val granted = remember(version) { BankAlertSettings.accessGranted(context) }
    fun label(pkg: String) = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.ba_bank_alerts), onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Group {
                    ListRow(stringResource(R.string.ba_read_bank_alerts), subtitle = if (on) "On" else "Off", trailing = { Toggle(on, { BankAlertSettings.setEnabled(context, it); version++ }) }, dividerInset = 16.dp)
                    ListRow(stringResource(R.string.ba_notification_access), subtitle = if (granted) stringResource(R.string.ba_allowed_in_android) else stringResource(R.string.ba_not_allowed_yet_tap_to_open_androi), chevron = true,
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, dividerInset = null)
                }
                SectionHeader(stringResource(R.string.ba_apps_to_read))
                Group {
                    sources.sorted().forEach { p -> ListRow(label(p), subtitle = p, trailing = { SmallButton(stringResource(R.string.ba_remove), { BankAlertSettings.removeSource(context, p); version++ }) }) }
                    ListRow(stringResource(R.string.ba_add_an_app), chevron = true, onClick = { searching = true }, dividerInset = null)
                }
                SectionFooter(stringResource(R.string.ba_only_notifications_from_these_apps))
                Spacer(Modifier.height(12.dp))
                Group { ListRow(stringResource(R.string.ba_delete_saved_alerts), titleColor = c.error, onClick = { confirmClear = true }, dividerInset = null) }
            }
        }
    }
    if (searching) PromptDialog(stringResource(R.string.ba_part_of_the_app_s_name), "", stringResource(R.string.ba_search), { q ->
        searching = false
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinct()
        matches = apps.filter { it.second.contains(q.trim(), ignoreCase = true) && it.first !in sources }.sortedBy { it.second.lowercase() }.take(6)
    }, { searching = false })
    matches?.let { m ->
        if (m.isEmpty()) ReadDialog(stringResource(R.string.ba_no_match), stringResource(R.string.ba_no_app_with_that_name_was_found_or)) { matches = null }
        else ActionDialog(stringResource(R.string.ba_which_app), m.map { (pkg, name) -> DialogAction(name) { BankAlertSettings.addSource(context, pkg); matches = null; version++ } } + DialogAction(stringResource(R.string.ba_cancel)) { matches = null }, { matches = null })
    }
    if (confirmClear) ConfirmDialog(stringResource(R.string.ba_delete_saved_alerts_2), stringResource(R.string.ba_the_alerts_kept_on_this_phone_are), stringResource(R.string.ba_delete), { confirmClear = false; com.pocketnode.app.CoreHost.clearBankAlerts() }, { confirmClear = false }, destructive = true)
}
