package com.pocketnode.app.ui

import com.pocketnode.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AppModel
import com.pocketnode.app.BuildConfig
import com.pocketnode.app.CoreHost
import com.pocketnode.core.MailAccount
import com.pocketnode.core.MailSecurity

/** Where a provider's mail servers are. The port decides how the connection is protected. */
private class Provider(val name: String, val smtp: String, val smtpPort: Int, val imap: String, val imapPort: Int, val note: String)

private val providers = listOf(
    Provider("Gmail", "smtp.gmail.com", 465, "imap.gmail.com", 993, "Turn on 2-step verification, then make an app password in your Google account and use it here."),
    Provider("Outlook", "smtp-mail.outlook.com", 587, "outlook.office365.com", 993, "If sign-in fails, make an app password in your Microsoft account security settings."),
    Provider("Yahoo", "smtp.mail.yahoo.com", 465, "imap.mail.yahoo.com", 993, "Make an app password in Yahoo's account security page and use it here."),
    Provider("iCloud", "smtp.mail.me.com", 587, "imap.mail.me.com", 993, "Make an app-specific password at appleid.apple.com and use it here."),
    Provider("Zoho", "smtp.zoho.com", 465, "imap.zoho.com", 993, "Use an application-specific password if you have two-factor sign-in on."),
)

private fun guess(address: String): Provider? = when (address.substringAfter('@', "").lowercase()) {
    "gmail.com", "googlemail.com" -> providers[0]
    "outlook.com", "hotmail.com", "live.com", "msn.com" -> providers[1]
    "yahoo.com", "ymail.com" -> providers[2]
    "icloud.com", "me.com", "mac.com" -> providers[3]
    "zoho.com" -> providers[4]
    else -> null
}

/** 465 and 993 are protected from the start, 587 and 143 upgrade after saying hello. A test server on this machine may be unprotected (debug builds only). */
private fun securityFor(host: String, port: Int): MailSecurity = when {
    port == 465 || port == 993 -> MailSecurity.SSL
    port == 587 || port == 143 -> MailSecurity.STARTTLS
    BuildConfig.DEBUG && (host == "127.0.0.1" || host == "localhost") -> MailSecurity.NONE
    else -> MailSecurity.SSL
}

