package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.repository.ProgressiveSearchEvent
import com.example.data.repository.TorrentRepository
import com.example.domain.model.FileSizeRange
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SortOption
import com.example.domain.model.SourceProgressState
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import com.example.domain.model.UploadDateWindow
import com.example.domain.model.UserPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
  val query: String = "",
  val activeCategoryTab: TorrentCategory = TorrentCategory.ALL,
  val filterState: SearchFilterState = SearchFilterState(),
  val results: List<TorrentItem> = emptyList(),
  val sourceProgress: Map<String, SourceProgressState> = emptyMap(),
  val sourceNames: Map<String, String> = emptyMap(),
  val isLoading: Boolean = false,
  val isRefreshing: Boolean = false,
  val isLoadingMore: Boolean = false,
  val hasMorePages: Boolean = false,
  val currentPage: Int = 1,
  val lastRefreshedAt: Long? = null,
  val usingCachedResults: Boolean = false,
  val selectedTorrent: TorrentItem? = null
)

class SearchViewModel(
  private val repository: TorrentRepository,
  private val preferencesRepository: PreferencesRepository
) : ViewModel() {

  private val rawItems = MutableStateFlow<List<TorrentItem>>(emptyList())
  private val _uiState = MutableStateFlow(SearchUiState())

  val preferences: StateFlow<UserPreferences> = preferencesRepository.preferencesFlow
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

  val sources: StateFlow<List<TorrentSource>> = repository.observeSources()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val favoriteIds: StateFlow<Set<String>> = repository.observeFavoriteIds()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

  val uiState: StateFlow<SearchUiState> = combine(
    _uiState,
    rawItems,
    favoriteIds
  ) { state, items, favIds ->
    val effectiveFilter = if (state.activeCategoryTab != TorrentCategory.ALL && state.filterState.selectedCategories.isEmpty()) {
      state.filterState.copy(selectedCategories = setOf(state.activeCategoryTab))
    } else {
      state.filterState
    }
    val processed = repository.deduplicateAndFilter(items, favIds, effectiveFilter)
    val updatedSelected = state.selectedTorrent?.let { sel ->
      processed.firstOrNull { it.id == sel.id } ?: sel.copy(isFavorite = favIds.contains(sel.id))
    }
    state.copy(results = processed, selectedTorrent = updatedSelected)
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

  private var searchJob: Job? = null
  private var debounceJob: Job? = null

  init {
    viewModelScope.launch {
      repository.ensureSeeded()
      val prefs = preferencesRepository.preferencesFlow.first()
      _uiState.update {
        it.copy(
          activeCategoryTab = prefs.defaultCategory,
          filterState = it.filterState.copy(sortOption = prefs.defaultSort)
        )
      }
      if (prefs.autoRefreshOnLaunch) {
        executeSearch(query = "", page = 1, isRefresh = false)
      }
    }
  }

  fun onQueryChanged(newQuery: String) {
    _uiState.update { it.copy(query = newQuery) }
    debounceJob?.cancel()
    debounceJob = viewModelScope.launch {
      val debounceMs = preferences.value.searchDebounceMs.toLong()
      delay(debounceMs)
      executeSearch(query = newQuery, page = 1, isRefresh = false)
    }
  }

  fun submitSearch(query: String = _uiState.value.query) {
    debounceJob?.cancel()
    _uiState.update { it.copy(query = query) }
    executeSearch(query = query, page = 1, isRefresh = false)
  }

  fun refresh(specificSourceId: String? = null) {
    debounceJob?.cancel()
    executeSearch(
      query = _uiState.value.query,
      page = 1,
      isRefresh = true,
      specificSourceId = specificSourceId
    )
  }

  fun loadNextPage() {
    val state = _uiState.value
    if (state.isLoading || state.isLoadingMore || !state.hasMorePages) return
    executeSearch(query = state.query, page = state.currentPage + 1, isRefresh = false, append = true)
  }

  private fun executeSearch(
    query: String,
    page: Int,
    isRefresh: Boolean,
    append: Boolean = false,
    specificSourceId: String? = null
  ) {
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      val prefs = preferences.value
      val currentSources = repository.observeSources().first()
      val enabledCount = currentSources.count { it.enabled }

      _uiState.update {
        it.copy(
          isLoading = !append && !isRefresh,
          isRefreshing = isRefresh,
          isLoadingMore = append,
          currentPage = page,
          hasMorePages = if (append) it.hasMorePages else false,
          usingCachedResults = false,
          sourceProgress = if (specificSourceId != null) {
            it.sourceProgress + (specificSourceId to SourceProgressState.Loading)
          } else {
            emptyMap()
          },
          sourceNames = currentSources.associate { s -> s.id to s.name }
        )
      }

      if (!append && specificSourceId == null) {
        rawItems.value = emptyList()
      }

      if (enabledCount == 0) {
        _uiState.update {
          it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false)
        }
        return@launch
      }

      var anySuccess = false
      var anyMorePages = false

      repository.searchAcrossSources(
        query = query,
        page = page,
        timeoutSeconds = prefs.sourceTimeoutSeconds,
        maxConcurrency = prefs.maxConcurrentSources,
        specificSourceId = specificSourceId
      ).collect { event ->
        when (event) {
          is ProgressiveSearchEvent.CachedFallbackLoaded -> {
            if (rawItems.value.isEmpty()) {
              rawItems.value = event.items
              _uiState.update { it.copy(usingCachedResults = true) }
            }
          }
          is ProgressiveSearchEvent.SourceStarted -> {
            _uiState.update { state ->
              state.copy(
                sourceProgress = state.sourceProgress + (event.sourceId to SourceProgressState.Loading),
                sourceNames = state.sourceNames + (event.sourceId to event.sourceName)
              )
            }
          }
          is ProgressiveSearchEvent.SourceCompleted -> {
            if (event.progress is SourceProgressState.Success) {
              anySuccess = true
              if (event.hasMore) anyMorePages = true
              rawItems.update { existing ->
                if (!append && specificSourceId == null && _uiState.value.usingCachedResults) {
                  _uiState.update { it.copy(usingCachedResults = false) }
                  event.items
                } else if (specificSourceId != null) {
                  existing.filterNot { it.sourceId == event.sourceId } + event.items
                } else {
                  existing + event.items
                }
              }
            }
            _uiState.update { state ->
              state.copy(
                sourceProgress = state.sourceProgress + (event.sourceId to event.progress)
              )
            }
          }
        }
      }

      val now = System.currentTimeMillis()
      _uiState.update {
        it.copy(
          isLoading = false,
          isRefreshing = false,
          isLoadingMore = false,
          hasMorePages = anyMorePages,
          lastRefreshedAt = if (anySuccess) now else it.lastRefreshedAt
        )
      }

      if (page == 1 && query.isNotBlank()) {
        repository.recordSearch(query, uiState.value.results.size, _uiState.value.activeCategoryTab)
      }
    }
  }

  fun selectDrawerCategory(category: TorrentCategory) {
    _uiState.update { state ->
      val updatedFilter = if (category == TorrentCategory.ALL) {
        state.filterState.copy(selectedCategories = emptySet())
      } else {
        state.filterState.copy(selectedCategories = setOf(category))
      }
      state.copy(activeCategoryTab = category, filterState = updatedFilter)
    }
  }

  fun updateFilters(newFilter: SearchFilterState) {
    _uiState.update { it.copy(filterState = newFilter) }
  }

  fun clearAllFilters() {
    val defaultSort = preferences.value.defaultSort
    _uiState.update {
      it.copy(
        activeCategoryTab = TorrentCategory.ALL,
        filterState = SearchFilterState(sortOption = defaultSort)
      )
    }
  }

  fun toggleCategoryFilterChip(category: TorrentCategory) {
    _uiState.update { state ->
      val current = state.filterState.selectedCategories
      val next = if (current.contains(category)) current - category else current + category
      state.copy(filterState = state.filterState.copy(selectedCategories = next))
    }
  }

  fun toggleResolutionChip(resolution: String) {
    _uiState.update { state ->
      val current = state.filterState.selectedResolutions
      val next = if (current.contains(resolution)) current - resolution else current + resolution
      state.copy(filterState = state.filterState.copy(selectedResolutions = next))
    }
  }

  fun setSortOption(sort: SortOption) {
    _uiState.update { state ->
      state.copy(filterState = state.filterState.copy(sortOption = sort))
    }
  }

  fun setFileSizeRange(range: FileSizeRange) {
    _uiState.update { state ->
      state.copy(filterState = state.filterState.copy(fileSizeRange = range))
    }
  }

  fun setUploadDateWindow(window: UploadDateWindow) {
    _uiState.update { state ->
      state.copy(filterState = state.filterState.copy(uploadDateWindow = window))
    }
  }

  fun toggleFavorite(item: TorrentItem) {
    viewModelScope.launch {
      repository.toggleFavorite(item)
    }
  }

  fun selectTorrent(item: TorrentItem?) {
    _uiState.update { it.copy(selectedTorrent = item) }
  }

  suspend fun findTorrentById(id: String): TorrentItem? {
    return uiState.value.results.firstOrNull { it.id == id }
      ?: rawItems.value.firstOrNull { it.id == id }
      ?: repository.getFavoriteOrCachedItem(id)
  }

  class Factory(
    private val repository: TorrentRepository,
    private val preferencesRepository: PreferencesRepository
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SearchViewModel(repository, preferencesRepository) as T
    }
  }
}
