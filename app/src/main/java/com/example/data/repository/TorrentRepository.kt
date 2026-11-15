package com.example.data.repository

import android.content.Context
import coil.ImageLoader
import com.example.data.local.db.AppDatabase
import com.example.data.local.db.CachedTorrentEntity
import com.example.data.local.db.DefaultSourcesSeeder
import com.example.data.local.db.FavoriteCollectionEntity
import com.example.data.local.db.FavoriteTorrentEntity
import com.example.data.local.db.SearchHistoryEntity
import com.example.data.local.db.TorrentSourceEntity
import com.example.data.network.TorrentHttpClient
import com.example.data.parser.DetectedFeed
import com.example.data.parser.DiscoveredSourceCandidate
import com.example.data.parser.SourceRepairEngine
import com.example.data.parser.TorrentMetadataParser
import com.example.data.parser.UnifiedSourceAdapter
import com.example.domain.model.FavoriteCollection
import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SearchHistoryItem
import com.example.domain.model.SortOption
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceProgressState
import com.example.domain.model.SourceType
import com.example.domain.model.StorageUsageInfo
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
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
import java.util.UUID

sealed interface ProgressiveSearchEvent {
  data class SourceStarted(val sourceId: String, val sourceName: String) : ProgressiveSearchEvent
  data class SourceCompleted(
    val sourceId: String,
    val sourceName: String,
    val items: List<TorrentItem>,
    val hasMore: Boolean,
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

  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

  suspend fun detectFeeds(url: String, timeoutSeconds: Int): List<DetectedFeed> =
    withContext(Dispatchers.IO) {
      adapter.detectFeedsFromWebsite(url, timeoutSeconds)
    }

  suspend fun discoverSourceFromUrl(input: String, timeoutSeconds: Int): DiscoveredSourceCandidate =
    withContext(Dispatchers.IO) {
      adapter.discoverSourceFromUrl(input, timeoutSeconds)
    }

  suspend fun testSingleSource(source: TorrentSource, timeoutSeconds: Int): SourceProgressState =
    withContext(Dispatchers.IO) {
      val start = System.currentTimeMillis()
      val res = adapter.fetchPage(source, "ubuntu", 1, timeoutSeconds)
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
      val res = adapter.fetchPage(source, query, 1, timeoutSeconds)
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
      val updated = current.copy(
        baseUrl = replacement.replacementBaseUrl,
        searchUrlTemplate = replacement.replacementSearchTemplate ?: current.searchUrlTemplate,
        feedUrl = replacement.replacementFeedUrl ?: current.feedUrl,
        sourceType = replacement.detectedSourceType ?: current.sourceType,
        htmlSelectors = replacement.replacementHtmlSelectors ?: current.htmlSelectors,
        lastError = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = snapshot
      )
      sourceDao.upsert(updated.toEntity())
      true
    }

  suspend fun markCloudflareVerifiedCheck(sourceId: String, timeoutSeconds: Int): SourceProgressState {
    val source = getSourceById(sourceId) ?: return SourceProgressState.Error("Source not found")
    return testSingleSource(source, timeoutSeconds)
  }

  // --- Multi-source Progressive Search & Refresh ---

  fun searchAcrossSources(
    query: String,
    page: Int,
    timeoutSeconds: Int,
    maxConcurrency: Int,
    specificSourceId: String? = null
  ): Flow<ProgressiveSearchEvent> = channelFlow {
    ensureSeeded()
    val favoriteIds = favoriteDao.getFavoriteById("__dummy__") // ensure DB ready
    val enabledSources = withContext(Dispatchers.IO) {
      val all = sourceDao.getEnabled().map { it.toDomain() }
      if (specificSourceId != null) all.filter { it.id == specificSourceId } else all
    }

    val cacheKey = "${query.trim().lowercase()}_p$page"
    if (page == 1 && specificSourceId == null) {
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
            send(ProgressiveSearchEvent.SourceStarted(source.id, source.name))
            val start = System.currentTimeMillis()
            val result = adapter.fetchPage(source, query, page, timeoutSeconds)
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

            if (result.items.isNotEmpty() && specificSourceId == null) {
              cacheDao.insertAll(result.items.map { it.toCacheEntity(cacheKey, now) })
              // Keep cache bounded
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
                progress = progress
              )
            )
          }
        }
      }
    }
  }

  suspend fun runAllSourcesDiagnostic(timeoutSeconds: Int): List<Pair<TorrentSource, SourceProgressState>> =
    withContext(Dispatchers.IO) {
      val all = sourceDao.getAll().map { it.toDomain() }.filter { it.enabled }
      coroutineScope {
        all.map { source ->
          async { source to testSingleSource(source, timeoutSeconds) }
        }.awaitAll()
      }
    }

  fun deduplicateAndFilter(
    items: List<TorrentItem>,
    favoriteIds: Set<String>,
    filter: SearchFilterState
  ): List<TorrentItem> {
    // Deduplicate by infoHash / normalized key, keeping highest-seeder or richer entry
    val dedupedMap = LinkedHashMap<String, TorrentItem>()
    for (item in items) {
      val key = TorrentMetadataParser.computeDedupeKey(item.infoHash, item.sourceUrl, item.title)
      val enriched = item.copy(isFavorite = favoriteIds.contains(item.id))
      val existing = dedupedMap[key]
      if (existing == null || (enriched.seeders ?: -1) > (existing.seeders ?: -1)) {
        dedupedMap[key] = enriched
      }
    }

    val now = System.currentTimeMillis()
    val filtered = dedupedMap.values.filter { item ->
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
        filter.selectedSourceIds.contains(item.sourceId)

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
    collectionId = collectionId
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
    savedAt = savedAt
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
    description = description
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
    cachedAt = cachedAt
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
