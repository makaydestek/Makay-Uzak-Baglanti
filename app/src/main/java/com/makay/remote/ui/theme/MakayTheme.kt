package com.makay.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * MAKAY renk paleti — masaustu `apps/remote/style.css` ile ayni degerler.
 *   --accent #818cf8   --accent2 #06b6d4   --primary #3b82f6
 *   --success #22c55e  --warn     #f59e0b  --danger    #ef4444
 */
object MakayPalette {
    val Accent = Color(0xFF818CF8)
    val Accent2 = Color(0xFF06B6D4)
    val Primary = Color(0xFF3B82F6)
    val Success = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)

    // koyu tema
    val DarkBg = Color(0xFF0A0A1A)
    val DarkBg2 = Color(0xFF10102A)
    val DarkText = Color(0xFFE6E9FF)
    val DarkMuted = Color(0x8CE6E9FF)
    val DarkCard = Color(0x08FFFFFF)
    val DarkCardBorder = Color(0x14FFFFFF)
    val DarkInput = Color(0x0AFFFFFF)

    // aydinlik tema
    val LightBg = Color(0xFFEEF1F8)
    val LightBg2 = Color(0xFFFFFFFF)
    val LightText = Color(0xFF1A2035)
    val LightMuted = Color(0x8C1A2035)
    val LightCard = Color(0x08000000)
    val LightCardBorder = Color(0x1A000000)
    val LightInput = Color(0x0A000000)
}

/** Ekrana gore degisen renkler. */
data class MakayColors(
    val bg: Color,
    val bg2: Color,
    val text: Color,
    val muted: Color,
    val card: Color,
    val cardBorder: Color,
    val input: Color,
    val accent: Color,
    val accent2: Color,
    val primary: Color,
    val success: Color,
    val warn: Color,
    val danger: Color
)

val LocalMakayColors = staticCompositionLocalOf {
    MakayColors(
        MakayPalette.DarkBg, MakayPalette.DarkBg2, MakayPalette.DarkText, MakayPalette.DarkMuted,
        MakayPalette.DarkCard, MakayPalette.DarkCardBorder, MakayPalette.DarkInput,
        MakayPalette.Accent, MakayPalette.Accent2, MakayPalette.Primary,
        MakayPalette.Success, MakayPalette.Warn, MakayPalette.Danger
    )
}

@Composable
fun colorsFor(dark: Boolean): MakayColors = if (dark) {
    MakayColors(
        MakayPalette.DarkBg, MakayPalette.DarkBg2, MakayPalette.DarkText, MakayPalette.DarkMuted,
        MakayPalette.DarkCard, MakayPalette.DarkCardBorder, MakayPalette.DarkInput,
        MakayPalette.Accent, MakayPalette.Accent2, MakayPalette.Primary,
        MakayPalette.Success, MakayPalette.Warn, MakayPalette.Danger
    )
} else {
    MakayColors(
        MakayPalette.LightBg, MakayPalette.LightBg2, MakayPalette.LightText, MakayPalette.LightMuted,
        MakayPalette.LightCard, MakayPalette.LightCardBorder, MakayPalette.LightInput,
        MakayPalette.Accent, MakayPalette.Accent2, MakayPalette.Primary,
        MakayPalette.Success, MakayPalette.Warn, MakayPalette.Danger
    )
}

/**
 * @param dark null = sistem tercihini kullan
 */
@Composable
fun MakayTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: isSystemInDarkTheme()
    val c = colorsFor(isDark)

    val scheme = if (isDark) {
        darkColorScheme(
            primary = c.primary,
            onPrimary = Color.White,
            secondary = c.accent2,
            background = c.bg,
            onBackground = c.text,
            surface = c.bg2,
            onSurface = c.text,
            error = c.danger
        )
    } else {
        lightColorScheme(
            primary = c.primary,
            onPrimary = Color.White,
            secondary = c.accent2,
            background = c.bg,
            onBackground = c.text,
            surface = c.bg2,
            onSurface = c.text,
            error = c.danger
        )
    }

    CompositionLocalProvider(LocalMakayColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Kisa erisim: `MakayTheme.c.accent` — Material3 `MaterialTheme` deseni gibi
 * fonksiyonla ayni ismi paylasan object.
 */
object MakayTheme {
    val c: MakayColors
        @Composable get() = LocalMakayColors.current
}