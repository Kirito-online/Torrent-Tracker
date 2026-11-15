package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TorrentRepository
import com.example.domain.model.FavoriteCollection
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SearchHistoryItem
import com.example.domain.model.SortOption
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoritesFilterState(
  val searchQuery: String = "",
  val selectedCategory: TorrentCategory = TorrentCategory.ALL,
  val selectedCollectionId: String? = null,
  val sortOption: SortOption = SortOption.NEWEST,
  val filterState: SearchFilterState = SearchFilterState(sortOption = SortOption.NEWEST)
)

class FavoritesViewModel(
  private val repository: TorrentRepository
) : ViewModel() {

  private val _filter = MutableStateFlow(FavoritesFilterState())
  val filter: StateFlow<FavoritesFilterState> = _filter.asStateFlow()

  val collections: StateFlow<List<FavoriteCollection>> = repository.observeCollections()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val allFavorites: StateFlow<List<TorrentItem>> = repository.observeFavorites()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val filteredFavorites: StateFlow<List<TorrentItem>> = combine(
    allFavorites,
    _filter
  ) { items, f ->
    val q = f.searchQuery.trim()
    val byQueryAndTab = items.filter { item ->
      val queryMatch = q.isBlank() ||
        item.title.contains(q, ignoreCase = true) ||
        item.sourceName.contains(q, ignoreCase = true) ||
        item.tags.any { it.contains(q, ignoreCase = true) }

      val collectionMatch = f.selectedCollectionId == null || item.collectionId == f.selectedCollectionId
      val categoryMatch = f.selectedCollectionId != null ||
        f.selectedCategory == TorrentCategory.ALL ||
        item.category == f.selectedCategory

      queryMatch && collectionMatch && categoryMatch
    }
    repository.deduplicateAndFilter(
      items = byQueryAndTab,
      favoriteIds = byQueryAndTab.map { it.id }.toSet(),
      filter = f.filterState.copy(sortOption = f.sortOption)
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val searchHistory: StateFlow<List<SearchHistoryItem>> = repository.observeSearchHistory()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun updateSearchQuery(query: String) {
    _filter.update { it.copy(searchQuery = query) }
  }

  fun selectCategory(category: TorrentCategory) {
    _filter.update { it.copy(selectedCategory = category, selectedCollectionId = null) }
  }

  fun selectCollection(collectionId: String?) {
    _filter.update { it.copy(selectedCollectionId = collectionId, selectedCategory = TorrentCategory.ALL) }
  }

  fun setSortOption(sort: SortOption) {
    _filter.update { it.copy(sortOption = sort) }
  }

  fun toggleFavorite(item: TorrentItem) {
    viewModelScope.launch { repository.toggleFavorite(item) }
  }

  fun createCollection(name: String) {
    if (name.isBlank()) return
    viewModelScope.launch { repository.createCollection(name) }
  }

  fun renameCollection(id: String, newName: String) {
    if (newName.isBlank()) return
    viewModelScope.launch { repository.renameCollection(id, newName) }
  }

  fun deleteCollection(id: String) {
    viewModelScope.launch {
      repository.deleteCollection(id)
      if (_filter.value.selectedCollectionId == id) {
        _filter.update { it.copy(selectedCollectionId = null) }
      }
    }
  }

  fun moveFavoriteToCollection(torrentId: String, collectionId: String?) {
    viewModelScope.launch { repository.moveFavoriteToCollection(torrentId, collectionId) }
  }

  fun deleteHistoryItem(id: Long) {
    viewModelScope.launch { repository.deleteHistoryItem(id) }
  }

  fun clearSearchHistory() {
    viewModelScope.launch { repository.clearSearchHistory() }
  }

  class Factory(private val repository: TorrentRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return FavoritesViewModel(repository) as T
    }
  }
}
