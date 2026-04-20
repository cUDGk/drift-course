package com.driftcourse.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = DriftPrimaryDark,
    onPrimary = DriftOnPrimaryDark,
    primaryContainer = DriftPrimaryContainerDark,
    onPrimaryContainer = DriftOnPrimaryContainerDark,
    secondary = DriftSecondaryDark,
    onSecondary = DriftOnSecondaryDark,
    secondaryContainer = DriftSecondaryContainerDark,
    onSecondaryContainer = DriftOnSecondaryContainerDark,
    tertiary = DriftTertiaryDark,
    onTertiary = DriftOnTertiaryDark,
    tertiaryContainer = DriftTertiaryContainerDark,
    onTertiaryContainer = DriftOnTertiaryContainerDark,
)

private val LightColors = lightColorScheme(
    primary = DriftPrimaryLight,
    onPrimary = DriftOnPrimaryLight,
    primaryContainer = DriftPrimaryContainerLight,
    onPrimaryContainer = DriftOnPrimaryContainerLight,
    secondary = DriftSecondaryLight,
    onSecondary = DriftOnSecondaryLight,
    secondaryContainer = DriftSecondaryContainerLight,
    onSecondaryContainer = DriftOnSecondaryContainerLight,
    tertiary = DriftTertiaryLight,
    onTertiary = DriftOnTertiaryLight,
    tertiaryContainer = DriftTertiaryContainerLight,
    onTertiaryContainer = DriftOnTertiaryContainerLight,
)

// dynamic color は壁紙依存で青オレンジが崩れるので無効化。
@Composable
fun DriftCourseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = DriftTypography, content = content)
}
