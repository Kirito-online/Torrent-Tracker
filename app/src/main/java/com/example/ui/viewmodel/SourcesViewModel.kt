package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.parser.DetectedFeed
import com.example.data.parser.DirectorySourceEntry
import com.example.data.parser.DiscoveredSourceCandidate
import com.example.data.parser.SourceDirectory
import com.example.data.repository.TorrentRepository
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceProgressState
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import com.example.domain.model.VerifiedReplacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourcesUiState(
  val sourceSearchQuery: String = "",
  val discoverQuery: String = "",
  val directoryMatches: List<DirectorySourceEntry> = SourceDirectory.catalog,
  val isDiscovering: Boolean = false,
  val discoveredCandidate: DiscoveredSourceCandidate? = null,
  val testingSourceIds: Set<String> = emptySet(),
  val isRefreshingAll: Boolean = false,
  val isDetectingFeeds: Boolean = false,
  val detectedFeeds: List<DetectedFeed> = emptyList(),
  val selectorPreviewMessage: String? = null,
  val selectorPreviewItems: List<TorrentItem> = emptyList(),
  val isRunningDiagnostics: Boolean = false,
  val diagnosticReport: SourceDiagnosticReport? = null,
  val candidateTestResult: String? = null,
  val statusMessage: String? = null
)

