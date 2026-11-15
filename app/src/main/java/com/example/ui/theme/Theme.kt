package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.domain.model.AccentColorOption
import com.example.domain.model.ThemeMode

@Composable
fun TorrentTrackerTheme(
  themeMode: ThemeMode = ThemeMode.DARK,
  accentColor: AccentColorOption = AccentColorOption.CYAN,
  content: @Composable () -> Unit
) {
  val primary = Color(accentColor.primaryHex)
  val secondary = Color(accentColor.secondaryHex)
  val isDark = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
  }
  val isAmoled = themeMode == ThemeMode.AMOLED

  val colorScheme = if (!isDark) {
    lightColorScheme(
      primary = primary,
      onPrimary = Color.White,
      primaryContainer = primary.copy(alpha = 0.14f),
      onPrimaryContainer = primary,
      secondary = secondary,
      onSecondary = Color.White,
      secondaryContainer = secondary.copy(alpha = 0.12f),
      onSecondaryContainer = secondary,
      background = LightBackground,
      onBackground = LightOnBackground,
      surface = LightSurface,
      onSurface = LightOnSurface,
      surfaceVariant = LightSurfaceVariant,
      onSurfaceVariant = LightOnSurfaceVariant,
      outline = LightOutline,
      outlineVariant = LightOutline.copy(alpha = 0.6f),
      error = ErrorRed,
      onError = Color.White
    )
  } else {
    val bg = if (isAmoled) AmoledBackground else DarkBackground
    val surf = if (isAmoled) AmoledSurface else DarkSurface
    val surfVar = if (isAmoled) AmoledSurfaceVariant else DarkSurfaceVariant
    val outline = if (isAmoled) AmoledOutline else DarkOutline

    darkColorScheme(
      primary = primary,
      onPrimary = Color(0xFF06131A),
      primaryContainer = primary.copy(alpha = 0.18f),
      onPrimaryContainer = primary,
      secondary = secondary,
      onSecondary = Color(0xFF06131A),
      secondaryContainer = secondary.copy(alpha = 0.18f),
      onSecondaryContainer = secondary,
      background = bg,
      onBackground = DarkOnBackground,
      surface = surf,
      onSurface = DarkOnSurface,
      surfaceVariant = surfVar,
      onSurfaceVariant = DarkOnSurfaceVariant,
      outline = outline,
      outlineVariant = outline.copy(alpha = 0.7f),
      error = ErrorRed,
      onError = Color.White
    )
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    content = content
  )
}
