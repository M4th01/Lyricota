package com.example.lrcfetcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Verde suave del logo, con superficies neutras para que el contenido (la letra) mande.
private val Light = lightColorScheme(
    primary = Color(0xFF2F7D4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEDC),
    onPrimaryContainer = Color(0xFF0B2E18),
    secondary = Color(0xFF5B7564),
    secondaryContainer = Color(0xFFE2ECE5),
    onSecondaryContainer = Color(0xFF18291F),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCEBC8),
    onTertiaryContainer = Color(0xFF2C1A00),
    background = Color(0xFFFAFBFA),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFAFBFA),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFEEF2EF),
    onSurfaceVariant = Color(0xFF5C6660),
    surfaceContainer = Color(0xFFF1F4F2),
    surfaceContainerHigh = Color(0xFFEBEFEC),
    surfaceContainerHighest = Color(0xFFE5EAE6),
    outline = Color(0xFFBFC8C2),
    outlineVariant = Color(0xFFDDE3DF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8BD3A6),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF1E4D31),
    onPrimaryContainer = Color(0xFFBDEBCB),
    secondary = Color(0xFFB2CCBB),
    secondaryContainer = Color(0xFF2B3A31),
    onSecondaryContainer = Color(0xFFCFE8D7),
    tertiary = Color(0xFFF0C063),
    onTertiary = Color(0xFF442B00),
    tertiaryContainer = Color(0xFF3D2E12),
    onTertiaryContainer = Color(0xFFFCE3B5),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF1E2320),
    onSurfaceVariant = Color(0xFFA4ADA7),
    surfaceContainer = Color(0xFF191D1B),
    surfaceContainerHigh = Color(0xFF212623),
    surfaceContainerHighest = Color(0xFF2A302C),
    outline = Color(0xFF4A524D),
    outlineVariant = Color(0xFF30372F),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
        bodyLarge = t.bodyLarge.copy(lineHeight = 26.sp),
    )
}

/** Estilo de las líneas de letra: grande y con aire. */
val LyricLineStyle: TextStyle @Composable get() = MaterialTheme.typography.titleMedium.copy(lineHeight = 28.sp)

@Composable
fun LyricotaTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
