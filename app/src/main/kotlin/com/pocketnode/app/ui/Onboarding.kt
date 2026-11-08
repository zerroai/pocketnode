package com.pocketnode.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pocketnode.app.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketnode.app.AnthropicTier
import com.pocketnode.app.AppModel
import com.pocketnode.app.CoreHost
import com.pocketnode.app.Provider
import com.pocketnode.app.Route
import com.pocketnode.core.ProbeResult

/** Centers content to a readable width on large screens. */
@Composable
fun Frame(background: Color, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = Dim.maxContent).fillMaxWidth()) { content() }
    }
}

@Composable
fun WelcomeScreen() {
    val c = MC.colors
    Frame(c.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Logo(68.dp)
                Spacer(Modifier.height(24.dp))
                Label(stringResource(R.string.onb_mobile_cloud), Type.largeTitle, c.text, align = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Label(stringResource(R.string.onb_an_assistant_that_runs_on_your_phone), Type.body, c.textSecondary, align = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            Feature(Glyph.Lock, stringResource(R.string.onb_private_by_design), stringResource(R.string.onb_your_chats_and_keys_stay_on_this_phone_a))
            Spacer(Modifier.height(24.dp))
            Feature(Glyph.Packs, stringResource(R.string.onb_extend_with_packs), stringResource(R.string.onb_add_messaging_and_tools_as_you_need_them))
            Spacer(Modifier.height(24.dp))
            Feature(Glyph.Key, stringResource(R.string.onb_bring_your_own_model), stringResource(R.string.onb_use_openrouter_openai_anthropic_or_a_mod))
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.onb_get_started), { AppModel.push(Route.SetupModel) })
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Feature(glyph: Glyph, title: String, body: String) {
    val c = MC.colors
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) { Icon(glyph, c.accent, size = 24.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Label(title, Type.headline, c.text)
            Label(body, Type.subhead, c.textSecondary)
        }
    }
}

/** Shared layout for setup steps: progress, title, scrolling content, pinned actions. */
@Composable
internal fun SetupFrame(
    step: Int,
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val c = MC.colors
    Frame(c.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    Pressable(onBack, Modifier.size(Dim.touch), description = stringResource(R.string.onb_back)) { Box(Modifier.size(Dim.touch), contentAlignment = Alignment.Center) { Icon(Glyph.Back, c.accent, size = 26.dp, weight = 2.1f) } }
                } else Spacer(Modifier.width(Dim.margin))
                StepBar(step, 4, Modifier.weight(1f).padding(end = Dim.margin))
                if (onBack != null) Spacer(Modifier.width(0.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dim.margin)) {
                Spacer(Modifier.height(16.dp))
                Label(title, Type.largeTitle, c.text)
                Spacer(Modifier.height(8.dp))
                Label(subtitle, Type.callout, c.textSecondary)
                Spacer(Modifier.height(28.dp))
                content()
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Dim.margin).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
    }
}

// ---- Model ---------------------------------------------------------------------------------

/** A well-known OpenAI-style service: its address, a sensible first model, and where the owner makes a key. */
private class Preset(val name: String, val url: String, val model: String, val keyPage: String)

private val presets = listOf(
    Preset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "https://openrouter.ai/keys"),
    Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys"),
    Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys"),
)
private const val ANTHROPIC_KEYS = "https://console.anthropic.com/settings/keys"

/** State for the model connection form. Used by setup and by Settings. */
class ModelFormState(initial: Provider) {
    var provider by mutableStateOf(if (initial == Provider.None) Provider.Anthropic else initial)
    var key by mutableStateOf("")
    var tier by mutableStateOf(CoreHost.config.tier)
    var url by mutableStateOf(CoreHost.config.serverUrl)
    var model by mutableStateOf(CoreHost.config.serverModel)
    var openAiUrl by mutableStateOf(CoreHost.config.openAiUrl.ifBlank { "https://api.openai.com/v1" })
    var openAiModel by mutableStateOf(CoreHost.config.openAiModel)
    var checking by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val canSubmit: Boolean
        get() = !checking && when (provider) {
            Provider.Anthropic -> key.isNotBlank() || CoreHost.config.hasAnthropicKey
            Provider.Ollama -> url.isNotBlank()
            Provider.OpenAi -> openAiUrl.isNotBlank() && openAiModel.isNotBlank() && (key.isNotBlank() || CoreHost.config.hasOpenAiKey || !openAiUrl.contains("api.openai.com"))
            Provider.None -> false
        }

