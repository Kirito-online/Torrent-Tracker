package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TorrentCategory(val label: String) {
  ALL("All"),
  MOVIES("Movies"),
  TV_SHOWS("TV Shows"),
  ANIME("Anime"),
  GAMES("Games"),
  SOFTWARE("Software"),
  MUSIC("Music"),
  BOOKS("Books"),
  OTHER("Other");

  companion object {
    fun selectableCategories(): List<TorrentCategory> = entries.filter { it != ALL }
  }
}

@Serializable
enum class SourceType(val label: String) {
  RSS_ATOM("RSS / Atom"),
  JSON_API("JSON API"),
  HTML_SELECTOR("HTML Selectors")
}

enum class SourceHealth {
  UNKNOWN,
  ONLINE,
  REDIRECTED,
  CLOUDFLARE_REQUIRED,
  PARSE_ERROR,
  OFFLINE,
  DISABLED
}

@Serializable
data class HtmlSelectorConfig(
  val itemContainer: String = "article, tr, .torrent-row, .item",
  val title: String = "a.title, h2 a, h3 a, td:nth-child(2) a, a",
  val detailsLink: String = "a.title, h2 a, h3 a, a",
  val magnetLink: String = "a[href^=magnet:]",
  val torrentFileLink: String = "a[href$=.torrent]",
  val coverImage: String = "img",
  val fileSize: String = ".size, td.size, [data-size]",
  val seeders: String = ".seeders, .seeds, td.seeds",
  val leechers: String = ".leechers, .leeches, td.leeches",
  val uploadDate: String = "time, .date, td.date",
  val uploader: String = ".uploader, .author",
  val category: String = ".category, .cat",
  val nextPageLink: String = "a[rel=next], .pagination a.next"
)

@Serializable
data class JsonMappingConfig(
  val itemsPath: String = "response.docs",
  val titleField: String = "title",
  val identifierField: String = "identifier",
  val detailsUrlTemplate: String = "",
  val magnetField: String = "magnet",
  val infoHashField: String = "btih",
  val torrentUrlTemplate: String = "",
  val coverImageTemplate: String = "",
  val imageField: String = "cover",
  val sizeField: String = "item_size",
  val seedersField: String = "seeders",
  val leechersField: String = "leechers",
  val dateField: String = "publicdate",
  val uploaderField: String = "creator",
  val categoryField: String = "mediatype",
  val yearField: String = "year",
  val languageField: String = "language",
  val descriptionField: String = "description"
)

@Serializable
data class TorrentSource(
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
  val lastSuccessAt: Long? = null,
  val lastCheckedAt: Long? = null,
  val lastError: String? = null,
  val lastStatusCode: Int? = null,
  val lastRedirectUrl: String? = null,
  val requiresCloudflare: Boolean = false,
  val previousWorkingConfigJson: String? = null,
  val createdAt: Long = System.currentTimeMillis()
) {
  val health: SourceHealth
    get() = when {
      !enabled -> SourceHealth.DISABLED
      requiresCloudflare -> SourceHealth.CLOUDFLARE_REQUIRED
      !lastRedirectUrl.isNullOrBlank() && lastError != null -> SourceHealth.REDIRECTED
      lastError != null && (lastStatusCode == 200) -> SourceHealth.PARSE_ERROR
      lastError != null -> SourceHealth.OFFLINE
      lastSuccessAt != null -> SourceHealth.ONLINE
      else -> SourceHealth.UNKNOWN
    }

  val isHttps: Boolean
    get() = baseUrl.trim().lowercase().startsWith("https://")

  val usesCleartextHttp: Boolean
    get() = baseUrl.trim().lowercase().startsWith("http://") ||
      searchUrlTemplate.trim().lowercase().startsWith("http://") ||
      feedUrl.trim().lowercase().startsWith("http://")
}

@Serializable
data class TorrentSourceOffer(
  val sourceId: String,
  val sourceName: String,
  val sourceUrl: String? = null,
  val magnetLink: String? = null,
  val torrentFileUrl: String? = null,
  val seeders: Int? = null,
  val leechers: Int? = null,
  val fileSizeText: String? = null,
  val fileSizeBytes: Long? = null,
  val uploadDateText: String? = null,
  val uploadTimestamp: Long? = null,
  val uploader: String? = null,
  val isSourceHealthy: Boolean = true,
  val sourceHealth: SourceHealth = SourceHealth.UNKNOWN
) {
  val hasMagnet: Boolean get() = !magnetLink.isNullOrBlank()
  val hasDirectTorrent: Boolean get() = !torrentFileUrl.isNullOrBlank()
}

