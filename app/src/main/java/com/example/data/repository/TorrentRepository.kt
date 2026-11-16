package com.example.data.repository

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import coil.ImageLoader
import com.example.data.local.db.AppDatabase
import com.example.data.local.db.CachedTorrentEntity
import com.example.data.local.db.DefaultSourcesSeeder
import com.example.data.local.db.FavoriteCollectionEntity
import com.example.data.local.db.FavoriteTorrentEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.TorrentSourceEntity
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.network.TorrentHttpClient
import com.example.data.parser.DetectedFeed
import com.example.data.parser.DiscoveredSourceCandidate
import com.example.data.parser.SourceRepairEngine
import com.example.data.parser.TorrentMetadataParser
import com.example.data.parser.UnifiedSourceAdapter
import com.example.domain.model.BackupCollectionEntry
import com.example.domain.model.BackupFavoriteEntry
import com.example.domain.model.BackupPreferencesEntry
import com.example.domain.model.BackupRestoreSummary
import com.example.domain.model.BackupSearchHistoryEntry
import com.example.domain.model.BackupSelection
import com.example.domain.model.BackupSourceEntry
import com.example.domain.model.FavoriteCollection
import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.RestoreMode
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SearchHistoryItem
import com.example.domain.model.SortOption
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceHealth
import com.example.domain.model.SourceProgressState
import com.example.domain.model.SourceType
import com.example.domain.model.StorageUsageInfo
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import com.example.domain.model.TorrentSourceOffer
import com.example.domain.model.TorrentTrackerBackup
import com.example.domain.model.UserPreferences
import com.example.domain.model.VerifiedReplacement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SourcePageRequest(
  val sourceId: String,
  val page: Int,
  val overrideUrl: String? = null
)

sealed interface ProgressiveSearchEvent {
  data class SourceStarted(val sourceId: String, val sourceName: String) : ProgressiveSearchEvent
  data class SourceCompleted(
    val sourceId: String,
    val sourceName: String,
    val items: List<TorrentItem>,
    val hasMore: Boolean,
    val nextPageUrl: String? = null,
    val requestUrl: String = "",
    val progress: SourceProgressState
  ) : ProgressiveSearchEvent
  data class CachedFallbackLoaded(val items: List<TorrentItem>) : ProgressiveSearchEvent
}

