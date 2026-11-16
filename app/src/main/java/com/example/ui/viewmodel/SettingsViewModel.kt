package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.repository.TorrentRepository
import com.example.domain.model.AccentColorOption
import com.example.domain.model.BackupSelection
import com.example.domain.model.DisplayDensity
import com.example.domain.model.RestoreMode
import com.example.domain.model.SortOption
import com.example.domain.model.SourceProgressState
import com.example.domain.model.StorageUsageInfo
import com.example.domain.model.ThemeMode
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentSource
import com.example.domain.model.TorrentTrackerBackup
import com.example.domain.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
  private val preferencesRepository: PreferencesRepository,
  private val torrentRepository: TorrentRepository
) : ViewModel() {

  val preferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

  private val _storageUsage = MutableStateFlow(StorageUsageInfo(0, 0, 0, 0, 0))
  val storageUsage: StateFlow<StorageUsageInfo> = _storageUsage.asStateFlow()

  private val _diagnosticsResults = MutableStateFlow<List<Pair<TorrentSource, SourceProgressState>>>(emptyList())
  val diagnosticsResults: StateFlow<List<Pair<TorrentSource, SourceProgressState>>> = _diagnosticsResults.asStateFlow()

  private val _isRunningBulkDiagnostics = MutableStateFlow(false)
  val isRunningBulkDiagnostics: StateFlow<Boolean> = _isRunningBulkDiagnostics.asStateFlow()

  private val _pendingImportBackup = MutableStateFlow<TorrentTrackerBackup?>(null)
  val pendingImportBackup: StateFlow<TorrentTrackerBackup?> = _pendingImportBackup.asStateFlow()

  private val _isProcessingBackup = MutableStateFlow(false)
  val isProcessingBackup: StateFlow<Boolean> = _isProcessingBackup.asStateFlow()

  private val _feedbackMessage = MutableStateFlow<String?>(null)
  val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

  init {
    refreshStorageStats()
  }

  fun refreshStorageStats() {
    viewModelScope.launch {
      _storageUsage.value = torrentRepository.getStorageUsage()
    }
  }

  fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferencesRepository.updateThemeMode(mode) }
  fun setAccentColor(color: AccentColorOption) = viewModelScope.launch { preferencesRepository.updateAccentColor(color) }
  fun setDisplayDensity(density: DisplayDensity) = viewModelScope.launch { preferencesRepository.updateDisplayDensity(density) }
  fun setDefaultCategory(category: TorrentCategory) = viewModelScope.launch { preferencesRepository.updateDefaultCategory(category) }
  fun setDefaultSort(sort: SortOption) = viewModelScope.launch { preferencesRepository.updateDefaultSort(sort) }
  fun setSearchDebounce(ms: Int) = viewModelScope.launch { preferencesRepository.updateSearchDebounceMs(ms) }
  fun setAutoRefreshOnLaunch(enabled: Boolean) = viewModelScope.launch { preferencesRepository.updateAutoRefreshOnLaunch(enabled) }
  fun setSourceTimeout(seconds: Int) = viewModelScope.launch { preferencesRepository.updateSourceTimeoutSeconds(seconds) }
  fun setMaxConcurrency(count: Int) = viewModelScope.launch { preferencesRepository.updateMaxConcurrentSources(count) }
  fun setPreferHttps(prefer: Boolean) = viewModelScope.launch { preferencesRepository.updatePreferHttps(prefer) }

  fun clearImageCache() {
    viewModelScope.launch {
      torrentRepository.clearImageCache()
      refreshStorageStats()
      _feedbackMessage.value = "Cached cover images cleared"
    }
  }

  fun clearSearchHistory() {
    viewModelScope.launch {
      torrentRepository.clearSearchHistory()
      refreshStorageStats()
      _feedbackMessage.value = "Search history cleared"
    }
  }

  fun clearTemporaryCache() {
    viewModelScope.launch {
      torrentRepository.clearTemporaryCache()
      refreshStorageStats()
      _feedbackMessage.value = "Temporary result cache cleared"
    }
  }

  fun clearVerificationCookies() {
    torrentRepository.clearVerificationCookies()
    _feedbackMessage.value = "Browser verification & session cookies cleared"
  }

  suspend fun buildDiagnosticReportText(): String {
    return torrentRepository.buildDiagnosticReportText(BuildConfig.VERSION_NAME)
  }

  fun runConnectionDiagnostics() {
    viewModelScope.launch {
      val prefs = preferencesRepository.preferencesFlow.first()
      _isRunningBulkDiagnostics.value = true
      val results = torrentRepository.runAllSourcesDiagnostic(
        timeoutSeconds = prefs.sourceTimeoutSeconds,
        maxConcurrency = prefs.maxConcurrentSources,
        preferHttps = prefs.preferHttps
      )
      _diagnosticsResults.value = results
      _isRunningBulkDiagnostics.value = false
    }
  }

  suspend fun buildBackupJson(selection: BackupSelection): String {
    val currentPrefs = preferencesRepository.preferencesFlow.first()
    return torrentRepository.exportBackupJson(
      selection = selection,
      preferences = currentPrefs,
      appVersion = BuildConfig.VERSION_NAME
    )
  }

  fun loadBackupForPreview(rawJson: String) {
    val result = torrentRepository.parseAndValidateBackup(rawJson)
    result.onSuccess { backup ->
      _pendingImportBackup.value = backup
    }.onFailure { err ->
      _feedbackMessage.value = err.localizedMessage ?: "Invalid backup file"
    }
  }

  fun dismissImportPreview() {
    _pendingImportBackup.value = null
  }

  fun confirmRestoreBackup(selection: BackupSelection, mode: RestoreMode) {
    val backup = _pendingImportBackup.value ?: return
    viewModelScope.launch {
      _isProcessingBackup.value = true
      runCatching {
        torrentRepository.restoreBackup(
          backup = backup,
          selection = selection,
          mode = mode,
          preferencesRepository = preferencesRepository
        )
      }.onSuccess { summary ->
        _pendingImportBackup.value = null
        refreshStorageStats()
        val parts = buildList {
          if (selection.includeSources) add("${summary.sourcesRestored} sources")
          if (selection.includeFavoritesAndCollections) add("${summary.favoritesRestored} favorites, ${summary.collectionsRestored} collections")
          if (summary.preferencesRestored) add("preferences")
          if (selection.includeSearchHistory) add("${summary.historyRestored} history")
        }
        _feedbackMessage.value = "Restored (${mode.label}): ${parts.joinToString(", ").ifBlank { "no items" }}"
      }.onFailure { err ->
        _feedbackMessage.value = "Restore failed: ${err.localizedMessage ?: "Database error"}"
      }
      _isProcessingBackup.value = false
    }
  }

  fun notifyFeedback(message: String) {
    _feedbackMessage.value = message
  }

  fun clearFeedback() {
    _feedbackMessage.value = null
  }

  class Factory(
    private val preferencesRepository: PreferencesRepository,
    private val torrentRepository: TorrentRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SettingsViewModel(preferencesRepository, torrentRepository) as T
    }
  }
}