/** Set up the mailbox the assistant sends from and reads. The login is checked with the mail server before anything is saved. */
@Composable
fun MailAccountScreen() {
    val c = MC.colors
    LaunchedEffect(Unit) { CoreHost.loadMailAccount() }
    val saved = CoreHost.mailAccountInfo
    var address by remember(saved) { mutableStateOf(saved?.address.orEmpty()) }
    var name by remember(saved) { mutableStateOf(saved?.name.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var smtp by remember(saved) { mutableStateOf(saved?.smtpHost.orEmpty()) }
    var smtpPort by remember(saved) { mutableStateOf(saved?.smtpPort?.toString().orEmpty()) }
    var imap by remember(saved) { mutableStateOf(saved?.imapHost.orEmpty()) }
    var imapPort by remember(saved) { mutableStateOf(saved?.imapPort?.toString().orEmpty()) }
    var advanced by remember(saved) { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun pick(p: Provider) { smtp = p.smtp; smtpPort = p.smtpPort.toString(); imap = p.imap; imapPort = p.imapPort.toString(); note = p.note; advanced = false }
    // Typing a well-known address fills in the servers.
    LaunchedEffect(address) { if (smtp.isBlank()) guess(address)?.let { pick(it) } }

    val ready = address.isNotBlank() && smtp.isNotBlank() && imap.isNotBlank() && smtpPort.toIntOrNull() != null && imapPort.toIntOrNull() != null && (password.isNotBlank() || saved != null)
    Frame(c.grouped) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            TopBar(stringResource(R.string.mail_email_account), onBack = { AppModel.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin).padding(bottom = 24.dp)) {
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.mail_your_assistant_sends_from_this_address_a), Type.subhead, c.textSecondary, Modifier.padding(horizontal = 4.dp))
                SectionHeader(stringResource(R.string.mail_provider))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    providers.forEach { p -> SmallButton(p.name, { pick(p) }) }
                    SmallButton("Other", { advanced = true; note = "Use the mail server names your email provider gives you. Port 465 or 993 is protected from the start; 587 or 143 upgrade to protected."; smtp = ""; imap = ""; smtpPort = "465"; imapPort = "993" })
                }
                note?.let { Spacer(Modifier.height(8.dp)); Label(it, Type.footnote, c.textSecondary, Modifier.padding(horizontal = 4.dp)) }
                Spacer(Modifier.height(16.dp))
                Field(stringResource(R.string.mail_email_address), address, { address = it; done = false; problem = null }, placeholder = "you@example.com", keyboardType = KeyboardType.Email)
                Spacer(Modifier.height(12.dp))
                Field(stringResource(R.string.mail_your_name_shown_to_people_you_email), name, { name = it; done = false }, placeholder = stringResource(R.string.mail_ada_obi))
                Spacer(Modifier.height(12.dp))
                Field(stringResource(R.string.mail_app_password), password, { password = it; done = false; problem = null }, secure = true, placeholder = if (saved != null) stringResource(R.string.mail_saved_leave_empty_to_keep_it) else stringResource(R.string.mail_not_your_usual_password))
                if (advanced || (smtp.isNotBlank() && providers.none { it.smtp == smtp })) {
                    SectionHeader(stringResource(R.string.mail_servers))
                    Field(stringResource(R.string.mail_send_server), smtp, { smtp = it; done = false }, placeholder = "smtp.example.com", keyboardType = KeyboardType.Uri)
                    Spacer(Modifier.height(12.dp))
                    Field(stringResource(R.string.mail_send_port), smtpPort, { smtpPort = it.filter(Char::isDigit).take(5); done = false }, keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(12.dp))
                    Field(stringResource(R.string.mail_read_server), imap, { imap = it; done = false }, placeholder = "imap.example.com", keyboardType = KeyboardType.Uri)
                    Spacer(Modifier.height(12.dp))
                    Field(stringResource(R.string.mail_read_port), imapPort, { imapPort = it.filter(Char::isDigit).take(5); done = false }, keyboardType = KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = {})
                } else if (smtp.isNotBlank()) {
                    TextAction(stringResource(R.string.mail_change_servers), { advanced = true })
                }
                Spacer(Modifier.height(16.dp))
                if (checking) InlineStatus(StatusKind.Info, stringResource(R.string.mail_checking_with_the_mail_server), working = true)
                problem?.let { InlineStatus(StatusKind.Error, it) }
                warning?.let { Spacer(Modifier.height(8.dp)); InlineStatus(StatusKind.Info, it) }
                if (done && problem == null) InlineStatus(StatusKind.Info, stringResource(R.string.mail_saved_install_the_email_pack_from_find_m))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(stringResource(R.string.mail_check_and_save), {
                    checking = true; problem = null; warning = null; done = false
                    val sp = smtpPort.toInt(); val ip = imapPort.toInt()
                    val account = MailAccount(
                        address.trim(), name.trim(), smtp.trim(), sp, securityFor(smtp.trim(), sp), imap.trim(), ip, securityFor(imap.trim(), ip),
                        address.trim(), password,
                    )
                    CoreHost.saveMailAccount(account) { err, readNote -> checking = false; problem = err; warning = readNote; if (err == null) { done = true; password = "" } }
                }, enabled = ready && !checking, loading = checking)
                if (saved != null) {
                    Spacer(Modifier.height(16.dp))
                    Group { ListRow(stringResource(R.string.mail_remove_this_account), titleColor = c.error, onClick = { confirmRemove = true }, dividerInset = null) }
                }
            }
        }
    }
    if (confirmRemove) ConfirmDialog(
        title = stringResource(R.string.mail_remove_the_email_account), message = stringResource(R.string.mail_the_password_is_deleted_from_this_phone),
        confirmLabel = stringResource(R.string.mail_remove), destructive = true, onConfirm = { confirmRemove = false; CoreHost.removeMailAccount() }, onDismiss = { confirmRemove = false },
    )
}
