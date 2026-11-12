package com.tube.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Фирменные цвета Tube — те же, что в пиксельном логотипе. */
object Brand {
    val Green = Color(0xFF33CC33)
    val GreenMid = Color(0xFF1AA61A)
    val GreenDark = Color(0xFF008000)
    val GreenShadow = Color(0xFF005A00)
    val GreenHighlight = Color(0xFF8CF07A)
    val Graphite = Color(0xFF2A2A2A)
    val Amber = Color(0xFFFFB703)
    val Red = Color(0xFFE5484D)
}

@Immutable
data class TubeColors(
    val isDark: Boolean,
    val background: Color,
    val card: Color,
    val cardBorder: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val glassTint: Color,
    val glassStroke: Color,
    val scrim: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val chip: Color,
    /** Текст и значки поверх акцентного цвета. */
    val onAccent: Color = Color.White,
    /** Цвета световых пятен фона. */
    val ambient: List<Color> = listOf(Brand.Green, Brand.GreenDark, Brand.GreenHighlight),
    /** Яркость световых пятен (AMOLED — приглушённые, чтобы фон оставался чёрным). */
    val ambientStrength: Float = 1f,
)

private val LightColors = TubeColors(
    isDark = false,
    background = Color(0xFFEFF4EC),
    card = Color.White.copy(alpha = 0.78f),
    cardBorder = Color.White.copy(alpha = 0.9f),
    textPrimary = Color(0xFF16201A),
    textSecondary = Color(0xFF56635A),
    textTertiary = Color(0xFF8D978F),
    accent = Color(0xFF169C16),
    glassTint = Color.White.copy(alpha = 0.32f),
    glassStroke = Color.White.copy(alpha = 0.65f),
    scrim = Color(0xFF16201A).copy(alpha = 0.28f),
    success = Color(0xFF169C16),
    warning = Color(0xFFE08A1E),
    danger = Brand.Red,
    chip = Color(0xFF16201A).copy(alpha = 0.06f),
)

private val DarkColors = TubeColors(
    isDark = true,
    background = Color(0xFF161816),
    card = Brand.Graphite.copy(alpha = 0.78f),
    cardBorder = Color.White.copy(alpha = 0.08f),
    textPrimary = Color(0xFFF0F4EF),
    textSecondary = Color(0xFFA6AFA7),
    textTertiary = Color(0xFF6E776F),
    accent = Brand.Green,
    glassTint = Brand.Graphite.copy(alpha = 0.38f),
    glassStroke = Color.White.copy(alpha = 0.14f),
    scrim = Color.Black.copy(alpha = 0.5f),
    success = Brand.Green,
    warning = Color(0xFFFFB35C),
    danger = Color(0xFFFF6B6E),
    chip = Color.White.copy(alpha = 0.08f),
    onAccent = Color(0xFF052105),
)

/** Палитра приложения: зелёная — Tube, фиолетовая (спокойная, минималистичная) — Tube Player. */
enum class TubePalette { Green, Violet }

private val VioletLight = TubeColors(
    isDark = false,
    background = Color(0xFFF6F4FA),
    card = Color.White.copy(alpha = 0.86f),
    cardBorder = Color.White.copy(alpha = 0.95f),
    textPrimary = Color(0xFF1D1A24),
    textSecondary = Color(0xFF5F5A6C),
    textTertiary = Color(0xFF9590A3),
    accent = Color(0xFF7457D9),
    glassTint = Color.White.copy(alpha = 0.42f),
    glassStroke = Color.White.copy(alpha = 0.7f),
    scrim = Color(0xFF1D1A24).copy(alpha = 0.28f),
    success = Color(0xFF7457D9),
    warning = Color(0xFFD9892F),
    danger = Brand.Red,
    chip = Color(0xFF1D1A24).copy(alpha = 0.055f),
    ambient = listOf(Color(0xFFC9B8FF), Color(0xFFE6DDFF), Color(0xFFB9C6F5)),
    ambientStrength = 0.55f,
)

