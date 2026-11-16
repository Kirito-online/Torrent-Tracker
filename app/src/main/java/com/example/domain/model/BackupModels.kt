package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TorrentTrackerBackup(
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val exportedAt: Long,
  val appVersion: String = "",
  val sources: List<BackupSourceEntry> = emptyList(),
  val favorites: List<BackupFavoriteEntry> = emptyList(),
  val collections: List<BackupCollectionEntry> = emptyList(),
  val preferences: BackupPreferencesEntry? = null,
  val searchHistory: List<BackupSearchHistoryEntry> = emptyList()
) {
  companion object {
    const val CURRENT_SCHEMA_VERSION = 1
  }
}

@Serializable
data class BackupSourceEntry(
  val id: String,
  val name: String,
  val baseUrl: String,
  val searchUrlTemplate: String,
  val feedUrl: String = "",
  val sourceType: SourceType,
  val enabled: Boolean = true,
  val isBuiltIn: Boolean = false,
  val allowCleartextHttp: Boolean = false,
  val htmlSelectors: HtmlSelectorConfig = HtmlSelectorConfig(),
  val jsonMapping: JsonMappingConfig = JsonMappingConfig(),
  val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class BackupFavoriteEntry(
  val id: String,
  val title: String,
  val category: TorrentCategory,
  val collectionId: String? = null,
  val releaseYear: Int? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val resolution: String? = null,
  val videoQuality: String? = null,
  val encoding: String? = null,
  val fileSizeText: String? = null,
  val fileSizeBytes: Long? = null,
  val seeders: Int? = null,
  val leechers: Int? = null,
  val uploadDateText: String? = null,
  val uploadTimestamp: Long? = null,
  val uploader: String? = null,
  val sourceId: String,
  val sourceName: String,
  val sourceUrl: String? = null,
  val magnetLink: String? = null,
  val torrentFileUrl: String? = null,
  val infoHash: String? = null,
  val coverImageUrl: String? = null,
  val language: String? = null,
  val subtitles: String? = null,
  val tags: List<String> = emptyList(),
  val description: String? = null,
  val savedAt: Long,
  val availableSources: List<TorrentSourceOffer> = emptyList()
)

@Serializable
data class BackupCollectionEntry(
  val id: String,
  val name: String,
  val createdAt: Long,
  val isSystem: Boolean = false
)

@Serializable
data class BackupPreferencesEntry(
  val themeMode: ThemeMode = ThemeMode.DARK,
  val accentColor: AccentColorOption = AccentColorOption.CYAN,
  val displayDensity: DisplayDensity = DisplayDensity.COMPACT,
  val defaultCategory: TorrentCategory = TorrentCategory.ALL,
  val defaultSort: SortOption = SortOption.RELEVANCE,
  val searchDebounceMs: Int = 450,
  val autoRefreshOnLaunch: Boolean = true,
  val sourceTimeoutSeconds: Int = 12,
  val maxConcurrentSources: Int = 4,
  val preferHttps: Boolean = true,
  val activityLoggingEnabled: Boolean = false
)

@Serializable
data class BackupSearchHistoryEntry(
  val query: String,
  val timestamp: Long,
  val resultCount: Int,
  val categoryLabel: String
)

data class BackupSelection(
  val includeSources: Boolean = true,
  val includeFavoritesAndCollections: Boolean = true,
  val includePreferences: Boolean = true,
  val includeSearchHistory: Boolean = false
) {
  val hasAnySelected: Boolean
    get() = includeSources || includeFavoritesAndCollections || includePreferences || includeSearchHistory
}

enum class RestoreMode(val label: String) {
  MERGE("Merge with existing"),
  REPLACE("Replace selected data")
}

data class BackupRestoreSummary(
  val sourcesRestored: Int,
  val favoritesRestored: Int,
  val collectionsRestored: Int,
  val historyRestored: Int,
  val preferencesRestored: Boolean
)