    /** Verifies the connection first; saves it only if it works. */
    fun submit(onSuccess: () -> Unit) {
        checking = true
        error = null
        val done = { r: ProbeResult ->
            checking = false
            when (r) {
                is ProbeResult.Ok -> onSuccess()
                is ProbeResult.Failed -> error = r.reason
            }
        }
        when (provider) {
            Provider.Anthropic -> CoreHost.connectAnthropic(key, tier, done)
            Provider.OpenAi -> CoreHost.connectOpenAi(openAiUrl, key, openAiModel, done)
            else -> CoreHost.connectOllama(url, model, done)
        }
    }
}

@Composable
fun ModelFormFields(s: ModelFormState, showTier: Boolean, onSubmit: () -> Unit) {
    val c = MC.colors
    Group(color = c.fill) {
        ChoiceRow(stringResource(R.string.onb_openrouter_openai_groq), s.provider == Provider.OpenAi, { s.provider = Provider.OpenAi; s.error = null; s.key = "" }, subtitle = stringResource(R.string.onb_one_key_many_models_the_easiest_start), leading = { Avatar(Glyph.Key, 36.dp) })
        ChoiceRow(stringResource(R.string.onb_anthropic), s.provider == Provider.Anthropic, { s.provider = Provider.Anthropic; s.error = null; s.key = "" }, subtitle = stringResource(R.string.onb_claude_models_with_your_api_key), leading = { Avatar(Glyph.Key, 36.dp) })
        ChoiceRow(stringResource(R.string.onb_my_own_computer), s.provider == Provider.Ollama, { s.provider = Provider.Ollama; s.error = null }, subtitle = stringResource(R.string.onb_ollama_on_your_computer_or_home_server_n), leading = { Avatar(Glyph.Server, 36.dp) }, dividerInset = null)
    }
    Spacer(Modifier.height(20.dp))
    if (s.provider == Provider.Anthropic) {
        Field(
            stringResource(R.string.onb_api_key), s.key, { s.key = it; s.error = null }, secure = true,
            placeholder = if (CoreHost.config.hasAnthropicKey) stringResource(R.string.onb_saved_leave_empty_to_keep_it) else stringResource(R.string.onb_paste_your_key),
            imeAction = ImeAction.Done, onDone = { if (s.canSubmit) onSubmit() },
        )
        KeyLink(stringResource(R.string.onb_make_your_key_at_anthropic), ANTHROPIC_KEYS)
        SectionFooter(stringResource(R.string.onb_usage_is_billed_by_anthropic_to_your_acc))
        if (showTier) {
            SectionHeader(stringResource(R.string.onb_model))
            Group(color = c.fill) {
                AnthropicTier.entries.forEachIndexed { i, t ->
                    ChoiceRow(t.label, s.tier == t, { s.tier = t }, subtitle = t.note, dividerInset = if (i == AnthropicTier.entries.lastIndex) null else 16.dp)
                }
            }
        }
    } else if (s.provider == Provider.OpenAi) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            presets.forEach { p -> SmallButton(p.name, { s.openAiUrl = p.url; s.openAiModel = p.model; s.error = null; s.key = "" }) }
        }
        presets.firstOrNull { it.url == s.openAiUrl }?.let { KeyLink("Make your key at ${it.name}", it.keyPage) }
        Spacer(Modifier.height(12.dp))
        Field(stringResource(R.string.onb_server_address), s.openAiUrl, { s.openAiUrl = it; s.error = null }, placeholder = "https://api.openai.com/v1", keyboardType = KeyboardType.Uri)
        Spacer(Modifier.height(12.dp))
        Field(
            stringResource(R.string.onb_api_key), s.key, { s.key = it; s.error = null }, secure = true,
            placeholder = if (CoreHost.config.hasOpenAiKey) stringResource(R.string.onb_saved_leave_empty_to_keep_it) else stringResource(R.string.onb_paste_your_key),
        )
        Spacer(Modifier.height(12.dp))
        Field(stringResource(R.string.onb_model), s.openAiModel, { s.openAiModel = it; s.error = null }, placeholder = "gpt-4o-mini", imeAction = ImeAction.Done, onDone = { if (s.canSubmit) onSubmit() })
        SectionFooter(stringResource(R.string.onb_usage_is_billed_by_your_provider_use_an))
    } else {
        Field(stringResource(R.string.onb_server_address), s.url, { s.url = it; s.error = null }, placeholder = "https://example.com", keyboardType = KeyboardType.Uri)
        Spacer(Modifier.height(12.dp))
        Field(stringResource(R.string.onb_model), s.model, { s.model = it; s.error = null }, placeholder = "llama3.1", imeAction = ImeAction.Done, onDone = { if (s.canSubmit) onSubmit() })
        SectionFooter(stringResource(R.string.onb_install_ollama_on_a_computer_pull_a_mode))
    }
}

