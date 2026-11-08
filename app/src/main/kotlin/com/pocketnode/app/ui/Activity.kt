package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.core.ActivityEntry
import com.pocketnode.core.ActivityKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun whenText(at: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return SimpleDateFormat(if (at >= today) "HH:mm:ss" else "d MMM, HH:mm", Locale.US).format(Date(at))
}

/** What the assistant did, newest first: tools it used, requests that left the phone, questions it asked you, and changes to what it may do. */
@Composable
fun ActivityScreen() {
    val c = MC.colors
    var filter by remember { mutableStateOf<ActivityKind?>(null) }
    var rows by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<ActivityKind, Int>>(emptyMap()) }
    var confirmClear by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var problemsOnly by remember { mutableStateOf(false) }
    var savedNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(filter, query, problemsOnly) {
        while (true) {
            val f = filter; val q = query; val p = problemsOnly
            val dayStart = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
            val (r, n) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CoreHost.activity.recent(200, f, q, p) to CoreHost.activity.counts(dayStart) }
            rows = r; counts = n
            kotlinx.coroutines.delay(2500)
        }
    }
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.act_activity), onBack = { AppModel.pop() }, trailing = {
                Pressable({ if (searching) { searching = false; query = "" } else searching = true }, Modifier.size(Dim.touch), description = stringResource(R.string.act_search_the_record)) { pressed ->
                    Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Search, if (pressed) c.textSecondary else c.accent, size = 24.dp, weight = 2f) }
                }
            })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                val toolN = counts[ActivityKind.TOOL] ?: 0; val netN = (counts[ActivityKind.NETWORK] ?: 0); val modelN = counts[ActivityKind.MODEL] ?: 0
                Label("Today: $toolN tool ${if (toolN == 1) "use" else "uses"}, $modelN ${if (modelN == 1) "request" else "requests"} to your model provider, $netN ${if (netN == 1) "request" else "requests"} from packs to websites.", Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(12.dp))
                if (searching) {
                    SearchBar(query, { query = it }, { searching = false; query = "" }, placeholder = stringResource(R.string.act_search_the_record))
                    Spacer(Modifier.height(12.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    SmallButton(if (filter == null) stringResource(R.string.act_all_shown) else stringResource(R.string.act_all), { filter = null })
                    ActivityKind.entries.forEach { k -> SmallButton(if (filter == k) "${k.label}, shown" else k.label, { filter = k }) }
                    SmallButton(if (problemsOnly) stringResource(R.string.act_problems_only_shown) else stringResource(R.string.act_problems_only), { problemsOnly = !problemsOnly })
                }
                Spacer(Modifier.height(12.dp))
                if (rows.isEmpty()) {
                    Label(if (query.isNotBlank() || problemsOnly || filter != null) stringResource(R.string.act_nothing_matches) else stringResource(R.string.act_nothing_yet_what_the_assistant_does_will), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
                } else {
                    Group {
                        rows.forEachIndexed { i, e ->
                            ListRow(
                                e.subject + (if (e.times > 1) "  x${e.times}" else ""), subtitle = listOf(e.detail).filter { it.isNotBlank() }.firstOrNull(),
                                value = whenText(e.at), dividerInset = if (i == rows.lastIndex) null else 16.dp,
                                leading = { Label(if (e.problem) "!" else when (e.kind) { ActivityKind.TOOL -> "T"; ActivityKind.NETWORK -> "N"; ActivityKind.MODEL -> "M"; ActivityKind.ASKED -> "?"; ActivityKind.SYSTEM -> "S" }, Type.headline, if (e.problem) c.error else c.accent) },
                            )
                        }
                    }
                    SectionFooter(stringResource(R.string.act_t_tool_n_network_from_a_pack_m_model_req))
                    Spacer(Modifier.height(8.dp))
                    TextAction(stringResource(R.string.act_save_this_list_as_a_text_file), { CoreHost.saveActivityRecord(filter, query, problemsOnly) { savedNote = it } })
                    savedNote?.let { Label(it, Type.footnote, c.textSecondary, Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
                    Spacer(Modifier.height(8.dp))
                    TextAction(stringResource(R.string.act_clear_the_record), { confirmClear = true }, color = c.error)
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog(
        title = stringResource(R.string.act_clear_the_record_105), message = stringResource(R.string.act_this_removes_the_activity_list_from_this), confirmLabel = stringResource(R.string.act_clear), destructive = true,
        onConfirm = { CoreHost.clearActivity(); confirmClear = false; rows = emptyList() }, onDismiss = { confirmClear = false },
    )
}
