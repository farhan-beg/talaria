package dev.hark.hermes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Palette(
    val bg: Color, val card: Color, val cardAlt: Color, val ink: Color, val muted: Color, val faint: Color,
    val accent: Color, val accentInk: Color, val accentSoft: Color, val line: Color,
    val good: Color, val warn: Color, val bad: Color, val userBubble: Color, val userInk: Color, val code: Color,
    val bgTop: Color = bg, val bgBottom: Color = bg, val glow: Color = Color.Transparent, val sheet: Color = card,
    val heroA: Color = accent, val heroB: Color = accent, val dark: Boolean = false,
)

// Talaria palette: midnight plum + indigo, with a warm gold "winged" accent.
val LightPalette = Palette(
    bg = Color(0xFFF6F2EA), card = Color(0xF7FFFDF8), cardAlt = Color(0xFFEDE6DA), ink = Color(0xFF1C1530),
    muted = Color(0xFF6B6278), faint = Color(0xFFA59DB0), accent = Color(0xFF3A2A6E), accentInk = Color(0xFFFFF8EC),
    accentSoft = Color(0x163A2A6E), line = Color(0x161C1530), good = Color(0xFF2E9E6B), warn = Color(0xFFB7791F),
    bad = Color(0xFFC94545), userBubble = Color(0xFFE6DDF3), userInk = Color(0xFF1C1530), code = Color(0xFFF0EBE2),
    bgTop = Color(0xFFFBF8F2), bgBottom = Color(0xFFE9E1F2), glow = Color(0x40E0A84A), sheet = Color(0xFFFBF8F2),
    heroA = Color(0xFF5B3BA8), heroB = Color(0xFF221840), dark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF130E22), card = Color(0xE81C1630), cardAlt = Color(0xFF2A2242), ink = Color(0xFFF5F0E8),
    muted = Color(0xFFA79FB8), faint = Color(0xFF675E7C), accent = Color(0xFFF0C46C), accentInk = Color(0xFF1E1505),
    accentSoft = Color(0x1FF0C46C), line = Color(0x14FFF4E0), good = Color(0xFF6FD9A3), warn = Color(0xFFF0C46C),
    bad = Color(0xFFFF8A7A), userBubble = Color(0xFF2E2448), userInk = Color(0xFFF5F0E8), code = Color(0xFF0E0A19),
    bgTop = Color(0xFF0A0713), bgBottom = Color(0xFF271C47), glow = Color(0x4D7A52E0), sheet = Color(0xFF17112A),
    heroA = Color(0xFF5B3BA8), heroB = Color(0xFF1E1638), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Look & feel switches the user controls in Settings. */
data class Look(val glass: Boolean = true, val motion: Boolean = true, val ambient: Boolean = true)
val LocalLook = staticCompositionLocalOf { Look() }

data class ThemePreset(val id: String, val name: String, val dark: Palette, val light: Palette, val swatch: List<Color>)

private fun night(
    accent: Long, accentInk: Long, top: Long, mid: Long, bottom: Long, card: Long, user: Long, glow: Long,
    heroA: Long, heroB: Long, sheet: Long, alt: Long, muted: Long = 0xFFA7A1B6, faint: Long = 0xFF686279,
) = DarkPalette.copy(
    accent = Color(accent), accentInk = Color(accentInk), accentSoft = Color(accent).copy(alpha = 0.14f),
    bgTop = Color(top), bg = Color(mid), bgBottom = Color(bottom), card = Color(card), cardAlt = Color(alt),
    userBubble = Color(user), glow = Color(glow), heroA = Color(heroA), heroB = Color(heroB), sheet = Color(sheet),
    muted = Color(muted), faint = Color(faint), warn = Color(0xFFF2BE5C),
)

private fun day(
    accent: Long, top: Long, mid: Long, bottom: Long, user: Long, glow: Long, heroA: Long, heroB: Long, ink: Long = 0xFF1A1626,
) = LightPalette.copy(
    accent = Color(accent), accentInk = Color(0xFFFFFFFF), accentSoft = Color(accent).copy(alpha = 0.12f),
    bgTop = Color(top), bg = Color(mid), bgBottom = Color(bottom), userBubble = Color(user), glow = Color(glow),
    heroA = Color(heroA), heroB = Color(heroB), sheet = Color(top), ink = Color(ink), userInk = Color(ink),
)

