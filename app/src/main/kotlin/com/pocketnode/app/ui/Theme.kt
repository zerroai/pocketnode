package com.pocketnode.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketnode.app.R

/** Design tokens. See docs/design/ui-guidelines.md before changing any value. */
@Immutable
class Palette(
    val dark: Boolean,
    val background: Color,
    val grouped: Color,
    val surface: Color,
    val fill: Color,
    val pressed: Color,
    val separator: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val success: Color,
    val error: Color,
    val scrim: Color,
) {
    /** Primary buttons and the user's chat bubble: ink on light, near-white on dark. */
    val ink: Color get() = text
    val onInk: Color get() = background
}

// Black and silver. Values come from brand/color/tokens.json (docs/design/ui-guidelines.md). No accent hue:
// selection and focus are black in light and silver in dark. Success is not green; it is shown with a check and words.
val LightPalette = Palette(
    dark = false,
    background = Color(0xFFFFFFFF),
    grouped = Color(0xFFF2F3F5),
    surface = Color(0xFFFFFFFF),
    fill = Color(0xFFF0F1F3),
    pressed = Color(0xFFE3E5E9),
    separator = Color(0xFFE1E3E7),
    text = Color(0xFF0A0A0C),
    textSecondary = Color(0xFF5B5F69),
    textTertiary = Color(0xFF686D77),
    accent = Color(0xFF17181C),
    accentSoft = Color(0x14000000),
    success = Color(0xFF0A0A0C),
    error = Color(0xFFC4122F),
    scrim = Color(0x66000000),
)

val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF0A0A0C),
    grouped = Color(0xFF0A0A0C),
    surface = Color(0xFF17181C),
    fill = Color(0xFF1F2025),
    pressed = Color(0xFF2A2C32),
    separator = Color(0xFF2A2C32),
    text = Color(0xFFF5F6F8),
    textSecondary = Color(0xFFA5A9B2),
    textTertiary = Color(0xFF8A8F99),
    accent = Color(0xFFD5D8DE),
    accentSoft = Color(0x24FFFFFF),
    success = Color(0xFFF5F6F8),
    error = Color(0xFFFF6B7F),
    scrim = Color(0x99000000),
)

/** Inter (SIL Open Font License, see docs/licenses). Subset to Latin plus the Naira sign. */
private val Sans = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

object Type {
    val largeTitle = TextStyle(fontFamily = Sans, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp)
    val title = TextStyle(fontFamily = Sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val headline = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val callout = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val subhead = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val footnote = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val tab = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
}

object Dim {
    val margin = 20.dp
    val maxContent = 600.dp
    val rowMin = 56.dp
    val touch = 48.dp
    val radiusField = 16.dp
    val radiusButton = 28.dp
    val radiusGroup = 20.dp
    val radiusBubble = 22.dp
    val radiusComposer = 28.dp
}

val LocalPalette = staticCompositionLocalOf { LightPalette }

object MC {
    val colors: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
}

enum class ThemeMode(val label: String) { System("System"), Light("Light"), Dark("Dark") }

@Composable
fun PocketnodeTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // The system bars follow the app's own appearance setting, not only the phone's.
    val view = LocalView.current
    SideEffect {
        var ctx = view.context
        while (ctx is android.content.ContextWrapper && ctx !is android.app.Activity) ctx = ctx.baseContext
        (ctx as? android.app.Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette, content = content)
}