class SourcesViewModel(
  private val repository: TorrentRepository,
  private val preferencesRepository: PreferencesRepository
) : ViewModel() {

  val sources: StateFlow<List<TorrentSource>> = repository.observeSources()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _uiState = MutableStateFlow(SourcesUiState())
  val uiState: StateFlow<SourcesUiState> = _uiState.asStateFlow()

  private var discoverJob: Job? = null

  fun onSourceSearchQueryChanged(query: String) {
    _uiState.update { it.copy(sourceSearchQuery = query) }
  }

  fun onDiscoverQueryChanged(query: String) {
    val matches = SourceDirectory.search(query)
    _uiState.update {
      it.copy(
        discoverQuery = query,
        directoryMatches = matches,
        discoveredCandidate = if (query.isBlank()) null else it.discoveredCandidate
      )
    }

    discoverJob?.cancel()
    val trimmed = query.trim()
    val looksLikeDomainOrUrl = trimmed.contains(".") && !trimmed.contains(" ") && trimmed.length >= 4
    if (looksLikeDomainOrUrl) {
      discoverJob = viewModelScope.launch {
        delay(500)
        probeWebsiteForDiscovery(trimmed)
      }
    } else {
      _uiState.update { it.copy(isDiscovering = false, discoveredCandidate = null) }
    }
  }

  fun probeWebsiteForDiscovery(input: String = _uiState.value.discoverQuery) {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return
    discoverJob?.cancel()
    discoverJob = viewModelScope.launch {
      val timeout = preferencesRepository.preferencesFlow.first().sourceTimeoutSeconds
      _uiState.update { it.copy(isDiscovering = true, discoveredCandidate = null) }
      val result = repository.discoverSourceFromUrl(trimmed, timeout)
      _uiState.update { it.copy(isDiscovering = false, discoveredCandidate = result) }
    }
  }

  fun quickAddSource(source: TorrentSource, testImmediately: Boolean = true) {
    viewModelScope.launch {
      repository.saveSource(source, preservePreviousSnapshot = false)
      _uiState.update { it.copy(statusMessage = "Added '${source.name}'") }
      if (testImmediately) {
        testSource(source)
      }
    }
  }

  fun toggleSource(sourceId: String, enabled: Boolean) {
    viewModelScope.launch {
      repository.toggleSourceEnabled(sourceId, enabled)
    }
  }

  fun saveSource(source: TorrentSource) {
    viewModelScope.launch {
      repository.saveSource(source, preservePreviousSnapshot = true)
      _uiState.update { it.copy(statusMessage = "Saved source '${source.name}'") }
    }
  }

  fun deleteSource(sourceId: String) {
    viewModelScope.launch {
      repository.deleteSource(sourceId)
      _uiState.update { it.copy(statusMessage = "Source removed") }
    }
  }

  fun testSource(source: TorrentSource) {
    viewModelScope.launch {
      val prefs = preferencesRepository.preferencesFlow.first()
      _uiState.update { it.copy(testingSourceIds = it.testingSourceIds + source.id) }
      val result = repository.testSingleSource(
        source = source,
        timeoutSeconds = prefs.sourceTimeoutSeconds,
        preferHttps = prefs.preferHttps,
        forceNetwork = true
      )
      val msg = when (result) {
        is SourceProgressState.Success -> "${source.name}: OK (${result.count} items in ${result.durationMs}ms)"
        is SourceProgressState.Error -> "${source.name}: ${result.message}"
        SourceProgressState.Loading -> ""
      }
      _uiState.update {
        it.copy(
          testingSourceIds = it.testingSourceIds - source.id,
          statusMessage = msg.ifBlank { null }
        )
      }
    }
  }

  fun refreshAllSources() {
    viewModelScope.launch {
      val prefs = preferencesRepository.preferencesFlow.first()
      _uiState.update { it.copy(isRefreshingAll = true) }
      val results = repository.runAllSourcesDiagnostic(
        timeoutSeconds = prefs.sourceTimeoutSeconds,
        maxConcurrency = prefs.maxConcurrentSources,
        preferHttps = prefs.preferHttps
      )
      val okCount = results.count { it.second is SourceProgressState.Success }
      _uiState.update {
        it.copy(
          isRefreshingAll = false,
          statusMessage = "Refreshed ${results.size} enabled sources: $okCount online"
        )
      }
    }
  }

  fun detectFeeds(websiteUrl: String) {
    viewModelScope.launch {
      val timeout = preferencesRepository.preferencesFlow.first().sourceTimeoutSeconds
      _uiState.update { it.copy(isDetectingFeeds = true, detectedFeeds = emptyList()) }
      val feeds = repository.detectFeeds(websiteUrl, timeout)
      _uiState.update {
        it.copy(
          isDetectingFeeds = false,
          detectedFeeds = feeds,
          statusMessage = if (feeds.isEmpty()) "No RSS/Atom feeds detected automatically" else "Detected ${feeds.size} feed(s)"
        )
      }
    }
  }

  fun clearDetectedFeeds() {
    _uiState.update { it.copy(detectedFeeds = emptyList(), selectorPreviewMessage = null, selectorPreviewItems = emptyList()) }
  }

  fun testSelectorsPreview(source: TorrentSource, query: String) {
    viewModelScope.launch {
      val timeout = preferencesRepository.preferencesFlow.first().sourceTimeoutSeconds
      _uiState.update { it.copy(selectorPreviewMessage = "Testing source…", selectorPreviewItems = emptyList()) }
      val (msg, items) = repository.testSelectorsPreview(source, query, timeout)
      _uiState.update { it.copy(selectorPreviewMessage = msg, selectorPreviewItems = items) }
    }
  }

  fun runRepairDiagnostic(sourceId: String) {
    viewModelScope.launch {
      val timeout = preferencesRepository.preferencesFlow.first().sourceTimeoutSeconds
      _uiState.update { it.copy(isRunningDiagnostics = true, diagnosticReport = null, candidateTestResult = null) }
      val report = repository.runDiagnostic(sourceId, timeout)
      _uiState.update { it.copy(isRunningDiagnostics = false, diagnosticReport = report) }
    }
  }

  fun testReplacementCandidate(url: String, source: TorrentSource) {
    viewModelScope.launch {
      val timeout = preferencesRepository.preferencesFlow.first().sourceTimeoutSeconds
      _uiState.update { it.copy(candidateTestResult = "Testing URL…") }
      val result = repository.testReplacementUrl(url, source, timeout)
      _uiState.update { it.copy(candidateTestResult = result) }
    }
  }

  fun applyReplacement(sourceId: String, replacement: VerifiedReplacement) {
    viewModelScope.launch {
      val applied = repository.applyVerifiedReplacement(sourceId, replacement)
      if (applied) {
        val msg = if (replacement.replacementHtmlSelectors != null) {
          "Applied auto-detected HTML selectors"
        } else {
          "Updated source with verified URL"
        }
        _uiState.update { it.copy(statusMessage = msg) }
        runRepairDiagnostic(sourceId)
      }
    }
  }

  fun restorePreviousConfig(sourceId: String) {
    viewModelScope.launch {
      val restored = repository.restorePreviousSourceConfig(sourceId)
      _uiState.update {
        it.copy(
          statusMessage = if (restored) "Previous working configuration restored" else "No previous configuration saved"
        )
      }
      if (restored) runRepairDiagnostic(sourceId)
    }
  }

  fun retryAfterCloudflareVerification(sourceId: String) {
    viewModelScope.launch {
      val prefs = preferencesRepository.preferencesFlow.first()
      _uiState.update { it.copy(testingSourceIds = it.testingSourceIds + sourceId) }
      val res = repository.markCloudflareVerifiedCheck(
        sourceId = sourceId,
        timeoutSeconds = prefs.sourceTimeoutSeconds,
        preferHttps = prefs.preferHttps
      )
      val msg = when (res) {
        is SourceProgressState.Success -> "Verification succeeded! Source is accessible again (${res.count} items)."
        is SourceProgressState.Error ->
          if (res.isCloudflare) "Cloudflare challenge is still active. Complete verification in WebView or open in external browser."
          else "Retry error: ${res.message}"
        SourceProgressState.Loading -> ""
      }
      _uiState.update {
        it.copy(
          testingSourceIds = it.testingSourceIds - sourceId,
          statusMessage = msg.ifBlank { null }
        )
      }
    }
  }

  fun clearStatusMessage() {
    _uiState.update { it.copy(statusMessage = null) }
  }

  class Factory(
    private val repository: TorrentRepository,
    private val preferencesRepository: PreferencesRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SourcesViewModel(repository, preferencesRepository) as T
    }
  }
}
