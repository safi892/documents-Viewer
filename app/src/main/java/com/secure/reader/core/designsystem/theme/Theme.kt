package com.secure.reader.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One palette, both modes: zinc neutrals with a single desaturated emerald
 * accent. Dynamic color is deliberately disabled — it would break the
 * one-palette rule.
 */
private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = Zinc50,
    primaryContainer = AccentContainerLight,
    onPrimaryContainer = Color(0xFF0E2E23),
    background = Zinc50,
    onBackground = Zinc900,
    surface = Zinc50,
    onSurface = Zinc900,
    surfaceVariant = Zinc100,
    onSurfaceVariant = Zinc600,
    surfaceContainerLowest = Zinc50,
    surfaceContainerLow = Zinc100,
    surfaceContainer = Zinc100,
    surfaceContainerHigh = Zinc200,
    outlineVariant = Zinc200,
    outline = Zinc400,
    error = PdfRed,
    onError = Zinc50,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = ReaderDarkBackground,
    primaryContainer = AccentContainerDark,
    onPrimaryContainer = AccentDark,
    background = ReaderDarkBackground,
    onBackground = ReaderDarkOnBackground,
    surface = ReaderDarkSurface,
    onSurface = ReaderDarkOnBackground,
    surfaceVariant = ReaderDarkSurfaceVariant,
    onSurfaceVariant = ReaderDarkMuted,
    surfaceContainerLowest = ReaderDarkBackground,
    surfaceContainerLow = ReaderDarkSurface,
    surfaceContainer = ReaderDarkSurfaceVariant,
    surfaceContainerHigh = ReaderDarkOutline,
    outlineVariant = ReaderDarkOutline,
    outline = ReaderDarkMuted,
    error = Color(0xFFD97C6B),
    onError = ReaderDarkBackground,
)

@Composable
fun ReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ReaderTypography,
        content = content,
    )
}