class TorrentRepository(
  private val context: Context,
  private val database: AppDatabase,
  val httpClient: TorrentHttpClient,
  private val imageLoader: ImageLoader
) {
  private val sourceDao = database.torrentSourceDao()
  private val favoriteDao = database.favoriteDao()
  private val historyDao = database.searchHistoryDao()
  private val cacheDao = database.cachedTorrentDao()

  val adapter = UnifiedSourceAdapter(httpClient)
  val repairEngine = SourceRepairEngine(httpClient, adapter)

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
    DefaultSourcesSeeder.seedIfEmpty(sourceDao)
    cacheDao.deleteArchiveCached()
  }

  // --- Sources ---

  fun observeSources(): Flow<List<TorrentSource>> =
    sourceDao.observeAll().map { list -> list.map { it.toDomain() } }

  suspend fun getSourceById(id: String): TorrentSource? =
    withContext(Dispatchers.IO) { sourceDao.getById(id)?.toDomain() }

  suspend fun saveSource(source: TorrentSource, preservePreviousSnapshot: Boolean = true) =
    withContext(Dispatchers.IO) {
      val existing = sourceDao.getById(source.id)?.toDomain()
      val snapshotJson = if (preservePreviousSnapshot && existing != null && existing.lastSuccessAt != null) {
        json.encodeToString(existing.copy(previousWorkingConfigJson = null))
      } else {
        source.previousWorkingConfigJson ?: existing?.previousWorkingConfigJson
      }
      sourceDao.upsert(source.copy(previousWorkingConfigJson = snapshotJson).toEntity())
    }

  suspend fun toggleSourceEnabled(sourceId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
    val existing = sourceDao.getById(sourceId) ?: return@withContext
    sourceDao.upsert(existing.copy(enabled = enabled))
  }

  suspend fun deleteSource(sourceId: String) = withContext(Dispatchers.IO) {
    sourceDao.deleteById(sourceId)
  }

  suspend fun restorePreviousSourceConfig(sourceId: String): Boolean = withContext(Dispatchers.IO) {
    val current = sourceDao.getById(sourceId)?.toDomain() ?: return@withContext false
    val prevJson = current.previousWorkingConfigJson ?: return@withContext false
    val restored = runCatching { json.decodeFromString<TorrentSource>(prevJson) }.getOrNull()
      ?: return@withContext false
    sourceDao.upsert(
      restored.copy(
        id = current.id,
        previousWorkingConfigJson = null,
        lastError = null
      ).toEntity()
    )
    true
  }

  suspend fun detectFeeds(
    url: String,
    timeoutSeconds: Int,
    allowCleartext: Boolean = false
  ): List<DetectedFeed> =
    withContext(Dispatchers.IO) {
      adapter.detectFeedsFromWebsite(url, timeoutSeconds, allowCleartext = allowCleartext)
    }

  suspend fun discoverSourceFromUrl(
    input: String,
    timeoutSeconds: Int,
    allowCleartext: Boolean = false
  ): DiscoveredSourceCandidate =
    withContext(Dispatchers.IO) {
      adapter.discoverSourceFromUrl(input, timeoutSeconds, allowCleartext = allowCleartext)
    }

  suspend fun testSingleSource(
    source: TorrentSource,
    timeoutSeconds: Int,
    @Suppress("UNUSED_PARAMETER") preferHttps: Boolean = true,
    forceNetwork: Boolean = false
  ): SourceProgressState = withContext(Dispatchers.IO) {
    val start = System.currentTimeMillis()
    // Strict security policy: global preferHttps never unlocks cleartext HTTP; per-source permission is mandatory.
    val allowCleartext = source.allowCleartextHttp
    val res = adapter.fetchPage(
      source = source,
      query = "ubuntu",
      page = 1,
      timeoutSeconds = timeoutSeconds,
      forceNetwork = forceNetwork,
      allowCleartext = allowCleartext
    )
    val duration = System.currentTimeMillis() - start
    val now = System.currentTimeMillis()
    val redirectUrl = res.redirectChain.firstOrNull()?.substringAfter("-> ")?.trim()

    val updated = source.copy(
      lastCheckedAt = now,
      lastSuccessAt = if (res.errorMessage == null) now else source.lastSuccessAt,
      lastError = res.errorMessage,
      lastStatusCode = res.statusCode,
      lastRedirectUrl = redirectUrl ?: if (res.errorMessage == null) null else source.lastRedirectUrl,
      requiresCloudflare = res.isCloudflareChallenge
    )
    sourceDao.upsert(updated.toEntity())

    if (res.errorMessage == null) {
      SourceProgressState.Success(res.items.size, duration)
    } else {
      SourceProgressState.Error(
        message = res.errorMessage,
        statusCode = res.statusCode,
        isCloudflare = res.isCloudflareChallenge,
        redirectUrl = redirectUrl
      )
    }
  }

  suspend fun testSelectorsPreview(source: TorrentSource, query: String, timeoutSeconds: Int): Pair<String, List<TorrentItem>> =
    withContext(Dispatchers.IO) {
      val res = adapter.fetchPage(
        source = source,
        query = query,
        page = 1,
        timeoutSeconds = timeoutSeconds,
        forceNetwork = true,
        allowCleartext = source.allowCleartextHttp
      )
      val msg = if (res.errorMessage != null) {
        "Error: ${res.errorMessage}"
      } else {
        "Extracted ${res.items.size} items (HTTP ${res.statusCode ?: 200})"
      }
      msg to res.items.take(5)
    }

  suspend fun runDiagnostic(sourceId: String, timeoutSeconds: Int): SourceDiagnosticReport? =
    withContext(Dispatchers.IO) {
      val source = sourceDao.getById(sourceId)?.toDomain() ?: return@withContext null
      repairEngine.diagnoseSource(source, timeoutSeconds)
    }

  suspend fun testReplacementUrl(url: String, source: TorrentSource, timeoutSeconds: Int): String =
    withContext(Dispatchers.IO) {
      repairEngine.testCandidateUrl(url, source, timeoutSeconds)
    }

  suspend fun applyVerifiedReplacement(sourceId: String, replacement: VerifiedReplacement): Boolean =
    withContext(Dispatchers.IO) {
      val current = sourceDao.getById(sourceId)?.toDomain() ?: return@withContext false
      val snapshot = json.encodeToString(current.copy(previousWorkingConfigJson = null))
      val nextBase = replacement.replacementBaseUrl
      val nextSearch = replacement.replacementSearchTemplate ?: current.searchUrlTemplate
      val nextFeed = replacement.replacementFeedUrl ?: current.feedUrl
      val usesHttp = nextBase.startsWith("http://", true) ||
        nextSearch.startsWith("http://", true) ||
        nextFeed.startsWith("http://", true)
      val updated = current.copy(
        baseUrl = nextBase,
        searchUrlTemplate = nextSearch,
        feedUrl = nextFeed,
        sourceType = replacement.detectedSourceType ?: current.sourceType,
        htmlSelectors = replacement.replacementHtmlSelectors ?: current.htmlSelectors,
        allowCleartextHttp = if (usesHttp) current.allowCleartextHttp else false,
        lastError = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = snapshot
      )
      sourceDao.upsert(updated.toEntity())
      true
    }

  suspend fun markCloudflareVerifiedCheck(
    sourceId: String,
    timeoutSeconds: Int,
    preferHttps: Boolean = true
  ): SourceProgressState {
    val source = getSourceById(sourceId) ?: return SourceProgressState.Error("Source not found")
    return testSingleSource(source, timeoutSeconds, preferHttps = preferHttps, forceNetwork = true)
  }

  // --- Multi-source Progressive Search & Refresh ---

  fun searchAcrossSources(
    query: String,
    page: Int,
    timeoutSeconds: Int,
    maxConcurrency: Int,
    specificSourceId: String? = null,
    preferHttps: Boolean = true,
    forceNetwork: Boolean = false,
    pageRequests: Map<String, SourcePageRequest> = emptyMap()
  ): Flow<ProgressiveSearchEvent> = channelFlow {
    ensureSeeded()
    val enabledSources = withContext(Dispatchers.IO) {
      val all = sourceDao.getEnabled().map { it.toDomain() }
      when {
        specificSourceId != null -> all.filter { it.id == specificSourceId }
        pageRequests.isNotEmpty() -> all.filter { pageRequests.containsKey(it.id) }
        else -> all
      }
    }

    val cacheKey = "${query.trim().lowercase()}_p$page"
    if (page == 1 && specificSourceId == null && pageRequests.isEmpty()) {
      val cached = withContext(Dispatchers.IO) { cacheDao.getByQueryKey(cacheKey) }
      if (cached.isNotEmpty()) {
        send(ProgressiveSearchEvent.CachedFallbackLoaded(cached.map { it.toDomain() }))
      }
    }

    val semaphore = Semaphore(maxConcurrency.coerceIn(1, 8))
    coroutineScope {
      enabledSources.forEach { source ->
        launch(Dispatchers.IO) {
          semaphore.withPermit {
            val req = pageRequests[source.id]
            val targetPage = req?.page ?: page
            val allowCleartext = source.allowCleartextHttp

            send(ProgressiveSearchEvent.SourceStarted(source.id, source.name))
            val start = System.currentTimeMillis()
            val result = adapter.fetchPage(
              source = source,
              query = query,
              page = targetPage,
              timeoutSeconds = timeoutSeconds,
              overrideUrl = req?.overrideUrl,
              forceNetwork = forceNetwork,
              allowCleartext = allowCleartext
            )
            val duration = System.currentTimeMillis() - start
            val now = System.currentTimeMillis()
            val redirectUrl = result.redirectChain.firstOrNull()?.substringAfter("-> ")?.trim()

            val updatedEntity = source.copy(
              lastCheckedAt = now,
              lastSuccessAt = if (result.errorMessage == null) now else source.lastSuccessAt,
              lastError = result.errorMessage,
              lastStatusCode = result.statusCode,
              lastRedirectUrl = redirectUrl ?: if (result.errorMessage == null) null else source.lastRedirectUrl,
              requiresCloudflare = result.isCloudflareChallenge
            ).toEntity()
            sourceDao.upsert(updatedEntity)

            if (result.items.isNotEmpty() && specificSourceId == null && targetPage == 1) {
              cacheDao.insertAll(result.items.map { it.toCacheEntity(cacheKey, now) })
              cacheDao.pruneOlderThan(now - 7L * 24 * 60 * 60 * 1000)
            }

            val progress = if (result.errorMessage == null) {
              SourceProgressState.Success(result.items.size, duration)
            } else {
              SourceProgressState.Error(
                message = result.errorMessage,
                statusCode = result.statusCode,
                isCloudflare = result.isCloudflareChallenge,
                redirectUrl = redirectUrl
              )
            }

            send(
              ProgressiveSearchEvent.SourceCompleted(
                sourceId = source.id,
                sourceName = source.name,
                items = result.items,
                hasMore = result.hasNextPage,
                nextPageUrl = result.nextPageUrl,
                requestUrl = result.requestUrl,
                progress = progress
              )
            )
          }
        }
      }
    }
  }

  suspend fun runAllSourcesDiagnostic(
    timeoutSeconds: Int,
    maxConcurrency: Int = 4,
    preferHttps: Boolean = true
  ): List<Pair<TorrentSource, SourceProgressState>> =
    withContext(Dispatchers.IO) {
      val all = sourceDao.getAll().map { it.toDomain() }.filter { it.enabled }
      val semaphore = Semaphore(maxConcurrency.coerceIn(1, 8))
      coroutineScope {
        all.map { source ->
          async {
            semaphore.withPermit {
              source to testSingleSource(source, timeoutSeconds, preferHttps = preferHttps, forceNetwork = true)
            }
          }
        }.awaitAll()
      }
    }

  /**
   * Groups matching torrents across sources while strictly avoiding false merges:
   * - Verified infoHashes group strictly by lowercase infoHash (never merges differing infoHashes).
   * - Hashless entries group conservatively by normalized title + year + season + episode + resolution + compatible size.
   * All matching source offers remain attached on [TorrentItem.availableSources] and the best recommended
   * source is promoted to primary fields.
   */
  fun deduplicateAndFilter(
    items: List<TorrentItem>,
    favoriteIds: Set<String>,
    filter: SearchFilterState,
    sourceHealthMap: Map<String, SourceHealth> = emptyMap()
  ): List<TorrentItem> {
    val buckets = LinkedHashMap<String, MutableList<TorrentItem>>()
    for (item in items) {
      val baseKey = TorrentMetadataParser.computeConservativeGroupKey(item)
      if (!item.infoHash.isNullOrBlank()) {
        buckets.getOrPut(baseKey) { mutableListOf() }.add(item)
      } else {
        var matchedKey: String? = null
        var suffix = 0
        while (true) {
          val candidateKey = if (suffix == 0) baseKey else "$baseKey#$suffix"
          val existingList = buckets[candidateKey]
          if (existingList == null) {
            matchedKey = candidateKey
            break
          }
          val rep = existingList.first()
          if (TorrentMetadataParser.areFallbackSizesCompatible(rep.fileSizeBytes, item.fileSizeBytes)) {
            matchedKey = candidateKey
            break
          }
          suffix++
        }
        buckets.getOrPut(matchedKey!!) { mutableListOf() }.add(item)
      }
    }

    val groupedItems = buckets.values.map { group ->
      mergeGroupedItems(group, favoriteIds, sourceHealthMap)
    }

    val now = System.currentTimeMillis()
    val filtered = groupedItems.filter { item ->
      val catMatch = filter.selectedCategories.isEmpty() ||
        filter.selectedCategories.contains(TorrentCategory.ALL) ||
        filter.selectedCategories.contains(item.category)

      val resMatch = filter.selectedResolutions.isEmpty() ||
        (item.resolution != null && filter.selectedResolutions.any { it.equals(item.resolution, true) })

      val seedMatch = filter.minSeeders <= 0 || (item.seeders ?: 0) >= filter.minSeeders

      val leechMatch = filter.maxLeechers == null || (item.leechers ?: 0) <= filter.maxLeechers

      val sizeMatch = when {
        filter.fileSizeRange.minBytes == null && filter.fileSizeRange.maxBytes == null -> true
        item.fileSizeBytes == null -> false
        else -> {
          val minOk = filter.fileSizeRange.minBytes?.let { item.fileSizeBytes >= it } ?: true
          val maxOk = filter.fileSizeRange.maxBytes?.let { item.fileSizeBytes <= it } ?: true
          minOk && maxOk
        }
      }

      val langMatch = filter.languageQuery.isBlank() ||
        item.language?.contains(filter.languageQuery.trim(), ignoreCase = true) == true ||
        item.title.contains(filter.languageQuery.trim(), ignoreCase = true)

      val subMatch = !filter.subtitlesOnly || !item.subtitles.isNullOrBlank()

      val dateMatch = filter.uploadDateWindow.maxAgeMillis?.let { maxAge ->
        item.uploadTimestamp != null && (now - item.uploadTimestamp) <= maxAge
      } ?: true

      val sourceMatch = filter.selectedSourceIds.isEmpty() ||
        item.allSourceOffers.any { filter.selectedSourceIds.contains(it.sourceId) }

      catMatch && resMatch && seedMatch && leechMatch && sizeMatch && langMatch && subMatch && dateMatch && sourceMatch
    }

    return when (filter.sortOption) {
      SortOption.RELEVANCE -> filtered.sortedWith(
        compareByDescending<TorrentItem> { it.relevanceScore }
          .thenByDescending { it.seeders ?: -1 }
      )
      SortOption.MOST_SEEDERS -> filtered.sortedByDescending { it.seeders ?: -1 }
      SortOption.NEWEST -> filtered.sortedByDescending { it.uploadTimestamp ?: 0L }
      SortOption.LARGEST -> filtered.sortedByDescending { it.fileSizeBytes ?: -1L }
      SortOption.SMALLEST -> filtered.sortedBy { it.fileSizeBytes ?: Long.MAX_VALUE }
    }
  }

  private fun mergeGroupedItems(
    group: List<TorrentItem>,
    favoriteIds: Set<String>,
    sourceHealthMap: Map<String, SourceHealth>
  ): TorrentItem {
    val rawOffers = group.flatMap { item ->
      item.allSourceOffers.map { offer ->
        val health = sourceHealthMap[offer.sourceId] ?: offer.sourceHealth
        val isOnline = health == SourceHealth.ONLINE
        offer.copy(isSourceHealthy = isOnline, sourceHealth = health)
      }
    }

    // Deduplicate identical offers from the same source, keeping the richer/higher-seeded one
    val dedupOffers = LinkedHashMap<String, TorrentSourceOffer>()
    for (offer in rawOffers) {
      val offerKey = "${offer.sourceId}|${offer.sourceUrl.orEmpty()}|${offer.magnetLink.orEmpty()}|${offer.torrentFileUrl.orEmpty()}"
      val existing = dedupOffers[offerKey]
      if (existing == null || scoreOffer(offer) > scoreOffer(existing)) {
        dedupOffers[offerKey] = offer
      }
    }

    val sortedOffers = dedupOffers.values.sortedByDescending { scoreOffer(it) }
    val primaryOffer = sortedOffers.first()
    val primaryItem = group.firstOrNull { it.sourceId == primaryOffer.sourceId } ?: group.first()

    val isFav = group.any { favoriteIds.contains(it.id) }
    val mergedCollectionId = group.firstNotNullOfOrNull { it.collectionId }
    val canonicalId = if (isFav) {
      group.firstOrNull { favoriteIds.contains(it.id) }?.id ?: primaryItem.id
    } else {
      primaryItem.id
    }

    return primaryItem.copy(
      id = canonicalId,
      sourceId = primaryOffer.sourceId,
      sourceName = primaryOffer.sourceName,
      sourceUrl = primaryOffer.sourceUrl ?: primaryItem.sourceUrl,
      magnetLink = primaryOffer.magnetLink ?: primaryItem.magnetLink,
      torrentFileUrl = primaryOffer.torrentFileUrl ?: primaryItem.torrentFileUrl,
      seeders = primaryOffer.seeders ?: primaryItem.seeders,
      leechers = primaryOffer.leechers ?: primaryItem.leechers,
      fileSizeText = primaryOffer.fileSizeText ?: primaryItem.fileSizeText,
      fileSizeBytes = primaryOffer.fileSizeBytes ?: primaryItem.fileSizeBytes,
      uploadDateText = primaryOffer.uploadDateText ?: primaryItem.uploadDateText,
      uploadTimestamp = primaryOffer.uploadTimestamp ?: primaryItem.uploadTimestamp,
      uploader = primaryOffer.uploader ?: primaryItem.uploader,
      coverImageUrl = primaryItem.coverImageUrl ?: group.firstNotNullOfOrNull { it.coverImageUrl },
      description = primaryItem.description ?: group.firstNotNullOfOrNull { it.description },
      language = primaryItem.language ?: group.firstNotNullOfOrNull { it.language },
      subtitles = primaryItem.subtitles ?: group.firstNotNullOfOrNull { it.subtitles },
      releaseYear = primaryItem.releaseYear ?: group.firstNotNullOfOrNull { it.releaseYear },
      season = primaryItem.season ?: group.firstNotNullOfOrNull { it.season },
      episode = primaryItem.episode ?: group.firstNotNullOfOrNull { it.episode },
      resolution = primaryItem.resolution ?: group.firstNotNullOfOrNull { it.resolution },
      videoQuality = primaryItem.videoQuality ?: group.firstNotNullOfOrNull { it.videoQuality },
      encoding = primaryItem.encoding ?: group.firstNotNullOfOrNull { it.encoding },
      tags = group.flatMap { it.tags }.distinct().take(8),
      relevanceScore = group.maxOf { it.relevanceScore },
      isFavorite = isFav,
      collectionId = mergedCollectionId,
      availableSources = sortedOffers
    )
  }

  private fun scoreOffer(offer: TorrentSourceOffer): Int {
    var score = when (offer.sourceHealth) {
      SourceHealth.ONLINE -> 5_000
      SourceHealth.UNKNOWN -> 0
      SourceHealth.REDIRECTED -> -500
      SourceHealth.CLOUDFLARE_REQUIRED,
      SourceHealth.PARSE_ERROR,
      SourceHealth.OFFLINE,
      SourceHealth.DISABLED -> -4_000
    }
    if (offer.hasMagnet) score += 1_500
    if (offer.hasDirectTorrent) score += 700
    if (!offer.sourceUrl.isNullOrBlank()) score += 200
    score += ((offer.seeders ?: 0).coerceAtLeast(0).coerceAtMost(20_000)) * 2
    if (offer.fileSizeBytes != null && offer.fileSizeBytes > 0) score += 150
    if (offer.uploadTimestamp != null) score += 80
    if (!offer.uploader.isNullOrBlank()) score += 40
    return score
  }

  // --- Favorites & Custom Collections ---

  fun observeFavorites(): Flow<List<TorrentItem>> =
    favoriteDao.observeAllFavorites().map { list -> list.map { it.toDomain() } }

  fun observeFavoriteIds(): Flow<Set<String>> =
    favoriteDao.observeFavoriteIds().map { it.toSet() }

  fun observeCollections(): Flow<List<FavoriteCollection>> =
    favoriteDao.observeCollections().map { list -> list.map { it.toDomain() } }

  suspend fun toggleFavorite(item: TorrentItem): Boolean = withContext(Dispatchers.IO) {
    val existing = favoriteDao.getFavoriteById(item.id)
    if (existing != null) {
      favoriteDao.deleteFavoriteById(item.id)
      false
    } else {
      favoriteDao.upsertFavorite(item.toFavoriteEntity(System.currentTimeMillis()))
      true
    }
  }

  suspend fun getFavoriteOrCachedItem(id: String): TorrentItem? = withContext(Dispatchers.IO) {
    favoriteDao.getFavoriteById(id)?.toDomain()
      ?: cacheDao.getRecentCached().firstOrNull { it.id == id }?.toDomain()
  }

  suspend fun createCollection(name: String): FavoriteCollection = withContext(Dispatchers.IO) {
    val entity = FavoriteCollectionEntity(
      id = "col_${UUID.randomUUID().toString().take(8)}",
      name = name.trim(),
      createdAt = System.currentTimeMillis(),
      isSystem = false
    )
    favoriteDao.upsertCollection(entity)
    entity.toDomain()
  }

  suspend fun renameCollection(collectionId: String, newName: String) = withContext(Dispatchers.IO) {
    if (newName.isNotBlank()) {
      favoriteDao.renameCollection(collectionId, newName.trim())
    }
  }

  suspend fun deleteCollection(collectionId: String) = withContext(Dispatchers.IO) {
    favoriteDao.clearCollectionReference(collectionId)
    favoriteDao.deleteCollection(collectionId)
  }

  suspend fun moveFavoriteToCollection(torrentId: String, collectionId: String?) = withContext(Dispatchers.IO) {
    favoriteDao.moveToCollection(torrentId, collectionId)
  }

  suspend fun updateFavoritePreferredOffer(torrentId: String, offer: TorrentSourceOffer) = withContext(Dispatchers.IO) {
    val existing = favoriteDao.getFavoriteById(torrentId)?.toDomain() ?: return@withContext
    val reordered = listOf(offer) + existing.allSourceOffers.filterNot {
      it.sourceId == offer.sourceId &&
        it.sourceUrl.orEmpty() == offer.sourceUrl.orEmpty() &&
        it.magnetLink.orEmpty() == offer.magnetLink.orEmpty()
    }
    val updated = existing.copy(
      sourceId = offer.sourceId,
      sourceName = offer.sourceName,
      sourceUrl = offer.sourceUrl ?: existing.sourceUrl,
      magnetLink = offer.magnetLink ?: existing.magnetLink,
      torrentFileUrl = offer.torrentFileUrl ?: existing.torrentFileUrl,
      seeders = offer.seeders ?: existing.seeders,
      leechers = offer.leechers ?: existing.leechers,
      fileSizeText = offer.fileSizeText ?: existing.fileSizeText,
      fileSizeBytes = offer.fileSizeBytes ?: existing.fileSizeBytes,
      uploadDateText = offer.uploadDateText ?: existing.uploadDateText,
      uploadTimestamp = offer.uploadTimestamp ?: existing.uploadTimestamp,
      uploader = offer.uploader ?: existing.uploader,
      availableSources = reordered
    )
    favoriteDao.upsertFavorite(updated.toFavoriteEntity(System.currentTimeMillis()))
  }

  fun clearVerificationCookies() {
    httpClient.cookieJar.clearAllCookies()
  }

  suspend fun buildDiagnosticReportText(appVersion: String = ""): String = withContext(Dispatchers.IO) {
    val sources = sourceDao.getAll().map { it.toDomain() }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    buildString {
      appendLine("Torrent Tracker Diagnostic Report")
      appendLine("Generated: ${fmt.format(Date())}")
      appendLine("App Version: ${appVersion.ifBlank { "1.0" }}")
      appendLine("Android SDK: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Total Sources: ${sources.size} (Enabled: ${sources.count { it.enabled }})")
      appendLine("Cookie Policy: Session cookies & tokens intentionally excluded")
      appendLine("---")
      sources.forEach { s ->
        val checkedLabel = s.lastCheckedAt?.let { fmt.format(Date(it)) } ?: "Never"
        appendLine("Source: ${s.name} [${s.id}]")
        appendLine("  Type: ${s.sourceType.label} | Enabled: ${s.enabled} | Health: ${s.health.name}")
        appendLine("  Base URL: ${s.baseUrl} | HTTP Allowed: ${s.allowCleartextHttp}")
        appendLine("  Last Checked: $checkedLabel | HTTP Status: ${s.lastStatusCode ?: "N/A"}")
        if (!s.lastRedirectUrl.isNullOrBlank()) appendLine("  Last Redirect: ${s.lastRedirectUrl}")
        if (!s.lastError.isNullOrBlank()) appendLine("  Last Error: ${s.lastError}")
      }
    }
  }

  // --- Search History ---

  fun observeSearchHistory(): Flow<List<SearchHistoryItem>> =
    historyDao.observeHistory().map { list -> list.map { it.toDomain() } }

  suspend fun recordSearch(query: String, resultCount: Int, category: TorrentCategory) =
    withContext(Dispatchers.IO) {
      val clean = query.trim()
      if (clean.isBlank()) return@withContext
      historyDao.deleteByQuery(clean)
      historyDao.insert(
        SearchHistoryEntity(
          query = clean,
          timestamp = System.currentTimeMillis(),
          resultCount = resultCount,
          categoryLabel = category.label
        )
      )
    }

  suspend fun deleteHistoryItem(id: Long) = withContext(Dispatchers.IO) {
    historyDao.deleteById(id)
  }

  suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
    historyDao.clearAll()
  }

  // --- Backup & Restore (Portable Versioned JSON, Zero Cookies/Secrets) ---

  suspend fun exportBackupJson(
    selection: BackupSelection,
    preferences: UserPreferences,
    appVersion: String = ""
  ): String = withContext(Dispatchers.IO) {
    val sources = if (selection.includeSources) {
      sourceDao.getAll().map { entity ->
        val domain = entity.toDomain()
        BackupSourceEntry(
          id = domain.id,
          name = domain.name,
          baseUrl = domain.baseUrl,
          searchUrlTemplate = domain.searchUrlTemplate,
          feedUrl = domain.feedUrl,
          sourceType = domain.sourceType,
          enabled = domain.enabled,
          isBuiltIn = domain.isBuiltIn,
          allowCleartextHttp = domain.allowCleartextHttp,
          htmlSelectors = domain.htmlSelectors,
          jsonMapping = domain.jsonMapping,
          createdAt = domain.createdAt
        )
      }
    } else emptyList()

    val collections = if (selection.includeFavoritesAndCollections) {
      favoriteDao.getAllCollectionsOnce().map { col ->
        BackupCollectionEntry(
          id = col.id,
          name = col.name,
          createdAt = col.createdAt,
          isSystem = col.isSystem
        )
      }
    } else emptyList()

    val favorites = if (selection.includeFavoritesAndCollections) {
      favoriteDao.getAllFavoritesOnce().map { fav ->
        val domain = fav.toDomain()
        BackupFavoriteEntry(
          id = fav.id,
          title = fav.title,
          category = domain.category,
          collectionId = fav.collectionId,
          releaseYear = fav.releaseYear,
          season = fav.season,
          episode = fav.episode,
          resolution = fav.resolution,
          videoQuality = fav.videoQuality,
          encoding = fav.encoding,
          fileSizeText = fav.fileSizeText,
          fileSizeBytes = fav.fileSizeBytes,
          seeders = fav.seeders,
          leechers = fav.leechers,
          uploadDateText = fav.uploadDateText,
          uploadTimestamp = fav.uploadTimestamp,
          uploader = fav.uploader,
          sourceId = fav.sourceId,
          sourceName = fav.sourceName,
          sourceUrl = fav.sourceUrl,
          magnetLink = fav.magnetLink,
          torrentFileUrl = fav.torrentFileUrl,
          infoHash = fav.infoHash,
          coverImageUrl = fav.coverImageUrl,
          language = fav.language,
          subtitles = fav.subtitles,
          tags = domain.tags,
          description = fav.description,
          savedAt = fav.savedAt,
          availableSources = domain.availableSources
        )
      }
    } else emptyList()

    val prefsEntry = if (selection.includePreferences) {
      BackupPreferencesEntry(
        themeMode = preferences.themeMode,
        accentColor = preferences.accentColor,
        displayDensity = preferences.displayDensity,
        defaultCategory = preferences.defaultCategory,
        defaultSort = preferences.defaultSort,
        searchDebounceMs = preferences.searchDebounceMs,
        autoRefreshOnLaunch = preferences.autoRefreshOnLaunch,
        sourceTimeoutSeconds = preferences.sourceTimeoutSeconds,
        maxConcurrentSources = preferences.maxConcurrentSources,
        preferHttps = preferences.preferHttps
      )
    } else null

    val history = if (selection.includeSearchHistory) {
      historyDao.getAllHistoryOnce().map { h ->
        BackupSearchHistoryEntry(
          query = h.query,
          timestamp = h.timestamp,
          resultCount = h.resultCount,
          categoryLabel = h.categoryLabel
        )
      }
    } else emptyList()

    val backup = TorrentTrackerBackup(
      schemaVersion = TorrentTrackerBackup.CURRENT_SCHEMA_VERSION,
      exportedAt = System.currentTimeMillis(),
      appVersion = appVersion,
      sources = sources,
      favorites = favorites,
      collections = collections,
      preferences = prefsEntry,
      searchHistory = history
    )
    val serialized = json.encodeToString(backup)
    require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES) {
      "Exported backup exceeds 4 MB safety limit."
    }
    serialized
  }

  fun parseAndValidateBackup(rawJson: String): Result<TorrentTrackerBackup> {
    if (rawJson.isBlank()) {
      return Result.failure(IllegalArgumentException("Backup file is empty."))
    }
    if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
      return Result.failure(IllegalArgumentException("Backup file exceeds 4 MB safety limit."))
    }
    return runCatching {
      val parsed = json.decodeFromString<TorrentTrackerBackup>(rawJson)
      require(parsed.schemaVersion in 1..TorrentTrackerBackup.CURRENT_SCHEMA_VERSION) {
        "Unsupported backup schema version (${parsed.schemaVersion}). Please update Torrent Tracker."
      }
      require(
        parsed.sources.size <= MAX_BACKUP_ITEMS &&
          parsed.favorites.size <= MAX_BACKUP_ITEMS &&
          parsed.collections.size <= MAX_BACKUP_ITEMS &&
          parsed.searchHistory.size <= MAX_BACKUP_ITEMS
      ) {
        "Backup exceeds maximum item count ($MAX_BACKUP_ITEMS per section)."
      }
      require(parsed.sources.distinctBy { it.id }.size == parsed.sources.size) {
        "Backup contains duplicate source IDs."
      }
      require(parsed.collections.distinctBy { it.id }.size == parsed.collections.size) {
        "Backup contains duplicate collection IDs."
      }
      require(parsed.favorites.distinctBy { it.id }.size == parsed.favorites.size) {
        "Backup contains duplicate favorite IDs."
      }

      parsed.sources.forEach { src ->
        require(src.id.isNotBlank() && src.name.isNotBlank()) {
          "Backup contains a source with missing ID or name."
        }
        require(isValidHttpUrl(src.baseUrl)) {
          "Source '${src.name}' has an invalid base URL."
        }
        if (src.searchUrlTemplate.isNotBlank()) {
          val probeTemplate = src.searchUrlTemplate.replace("{query}", "test").replace("{page}", "1")
          require(isValidHttpUrl(probeTemplate)) {
            "Source '${src.name}' has an invalid search URL template."
          }
        }
        if (src.feedUrl.isNotBlank()) {
          val probeFeed = src.feedUrl.replace("{query}", "test").replace("{page}", "1")
          require(isValidHttpUrl(probeFeed)) {
            "Source '${src.name}' has an invalid feed URL."
          }
        }
      }

      val backupCollectionIds = parsed.collections.map { it.id }.toSet()
      parsed.collections.forEach { col ->
        require(col.id.isNotBlank() && col.name.isNotBlank()) {
          "Backup contains a collection with missing ID or name."
        }
      }
      parsed.favorites.forEach { fav ->
        require(fav.id.isNotBlank() && fav.title.isNotBlank() && fav.sourceId.isNotBlank() && fav.sourceName.isNotBlank()) {
          "Backup contains an invalid favorite entry."
        }
        val colId = fav.collectionId
        if (!colId.isNullOrBlank()) {
          require(backupCollectionIds.contains(colId)) {
            "Favorite '${fav.title}' references unknown collection '$colId'."
          }
        }
      }
      parsed
    }
  }

  private fun isValidHttpUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    val lower = trimmed.lowercase()
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) return false
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
  }

  suspend fun restoreBackup(
    backup: TorrentTrackerBackup,
    selection: BackupSelection,
    mode: RestoreMode,
    preferencesRepository: PreferencesRepository? = null
  ): BackupRestoreSummary = withContext(Dispatchers.IO) {
    var sourcesRestored = 0
    var favoritesRestored = 0
    var collectionsRestored = 0
    var historyRestored = 0

    database.withTransaction {
      if (selection.includeSources) {
        val mappedSources = backup.sources.map { src ->
          val domain = TorrentSource(
            id = src.id,
            name = src.name,
            baseUrl = src.baseUrl,
            searchUrlTemplate = src.searchUrlTemplate,
            feedUrl = src.feedUrl,
            sourceType = src.sourceType,
            enabled = src.enabled,
            isBuiltIn = src.isBuiltIn,
            // Imported HTTP sources ALWAYS require fresh explicit confirmation on this device
            allowCleartextHttp = false,
            htmlSelectors = src.htmlSelectors,
            jsonMapping = src.jsonMapping,
            createdAt = src.createdAt
          )
          domain.toEntity()
        }
        if (mode == RestoreMode.REPLACE) {
          sourceDao.deleteAll()
          if (mappedSources.isNotEmpty()) {
            sourceDao.upsertAll(mappedSources)
          }
          sourcesRestored = mappedSources.size
        } else {
          // MERGE updates matching IDs AND inserts missing IDs while preserving unrelated sources
          if (mappedSources.isNotEmpty()) {
            sourceDao.upsertAll(mappedSources)
          }
          sourcesRestored = mappedSources.size
        }
      }

      if (selection.includeFavoritesAndCollections) {
        val mappedCollections = backup.collections.map { col ->
          FavoriteCollectionEntity(
            id = col.id,
            name = col.name,
            createdAt = col.createdAt,
            isSystem = col.isSystem
          )
        }
        if (mode == RestoreMode.REPLACE) {
          favoriteDao.deleteAllFavorites()
          favoriteDao.deleteAllCollections()
          if (mappedCollections.isNotEmpty()) {
            favoriteDao.upsertCollections(mappedCollections)
          }
          collectionsRestored = mappedCollections.size
        } else {
          // MERGE updates matching collection IDs and inserts missing ones
          if (mappedCollections.isNotEmpty()) {
            favoriteDao.upsertCollections(mappedCollections)
          }
          collectionsRestored = mappedCollections.size
        }

        val validCollectionIds = favoriteDao.getAllCollectionsOnce().map { it.id }.toSet()
        val mappedFavorites = backup.favorites.map { fav ->
          val safeCollectionId = fav.collectionId?.takeIf { validCollectionIds.contains(it) }
          if (!fav.collectionId.isNullOrBlank() && safeCollectionId == null) {
            throw IllegalArgumentException("Favorite '${fav.title}' references nonexistent collection '${fav.collectionId}'.")
          }
          FavoriteTorrentEntity(
            id = fav.id,
            title = fav.title,
            category = fav.category.name,
            collectionId = safeCollectionId,
            releaseYear = fav.releaseYear,
            season = fav.season,
            episode = fav.episode,
            resolution = fav.resolution,
            videoQuality = fav.videoQuality,
            encoding = fav.encoding,
            fileSizeText = fav.fileSizeText,
            fileSizeBytes = fav.fileSizeBytes,
            seeders = fav.seeders,
            leechers = fav.leechers,
            uploadDateText = fav.uploadDateText,
            uploadTimestamp = fav.uploadTimestamp,
            uploader = fav.uploader,
            sourceId = fav.sourceId,
            sourceName = fav.sourceName,
            sourceUrl = fav.sourceUrl,
            magnetLink = fav.magnetLink,
            torrentFileUrl = fav.torrentFileUrl,
            infoHash = fav.infoHash,
            coverImageUrl = fav.coverImageUrl,
            language = fav.language,
            subtitles = fav.subtitles,
            tagsJson = json.encodeToString(fav.tags),
            description = fav.description,
            savedAt = fav.savedAt,
            alternateSourcesJson = json.encodeToString(fav.availableSources)
          )
        }

        if (mappedFavorites.isNotEmpty()) {
          favoriteDao.upsertFavorites(mappedFavorites)
        }
        favoritesRestored = mappedFavorites.size
      }

      if (selection.includeSearchHistory) {
        val mappedHistory = backup.searchHistory
          .filter { it.query.isNotBlank() }
          .map { h ->
            SearchHistoryEntity(
              query = h.query.trim(),
              timestamp = h.timestamp,
              resultCount = h.resultCount,
              categoryLabel = h.categoryLabel
            )
          }
        if (mode == RestoreMode.REPLACE) {
          historyDao.clearAll()
          if (mappedHistory.isNotEmpty()) {
            historyDao.insertAll(mappedHistory)
            historyRestored = mappedHistory.size
          }
        } else {
          val existingQueries = historyDao.getAllHistoryOnce().map { it.query.trim().lowercase() }.toSet()
          val toInsert = mappedHistory.filterNot { existingQueries.contains(it.query.lowercase()) }
          if (toInsert.isNotEmpty()) {
            historyDao.insertAll(toInsert)
          }
          historyRestored = toInsert.size
        }
      }
    }

    var prefsRestored = false
    if (selection.includePreferences && backup.preferences != null && preferencesRepository != null) {
      preferencesRepository.applyBackupPreferences(backup.preferences)
      prefsRestored = true
    }

    BackupRestoreSummary(
      sourcesRestored = sourcesRestored,
      favoritesRestored = favoritesRestored,
      collectionsRestored = collectionsRestored,
      historyRestored = historyRestored,
      preferencesRestored = prefsRestored
    )
  }

  companion object {
    const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
    const val MAX_BACKUP_ITEMS = 5_000
  }

  // --- Storage & Cache Management ---

  suspend fun getStorageUsage(): StorageUsageInfo = withContext(Dispatchers.IO) {
    val cacheDir = File(context.cacheDir, "image_cache")
    val imgBytes = folderSize(cacheDir)
    val dbFile = context.getDatabasePath(AppDatabase.DB_NAME)
    val dbBytes = (dbFile?.length() ?: 0L) +
      (File(dbFile.path + "-wal").takeIf { it.exists() }?.length() ?: 0L)
    StorageUsageInfo(
      imageCacheBytes = imgBytes,
      databaseBytes = dbBytes,
      cachedTorrentsCount = cacheDao.count(),
      searchHistoryCount = historyDao.count(),
      favoritesCount = favoriteDao.countFavorites()
    )
  }

  @OptIn(coil.annotation.ExperimentalCoilApi::class)
  suspend fun clearImageCache() = withContext(Dispatchers.IO) {
    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
    File(context.cacheDir, "image_cache").deleteRecursively()
  }

  suspend fun clearTemporaryCache() = withContext(Dispatchers.IO) {
    cacheDao.clearAll()
  }

  private fun folderSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var size = 0L
    dir.listFiles()?.forEach { file ->
      size += if (file.isDirectory) folderSize(file) else file.length()
    }
    return size
  }

  // --- Entity Mapping ---

  private fun TorrentSourceEntity.toDomain(): TorrentSource = TorrentSource(
    id = id,
    name = name,
    baseUrl = baseUrl,
    searchUrlTemplate = searchUrlTemplate,
    feedUrl = feedUrl,
    sourceType = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.RSS_ATOM),
    enabled = enabled,
    isBuiltIn = isBuiltIn,
    allowCleartextHttp = allowCleartextHttp,
    htmlSelectors = runCatching { json.decodeFromString<HtmlSelectorConfig>(htmlSelectorsJson) }
      .getOrDefault(HtmlSelectorConfig()),
    jsonMapping = runCatching { json.decodeFromString<JsonMappingConfig>(jsonMappingJson) }
      .getOrDefault(JsonMappingConfig()),
    lastSuccessAt = lastSuccessAt,
    lastCheckedAt = lastCheckedAt,
    lastError = lastError,
    lastStatusCode = lastStatusCode,
    lastRedirectUrl = lastRedirectUrl,
    requiresCloudflare = requiresCloudflare,
    previousWorkingConfigJson = previousWorkingConfigJson,
    createdAt = createdAt
  )

  private fun TorrentSource.toEntity(): TorrentSourceEntity = TorrentSourceEntity(
    id = id,
    name = name,
    baseUrl = baseUrl,
    searchUrlTemplate = searchUrlTemplate,
    feedUrl = feedUrl,
    sourceType = sourceType.name,
    enabled = enabled,
    isBuiltIn = isBuiltIn,
    allowCleartextHttp = allowCleartextHttp,
    htmlSelectorsJson = json.encodeToString(htmlSelectors),
    jsonMappingJson = json.encodeToString(jsonMapping),
    lastSuccessAt = lastSuccessAt,
    lastCheckedAt = lastCheckedAt,
    lastError = lastError,
    lastStatusCode = lastStatusCode,
    lastRedirectUrl = lastRedirectUrl,
    requiresCloudflare = requiresCloudflare,
    previousWorkingConfigJson = previousWorkingConfigJson,
    createdAt = createdAt
  )

  private fun FavoriteTorrentEntity.toDomain(): TorrentItem = TorrentItem(
    id = id,
    title = title,
    category = runCatching { TorrentCategory.valueOf(category) }.getOrDefault(TorrentCategory.OTHER),
    releaseYear = releaseYear,
    season = season,
    episode = episode,
    resolution = resolution,
    videoQuality = videoQuality,
    encoding = encoding,
    fileSizeText = fileSizeText,
    fileSizeBytes = fileSizeBytes,
    seeders = seeders,
    leechers = leechers,
    uploadDateText = uploadDateText,
    uploadTimestamp = uploadTimestamp,
    uploader = uploader,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    magnetLink = magnetLink,
    torrentFileUrl = torrentFileUrl,
    infoHash = infoHash,
    coverImageUrl = coverImageUrl,
    language = language,
    subtitles = subtitles,
    tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
    description = description,
    isFavorite = true,
    collectionId = collectionId,
    availableSources = runCatching { json.decodeFromString<List<TorrentSourceOffer>>(alternateSourcesJson) }
      .getOrDefault(emptyList())
  )

  private fun TorrentItem.toFavoriteEntity(savedAt: Long): FavoriteTorrentEntity = FavoriteTorrentEntity(
    id = id,
    title = title,
    category = category.name,
    collectionId = collectionId,
    releaseYear = releaseYear,
    season = season,
    episode = episode,
    resolution = resolution,
    videoQuality = videoQuality,
    encoding = encoding,
    fileSizeText = fileSizeText,
    fileSizeBytes = fileSizeBytes,
    seeders = seeders,
    leechers = leechers,
    uploadDateText = uploadDateText,
    uploadTimestamp = uploadTimestamp,
    uploader = uploader,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    magnetLink = magnetLink,
    torrentFileUrl = torrentFileUrl,
    infoHash = infoHash,
    coverImageUrl = coverImageUrl,
    language = language,
    subtitles = subtitles,
    tagsJson = json.encodeToString(tags),
    description = description,
    savedAt = savedAt,
    alternateSourcesJson = json.encodeToString(allSourceOffers)
  )

  private fun CachedTorrentEntity.toDomain(): TorrentItem = TorrentItem(
    id = id,
    title = title,
    category = runCatching { TorrentCategory.valueOf(category) }.getOrDefault(TorrentCategory.OTHER),
    releaseYear = releaseYear,
    season = season,
    episode = episode,
    resolution = resolution,
    videoQuality = videoQuality,
    encoding = encoding,
    fileSizeText = fileSizeText,
    fileSizeBytes = fileSizeBytes,
    seeders = seeders,
    leechers = leechers,
    uploadDateText = uploadDateText,
    uploadTimestamp = uploadTimestamp,
    uploader = uploader,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    magnetLink = magnetLink,
    torrentFileUrl = torrentFileUrl,
    infoHash = infoHash,
    coverImageUrl = coverImageUrl,
    language = language,
    subtitles = subtitles,
    tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
    description = description,
    availableSources = runCatching { json.decodeFromString<List<TorrentSourceOffer>>(alternateSourcesJson) }
      .getOrDefault(emptyList())
  )

  private fun TorrentItem.toCacheEntity(queryKey: String, cachedAt: Long): CachedTorrentEntity = CachedTorrentEntity(
    id = id,
    queryKey = queryKey,
    title = title,
    category = category.name,
    releaseYear = releaseYear,
    season = season,
    episode = episode,
    resolution = resolution,
    videoQuality = videoQuality,
    encoding = encoding,
    fileSizeText = fileSizeText,
    fileSizeBytes = fileSizeBytes,
    seeders = seeders,
    leechers = leechers,
    uploadDateText = uploadDateText,
    uploadTimestamp = uploadTimestamp,
    uploader = uploader,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    magnetLink = magnetLink,
    torrentFileUrl = torrentFileUrl,
    infoHash = infoHash,
    coverImageUrl = coverImageUrl,
    language = language,
    subtitles = subtitles,
    tagsJson = json.encodeToString(tags),
    description = description,
    cachedAt = cachedAt,
    alternateSourcesJson = json.encodeToString(allSourceOffers)
  )

  private fun FavoriteCollectionEntity.toDomain(): FavoriteCollection = FavoriteCollection(
    id = id,
    name = name,
    createdAt = createdAt,
    isSystem = isSystem
  )

  private fun SearchHistoryEntity.toDomain(): SearchHistoryItem = SearchHistoryItem(
    id = id,
    query = query,
    timestamp = timestamp,
    resultCount = resultCount,
    categoryLabel = categoryLabel
  )
}