/** A link to the page where the owner makes their key. Opens in the browser. */
@Composable
private fun KeyLink(text: String, url: String) {
    val uri = LocalUriHandler.current
    Spacer(Modifier.height(8.dp))
    TextAction(text, { runCatching { uri.openUri(url) } })
}

@Composable
fun StatusLine(s: ModelFormState) {
    when {
        s.checking -> InlineStatus(StatusKind.Info, stringResource(R.string.onb_checking_the_connection), working = true)
        s.error != null -> InlineStatus(StatusKind.Error, s.error!!)
        else -> {}
    }
}

@Composable
fun SetupModelScreen() {
    val state = remember { ModelFormState(CoreHost.config.provider) }
    val next = { state.submit { AppModel.push(Route.SetupFinish) } }
    SetupFrame(
        step = 2,
        title = stringResource(R.string.onb_connect_a_model),
        subtitle = stringResource(R.string.onb_mobile_cloud_uses_the_model_you_choose_y),
        onBack = { AppModel.pop() },
        actions = {
            StatusLine(state)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(stringResource(R.string.onb_continue), next, enabled = state.canSubmit, loading = state.checking)
            TextAction(stringResource(R.string.onb_skip_for_now), { AppModel.push(Route.SetupFinish) }, Modifier.align(Alignment.CenterHorizontally))
        },
    ) { ModelFormFields(state, showTier = false, onSubmit = { next() }) }
}

// ---- Finish --------------------------------------------------------------------------------

@Composable
fun SetupFinishScreen() {
    val c = MC.colors
    val needsPermission = Build.VERSION.SDK_INT >= 33
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AppModel.finishOnboarding() }
    CoreHost.config // read so the summary updates when settings change
    SetupFrame(
        step = 4,
        title = stringResource(R.string.onb_keep_it_running),
        subtitle = stringResource(R.string.onb_mobile_cloud_runs_a_small_background_ser),
        onBack = { AppModel.pop() },
        actions = {
            PrimaryButton(if (needsPermission) stringResource(R.string.onb_allow_notifications) else stringResource(R.string.onb_finish), {
                if (needsPermission) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else AppModel.finishOnboarding()
            })
            if (needsPermission) TextAction(stringResource(R.string.onb_not_now), { AppModel.finishOnboarding() }, Modifier.align(Alignment.CenterHorizontally))
        },
    ) {
        Group(color = MC.colors.fill) {
            ListRow(stringResource(R.string.onb_model), value = CoreHost.modelSummary(), dividerInset = 16.dp)
            ListRow(stringResource(R.string.onb_packs), value = CoreHost.packs.size.let { if (it == 0) stringResource(R.string.onb_none_yet) else if (it == 1) "1 added" else "$it added" }, dividerInset = null)
        }
        SectionFooter(stringResource(R.string.onb_you_can_change_these_at_any_time_in_sett))
        var testing by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        Spacer(Modifier.height(16.dp))
        SecondaryButton(if (testing) stringResource(R.string.onb_testing) else stringResource(R.string.onb_test_your_setup), { if (!testing) { testing = true; outcome = null; CoreHost.testSetup { ok, msg -> testing = false; outcome = ok to msg } } }, Modifier.fillMaxWidth())
        outcome?.let { (ok, msg) -> Spacer(Modifier.height(8.dp)); InlineStatus(if (ok) StatusKind.Success else StatusKind.Error, msg) }
        SectionFooter(stringResource(R.string.onb_sends_one_short_message_to_your_model_to))
    }
}
