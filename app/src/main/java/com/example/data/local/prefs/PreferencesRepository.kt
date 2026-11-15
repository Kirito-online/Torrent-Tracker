package com.example.data.local.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.AccentColorOption
import com.example.domain.model.DisplayDensity
import com.example.domain.model.SortOption
import com.example.domain.model.ThemeMode
import com.example.domain.model.TorrentCategory
import com.example.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "torrent_tracker_prefs")

class PreferencesRepository(private val context: Context) {

  private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val DISPLAY_DENSITY = stringPreferencesKey("display_density")
    val DEFAULT_CATEGORY = stringPreferencesKey("default_category")
    val DEFAULT_SORT = stringPreferencesKey("default_sort")
    val SEARCH_DEBOUNCE_MS = intPreferencesKey("search_debounce_ms")
    val AUTO_REFRESH_ON_LAUNCH = booleanPreferencesKey("auto_refresh_on_launch")
    val SOURCE_TIMEOUT_SECONDS = intPreferencesKey("source_timeout_seconds")
    val MAX_CONCURRENT_SOURCES = intPreferencesKey("max_concurrent_sources")
    val PREFER_HTTPS = booleanPreferencesKey("prefer_https")
  }

  val preferencesFlow: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
    UserPreferences(
      themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
        ?: ThemeMode.DARK,
      accentColor = prefs[Keys.ACCENT_COLOR]?.let { runCatching { AccentColorOption.valueOf(it) }.getOrNull() }
        ?: AccentColorOption.CYAN,
      displayDensity = prefs[Keys.DISPLAY_DENSITY]?.let { runCatching { DisplayDensity.valueOf(it) }.getOrNull() }
        ?: DisplayDensity.COMPACT,
      defaultCategory = prefs[Keys.DEFAULT_CATEGORY]?.let { runCatching { TorrentCategory.valueOf(it) }.getOrNull() }
        ?: TorrentCategory.ALL,
      defaultSort = prefs[Keys.DEFAULT_SORT]?.let { runCatching { SortOption.valueOf(it) }.getOrNull() }
        ?: SortOption.RELEVANCE,
      searchDebounceMs = prefs[Keys.SEARCH_DEBOUNCE_MS] ?: 450,
      autoRefreshOnLaunch = prefs[Keys.AUTO_REFRESH_ON_LAUNCH] ?: true,
      sourceTimeoutSeconds = prefs[Keys.SOURCE_TIMEOUT_SECONDS] ?: 12,
      maxConcurrentSources = prefs[Keys.MAX_CONCURRENT_SOURCES] ?: 4,
      preferHttps = prefs[Keys.PREFER_HTTPS] ?: true
    )
  }

  suspend fun updateThemeMode(mode: ThemeMode) {
    context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }

  suspend fun updateAccentColor(color: AccentColorOption) {
    context.dataStore.edit { it[Keys.ACCENT_COLOR] = color.name }
  }

  suspend fun updateDisplayDensity(density: DisplayDensity) {
    context.dataStore.edit { it[Keys.DISPLAY_DENSITY] = density.name }
  }

  suspend fun updateDefaultCategory(category: TorrentCategory) {
    context.dataStore.edit { it[Keys.DEFAULT_CATEGORY] = category.name }
  }

  suspend fun updateDefaultSort(sort: SortOption) {
    context.dataStore.edit { it[Keys.DEFAULT_SORT] = sort.name }
  }

  suspend fun updateSearchDebounceMs(ms: Int) {
    context.dataStore.edit { it[Keys.SEARCH_DEBOUNCE_MS] = ms.coerceIn(150, 1200) }
  }

  suspend fun updateAutoRefreshOnLaunch(enabled: Boolean) {
    context.dataStore.edit { it[Keys.AUTO_REFRESH_ON_LAUNCH] = enabled }
  }

  suspend fun updateSourceTimeoutSeconds(seconds: Int) {
    context.dataStore.edit { it[Keys.SOURCE_TIMEOUT_SECONDS] = seconds.coerceIn(5, 30) }
  }

  suspend fun updateMaxConcurrentSources(count: Int) {
    context.dataStore.edit { it[Keys.MAX_CONCURRENT_SOURCES] = count.coerceIn(1, 8) }
  }

  suspend fun updatePreferHttps(prefer: Boolean) {
    context.dataStore.edit { it[Keys.PREFER_HTTPS] = prefer }
  }

  suspend fun applyBackupPreferences(entry: com.example.domain.model.BackupPreferencesEntry) {
    context.dataStore.edit { prefs ->
      prefs[Keys.THEME_MODE] = entry.themeMode.name
      prefs[Keys.ACCENT_COLOR] = entry.accentColor.name
      prefs[Keys.DISPLAY_DENSITY] = entry.displayDensity.name
      prefs[Keys.DEFAULT_CATEGORY] = entry.defaultCategory.name
      prefs[Keys.DEFAULT_SORT] = entry.defaultSort.name
      prefs[Keys.SEARCH_DEBOUNCE_MS] = entry.searchDebounceMs.coerceIn(150, 1200)
      prefs[Keys.AUTO_REFRESH_ON_LAUNCH] = entry.autoRefreshOnLaunch
      prefs[Keys.SOURCE_TIMEOUT_SECONDS] = entry.sourceTimeoutSeconds.coerceIn(5, 30)
      prefs[Keys.MAX_CONCURRENT_SOURCES] = entry.maxConcurrentSources.coerceIn(1, 8)
      prefs[Keys.PREFER_HTTPS] = entry.preferHttps
    }
  }
}
