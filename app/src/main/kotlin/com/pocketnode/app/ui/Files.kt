package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AssetFiles
import com.pocketnode.app.CoreHost
import com.pocketnode.app.FileActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun sizeText(bytes: Long): String = when {
    bytes < 1_000 -> "$bytes B"
    bytes < 1_000_000 -> "${bytes / 1_000} KB"
    else -> "%.1f MB".format(bytes / 1_000_000.0)
}

/** Everything the assistant has made for you: PDFs, spreadsheets, text files. Tap one to open it; Save puts a copy where you choose. */
@Composable
fun FilesScreen() {
    val c = MC.colors
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<AssetFiles.Asset>?>(null) }
    var asking by remember { mutableStateOf<AssetFiles.Asset?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Reads the locked names off the main thread, and again every few seconds so a file made in the chat appears here.
    LaunchedEffect(version) { items = withContext(Dispatchers.IO) { CoreHost.assetFiles.list() } }
    LaunchedEffect(Unit) { while (true) { delay(3000); version++ } }
    val list = items
    Column(Modifier.fillMaxSize().background(c.grouped).verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        LargeTitleHeader(stringResource(R.string.files_files))
        Column(Modifier.padding(horizontal = Dim.margin)) {
            if (list == null) {
                // Still reading; show nothing rather than a flash of "Nothing yet".
            } else if (list.isEmpty()) {
                Label(stringResource(R.string.files_nothing_yet_ask_your_assistant_for_examp), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
            } else {
                problem?.let { Label(it, Type.subhead, c.accent, Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                Group {
                    list.forEachIndexed { i, a ->
                        ListRow(
                            a.name,
                            subtitle = "${a.kind}, ${sizeText(a.size)}, ${SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(a.modified))}",
                            leading = { Avatar(Glyph.File, 36.dp) },
                            dividerInset = null,
                            onClick = { problem = if (FileActions.open(context, a.ref)) null else FileActions.NO_APP },
                        )
                        Row(
                            Modifier.fillMaxWidth().drawBehind { if (i != list.lastIndex) drawLine(c.separator, Offset(16.dp.toPx(), size.height), Offset(size.width, size.height), strokeWidth = 1f) }
                                .padding(start = 64.dp, end = 16.dp, bottom = 10.dp),
                        ) {
                            TextAction(stringResource(R.string.files_save), { CoreHost.saveFile(a.ref) { problem = it } })
                            Spacer(Modifier.width(20.dp))
                            TextAction("Share", { if (!FileActions.share(context, a.ref)) problem = "That file could not be unlocked." })
                            Spacer(Modifier.width(20.dp))
                            TextAction(stringResource(R.string.files_delete), { asking = a }, color = c.textSecondary)
                        }
                    }
                }
                SectionFooter(stringResource(R.string.files_these_are_kept_locked_on_this_phone_in_t))
            }
        }
    }
    asking?.let { a ->
        ConfirmDialog(
            title = "Delete ${a.name}?", message = stringResource(R.string.files_this_removes_the_file_from_this_phone_co),
            confirmLabel = stringResource(R.string.files_delete), destructive = true,
            onConfirm = { CoreHost.assetFiles.delete(a.ref); asking = null; version++ }, onDismiss = { asking = null },
        )
    }
}
