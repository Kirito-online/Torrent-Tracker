package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.model.ThemeMode
import com.example.ui.navigation.AppNavigation
import com.example.ui.theme.TorrentTrackerTheme
import com.example.ui.viewmodel.FavoritesViewModel
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SourcesViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
    val container = (application as TorrentTrackerApp).container

    setContent {
      val searchVm: SearchViewModel = viewModel(
        factory = SearchViewModel.Factory(container.torrentRepository, container.preferencesRepository)
      )
      val sourcesVm: SourcesViewModel = viewModel(
        factory = SourcesViewModel.Factory(container.torrentRepository, container.preferencesRepository)
      )
      val favoritesVm: FavoritesViewModel = viewModel(
        factory = FavoritesViewModel.Factory(container.torrentRepository)
      )
      val settingsVm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container.preferencesRepository, container.torrentRepository)
      )

      val prefs by settingsVm.preferences.collectAsStateWithLifecycle()
      val isSystemDark = isSystemInDarkTheme()
      val isDarkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
      }

      DisposableEffect(isDarkTheme) {
        enableEdgeToEdge(
          statusBarStyle = if (isDarkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
          },
          navigationBarStyle = if (isDarkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
          } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
          }
        )
        onDispose {}
      }

      TorrentTrackerTheme(
        themeMode = prefs.themeMode,
        accentColor = prefs.accentColor
      ) {
        AppNavigation(
          searchViewModel = searchVm,
          sourcesViewModel = sourcesVm,
          favoritesViewModel = favoritesVm,
          settingsViewModel = settingsVm
        )
      }
    }
  }
}