private val VioletDark = TubeColors(
    isDark = true,
    background = Color(0xFF141218),
    card = Color(0xFF211E28).copy(alpha = 0.86f),
    cardBorder = Color.White.copy(alpha = 0.07f),
    textPrimary = Color(0xFFEDE9F5),
    textSecondary = Color(0xFFA9A3B8),
    textTertiary = Color(0xFF6F6981),
    accent = Color(0xFFB7A0FF),
    glassTint = Color(0xFF211E28).copy(alpha = 0.45f),
    glassStroke = Color.White.copy(alpha = 0.12f),
    scrim = Color.Black.copy(alpha = 0.5f),
    success = Color(0xFFB7A0FF),
    warning = Color(0xFFFFB35C),
    danger = Color(0xFFFF6B6E),
    chip = Color.White.copy(alpha = 0.075f),
    onAccent = Color(0xFF1E1433),
    ambient = listOf(Color(0xFF6C52D6), Color(0xFF3C2E78), Color(0xFF5A6BC9)),
    ambientStrength = 0.5f,
)

/** Чистый чёрный фон для AMOLED-экранов: пиксели фона выключены, карточки и стекло почти чёрные. */
private fun amoled(base: TubeColors): TubeColors = base.copy(
    background = Color.Black,
    card = Color(0xFF0E0E10).copy(alpha = 0.9f),
    cardBorder = Color.White.copy(alpha = 0.07f),
    glassTint = Color.Black.copy(alpha = 0.55f),
    glassStroke = Color.White.copy(alpha = 0.12f),
    scrim = Color.Black.copy(alpha = 0.66f),
    chip = Color.White.copy(alpha = 0.09f),
    ambientStrength = base.ambientStrength * 0.6f,
)

object Shapes {
    val Card = RoundedCornerShape(24.dp)
    val Panel = RoundedCornerShape(32.dp)
    val Sheet = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Chip = RoundedCornerShape(16.dp)
}

private val LocalTubeColors = staticCompositionLocalOf { DarkColors }

object TubeTheme {
    val colors: TubeColors
        @Composable @ReadOnlyComposable get() = LocalTubeColors.current
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

/**
 * Цвета Material You: акцент, фон и текст — из системной палитры, а форма интерфейса
 * (стекло, полупрозрачные карточки) остаётся своей. Смысловые цвета состояний не меняются.
 */
private fun dynamicColors(scheme: ColorScheme, dark: Boolean, palette: TubePalette): TubeColors {
    val base = palette(palette, dark)
    return base.copy(
        background = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainer,
        card = (if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest).copy(alpha = if (dark) 0.74f else 0.8f),
        cardBorder = if (dark) scheme.outlineVariant.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.9f),
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textTertiary = scheme.outline,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        glassTint = scheme.surfaceContainer.copy(alpha = if (dark) 0.35f else 0.32f),
        scrim = scheme.scrim.copy(alpha = if (dark) 0.45f else 0.28f),
        chip = scheme.onSurface.copy(alpha = if (dark) 0.08f else 0.06f),
        ambient = listOf(scheme.primary, scheme.tertiary, scheme.secondary),
    )
}

val dynamicColorsSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * [dynamic] — цвета Material You вместо фирменных (Android 12+);
 * [amoled] — в тёмной теме чистый чёрный фон; [palette] — фирменные цвета приложения.
 */
@Composable
fun TubeTheme(
    darkTheme: Boolean,
    dynamic: Boolean = false,
    amoled: Boolean = false,
    palette: TubePalette = TubePalette.Green,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicScheme = if (dynamic && dynamicColorsSupported) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }
    val baseColors = when {
        dynamicScheme != null -> dynamicColors(dynamicScheme, darkTheme, palette)
        else -> palette(palette, darkTheme)
    }
    val violet = palette == TubePalette.Violet
    val colors = if (amoled && darkTheme) amoled(baseColors) else baseColors
    val scheme = dynamicScheme ?: if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = if (violet) Color(0xFF8E7CC3) else Brand.GreenDark,
            tertiary = Brand.Amber,
            background = colors.background,
            surface = if (amoled) Color.Black else if (violet) Color(0xFF1D1B22) else Color(0xFF1E211E),
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = if (violet) Color(0xFF8E7CC3) else Brand.GreenDark,
            tertiary = Brand.Amber,
            background = colors.background,
            surface = if (violet) Color(0xFFFBFAFE) else Color(0xFFF8FBF6),
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
        )
    }
    CompositionLocalProvider(LocalTubeColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

private fun palette(palette: TubePalette, dark: Boolean): TubeColors = when (palette) {
    TubePalette.Green -> if (dark) DarkColors else LightColors
    TubePalette.Violet -> if (dark) VioletDark else VioletLight
}