data class TorrentItem(
  val id: String,
  val title: String,
  val category: TorrentCategory,
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
  val isFavorite: Boolean = false,
  val collectionId: String? = null,
  val relevanceScore: Int = 0,
  val availableSources: List<TorrentSourceOffer> = emptyList()
) {
  val hasMagnet: Boolean get() = !magnetLink.isNullOrBlank()
  val hasDirectTorrent: Boolean get() = !torrentFileUrl.isNullOrBlank()
  val seasonEpisodeLabel: String?
    get() = when {
      season != null && episode != null -> "S%02dE%02d".format(season, episode)
      season != null -> "S%02d".format(season)
      else -> null
    }

  fun toPrimaryOffer(
    isSourceHealthy: Boolean = false,
    sourceHealth: SourceHealth = SourceHealth.UNKNOWN
  ): TorrentSourceOffer = TorrentSourceOffer(
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    magnetLink = magnetLink,
    torrentFileUrl = torrentFileUrl,
    seeders = seeders,
    leechers = leechers,
    fileSizeText = fileSizeText,
    fileSizeBytes = fileSizeBytes,
    uploadDateText = uploadDateText,
    uploadTimestamp = uploadTimestamp,
    uploader = uploader,
    isSourceHealthy = isSourceHealthy,
    sourceHealth = sourceHealth
  )

  val allSourceOffers: List<TorrentSourceOffer>
    get() = availableSources.ifEmpty { listOf(toPrimaryOffer()) }

  val sourceCount: Int
    get() = allSourceOffers.size.coerceAtLeast(1)
}

enum class SortOption(val label: String) {
  RELEVANCE("Relevance"),
  MOST_SEEDERS("Most seeders"),
  NEWEST("Newest uploads"),
  LARGEST("Largest file"),
  SMALLEST("Smallest file")
}

enum class FileSizeRange(val label: String, val minBytes: Long?, val maxBytes: Long?) {
  ANY("Any size", null, null),
  UNDER_1GB("< 1 GB", null, 1_073_741_824L),
  FROM_1_TO_5GB("1 – 5 GB", 1_073_741_824L, 5_368_709_120L),
  FROM_5_TO_20GB("5 – 20 GB", 5_368_709_120L, 21_474_836_480L),
  OVER_20GB("> 20 GB", 21_474_836_480L, null)
}

enum class UploadDateWindow(val label: String, val maxAgeMillis: Long?) {
  ANY("Any time", null),
  LAST_24H("Last 24h", 24L * 60 * 60 * 1000),
  LAST_7D("Last 7 days", 7L * 24 * 60 * 60 * 1000),
  LAST_30D("Last 30 days", 30L * 24 * 60 * 60 * 1000),
  LAST_YEAR("Past year", 365L * 24 * 60 * 60 * 1000)
}

data class SearchFilterState(
  val selectedCategories: Set<TorrentCategory> = emptySet(),
  val selectedResolutions: Set<String> = emptySet(),
  val minSeeders: Int = 0,
  val maxLeechers: Int? = null,
  val fileSizeRange: FileSizeRange = FileSizeRange.ANY,
  val languageQuery: String = "",
  val subtitlesOnly: Boolean = false,
  val uploadDateWindow: UploadDateWindow = UploadDateWindow.ANY,
  val selectedSourceIds: Set<String> = emptySet(),
  val sortOption: SortOption = SortOption.RELEVANCE
) {
  val activeCount: Int
    get() {
      var count = 0
      if (selectedCategories.isNotEmpty()) count++
      if (selectedResolutions.isNotEmpty()) count++
      if (minSeeders > 0) count++
      if (maxLeechers != null) count++
      if (fileSizeRange != FileSizeRange.ANY) count++
      if (languageQuery.isNotBlank()) count++
      if (subtitlesOnly) count++
      if (uploadDateWindow != UploadDateWindow.ANY) count++
      if (selectedSourceIds.isNotEmpty()) count++
      if (sortOption != SortOption.RELEVANCE) count++
      return count
    }

  val isDefault: Boolean get() = activeCount == 0
}