val ThemePresets = listOf(
    ThemePreset("talaria", "Talaria", DarkPalette, LightPalette, listOf(Color(0xFF271C47), Color(0xFF5B3BA8), Color(0xFFF0C46C))),
    ThemePreset("abyss", "Abyss",
        night(0xFF5EE6D0, 0xFF03201B, 0xFF040D12, 0xFF08171F, 0xFF0D2B36, 0xE8102029, 0xFF15343F, 0x4D1FA7B4, 0xFF117A82, 0xFF0A2630, 0xFF0B1A22, 0xFF1A3440, 0xFF9DB5BC, 0xFF5D7880),
        day(0xFF0B7A75, 0xFFF3FAF9, 0xFFEAF4F3, 0xFFD5EAEA, 0xFFD3ECEA, 0x4038C2B8, 0xFF117A82, 0xFF0A2630),
        listOf(Color(0xFF0D2B36), Color(0xFF117A82), Color(0xFF5EE6D0))),
    ThemePreset("ember", "Ember",
        night(0xFFFF9466, 0xFF2A0E02, 0xFF0F0807, 0xFF1A0F0C, 0xFF3A1A12, 0xE81F1411, 0xFF3A2219, 0x4DE0563A, 0xFFB4442A, 0xFF2A130D, 0xFF1A100D, 0xFF33221C, 0xFFBCA79E, 0xFF7A665E),
        day(0xFFC4502C, 0xFFFFF7F2, 0xFFFBEEE6, 0xFFF4DACB, 0xFFF6DCCD, 0x40FF8A5C, 0xFFB4442A, 0xFF2A130D),
        listOf(Color(0xFF3A1A12), Color(0xFFB4442A), Color(0xFFFF9466))),
    ThemePreset("fern", "Fern",
        night(0xFF9BE8A8, 0xFF07210E, 0xFF060E0A, 0xFF0B1711, 0xFF173126, 0xE8111D17, 0xFF1C3328, 0x4D3FBF73, 0xFF2D7A4F, 0xFF0D2219, 0xFF0C1712, 0xFF1E3329, 0xFFA2B5A9, 0xFF62786A),
        day(0xFF2D7A4F, 0xFFF5FAF5, 0xFFECF4EC, 0xFFD8EADB, 0xFFD7ECDA, 0x4066C98A, 0xFF2D7A4F, 0xFF0D2219),
        listOf(Color(0xFF173126), Color(0xFF2D7A4F), Color(0xFF9BE8A8))),
    ThemePreset("sakura", "Sakura",
        night(0xFFFFA8CC, 0xFF2E0517, 0xFF100810, 0xFF1A0E18, 0xFF3A1A31, 0xE81F1320, 0xFF3A2236, 0x4DE0609E, 0xFFA8457A, 0xFF2A1226, 0xFF1A1019, 0xFF33222F, 0xFFBCA6B4, 0xFF7A6574),
        day(0xFFB23E77, 0xFFFFF6FA, 0xFFFBEDF3, 0xFFF3D9E6, 0xFFF6DBE8, 0x40FF8FBF, 0xFFA8457A, 0xFF2A1226),
        listOf(Color(0xFF3A1A31), Color(0xFFA8457A), Color(0xFFFFA8CC))),
    ThemePreset("onyx", "Onyx",
        night(0xFFECECEC, 0xFF111111, 0xFF060606, 0xFF0D0D0D, 0xFF1C1C1C, 0xE8161616, 0xFF262626, 0x33FFFFFF, 0xFF3A3A3A, 0xFF141414, 0xFF121212, 0xFF262626, 0xFFA3A3A3, 0xFF656565),
        day(0xFF1A1A1A, 0xFFFAFAFA, 0xFFF2F2F2, 0xFFE4E4E4, 0xFFE6E6E6, 0x22000000, 0xFF3A3A3A, 0xFF111111, 0xFF141414),
        listOf(Color(0xFF1C1C1C), Color(0xFF3A3A3A), Color(0xFFECECEC))),
)

/** Pure-black variant for OLED screens. */
fun Palette.amoled() = copy(bgTop = Color.Black, bg = Color.Black, bgBottom = Color.Black, sheet = Color(0xFF0A0A0A),
    card = Color(0xFF111114), code = Color(0xFF050505), glow = glow.copy(alpha = glow.alpha * 0.5f))
/** Space reserved at the top of a tab screen for the floating nav bar (below the status bar). */
val LocalTopInset = staticCompositionLocalOf { 0.dp }
val Mono = FontFamily.Monospace
val Sans = FontFamily(
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.inter_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.inter_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.inter_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.inter_bold, FontWeight.Bold),
)
val Serif = FontFamily(
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.fraunces_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.fraunces_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(dev.hark.hermes.R.font.fraunces_semibold, FontWeight.SemiBold),
)
val CardShape = RoundedCornerShape(28.dp)

@Composable
fun HermesTheme(mode: String, preset: String = "talaria", look: Look = Look(), content: @Composable () -> Unit) {
    val dark = when (mode) { "dark", "amoled" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val spec = ThemePresets.firstOrNull { it.id == preset } ?: ThemePresets[0]
    val p0 = if (dark) spec.dark else spec.light
    val p = if (mode == "amoled") p0.amoled() else p0
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.card, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.cardAlt, onSurfaceVariant = p.muted, outline = p.line, error = p.bad,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.ink, secondaryContainer = p.accentSoft, surfaceContainer = p.card,
        surfaceContainerHigh = p.cardAlt, surfaceContainerHighest = p.cardAlt, surfaceContainerLow = p.card,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.accentInk, background = p.bg, surface = p.card, onSurface = p.ink,
        onBackground = p.ink, surfaceVariant = p.cardAlt, onSurfaceVariant = p.muted, outline = p.line, error = p.bad,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.ink, secondaryContainer = p.accentSoft, surfaceContainer = p.card,
        surfaceContainerHigh = p.cardAlt, surfaceContainerHighest = p.cardAlt, surfaceContainerLow = p.card,
    )
    val t = Typography(
        displaySmall = TextStyle(fontFamily = Sans, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.1).sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = Sans, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = Sans, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, lineHeight = 29.sp),
        titleLarge = TextStyle(fontFamily = Sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleSmall = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
        bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
        bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.5.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.1).sp),
        labelMedium = TextStyle(fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides p, LocalLook provides look) {
        MaterialTheme(colorScheme = scheme, typography = t, content = content)
    }
}
