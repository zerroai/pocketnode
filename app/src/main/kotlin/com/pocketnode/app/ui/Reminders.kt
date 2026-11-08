package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.core.Reminder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun whenText(r: Reminder): String {
    val time = SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(Date(r.at))
    return when (r.repeat) { "daily" -> "$time, every day"; "weekly" -> "$time, every week"; "weekdays" -> "$time, on weekdays"; else -> time }
}

/** What the assistant has set to go off, with a way to cancel each one. New ones are made by asking in a chat. */
@Composable
fun RemindersScreen() {
    val c = MC.colors
    LaunchedEffect(Unit) { while (true) { CoreHost.loadReminders(); kotlinx.coroutines.delay(2500) } }
    val items = CoreHost.reminders
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(stringResource(R.string.rem_reminders), onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Label(stringResource(R.string.rem_nothing_is_waiting_ask_your_assistant_fo), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
                } else {
                    Group {
                        items.forEachIndexed { i, r ->
                            ListRow(
                                r.text, subtitle = whenText(r), dividerInset = if (i == items.lastIndex) null else 16.dp,
                                trailing = { SmallButton(stringResource(R.string.rem_cancel), { CoreHost.cancelReminder(r.id) }) },
                            )
                        }
                    }
                    SectionFooter(stringResource(R.string.rem_they_show_as_notifications_even_when_mob))
                }
            }
        }
    }
}