enum class ThemeMode(val label: String) {
  SYSTEM("System default"),
  LIGHT("Light mode"),
  DARK("Dark mode"),
  AMOLED("AMOLED black")
}

enum class AccentColorOption(val label: String, val primaryHex: Long, val secondaryHex: Long) {
  CYAN("Electric Cyan", 0xFF00D4AA, 0xFF0EA5E9),
  BLUE("Cobalt Blue", 0xFF3B82F6, 0xFF6366F1),
  AMBER("Amber Pulse", 0xFFF59E0B, 0xFFF97316),
  ROSE("Crimson Rose", 0xFFF43F5E, 0xFFEC4899),
  EMERALD("Signal Emerald", 0xFF10B981, 0xFF14B8A6),
  VIOLET("Ultraviolet", 0xFF8B5CF6, 0xFFA855F7)
}

enum class DisplayDensity(val label: String) {
  COMPACT("Compact"),
  COMFORTABLE("Comfortable")
}

data class UserPreferences(
  val themeMode: ThemeMode = ThemeMode.DARK,
  val accentColor: AccentColorOption = AccentColorOption.CYAN,
  val displayDensity: DisplayDensity = DisplayDensity.COMPACT,
  val defaultCategory: TorrentCategory = TorrentCategory.ALL,
  val defaultSort: SortOption = SortOption.RELEVANCE,
  val searchDebounceMs: Int = 450,
  val autoRefreshOnLaunch: Boolean = true,
  val sourceTimeoutSeconds: Int = 12,
  val maxConcurrentSources: Int = 4,
  val preferHttps: Boolean = true
)

data class FavoriteCollection(
  val id: String,
  val name: String,
  val createdAt: Long,
  val isSystem: Boolean = false
)

data class SearchHistoryItem(
  val id: Long,
  val query: String,
  val timestamp: Long,
  val resultCount: Int,
  val categoryLabel: String
)

sealed interface SourceProgressState {
  data object Loading : SourceProgressState
  data class Success(val count: Int, val durationMs: Long) : SourceProgressState
  data class Error(
    val message: String,
    val statusCode: Int? = null,
    val isCloudflare: Boolean = false,
    val redirectUrl: String? = null
  ) : SourceProgressState
}

enum class RepairVerificationStage(val label: String) {
  UNREACHABLE("Unreachable"),
  REACHABLE("Reachable"),
  RECOGNIZED("Recognized"),
  PARSED("Parsed"),
  USABLE("Usable")
}

data class VerifiedReplacement(
  val label: String,
  val replacementBaseUrl: String,
  val replacementSearchTemplate: String?,
  val replacementFeedUrl: String?,
  val detectedSourceType: SourceType?,
  val replacementHtmlSelectors: HtmlSelectorConfig? = null,
  val verificationSummary: String,
  val isVerified: Boolean = true,
  val reason: String = "",
  val stage: RepairVerificationStage = if (isVerified) RepairVerificationStage.USABLE else RepairVerificationStage.RECOGNIZED
)

data class SourceDiagnosticReport(
  val source: TorrentSource,
  val checkedAt: Long,
  val domainReachable: Boolean,
  val statusCode: Int?,
  val finalUrl: String?,
  val redirectChain: List<String>,
  val isCloudflareChallenge: Boolean,
  val parsingIssueDetected: Boolean,
  val isSpaPage: Boolean = false,
  val issueSummary: String,
  val recommendedActions: List<String>,
  val verifiedReplacements: List<VerifiedReplacement>,
  val verificationStage: RepairVerificationStage = RepairVerificationStage.UNREACHABLE,
  val testedUrl: String = "",
  val extractedCount: Int = 0
)

data class StorageUsageInfo(
  val imageCacheBytes: Long,
  val databaseBytes: Long,
  val cachedTorrentsCount: Int,
  val searchHistoryCount: Int,
  val favoritesCount: Int
) {
  val totalBytes: Long get() = imageCacheBytes + databaseBytes
}
