package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows
import coil.ImageLoader
import com.example.data.local.db.AppDatabase
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.network.TorrentHttpClient
import com.example.data.network.WebViewCookieJar
import com.example.data.parser.SourceRepairEngine
import com.example.data.parser.UnifiedSourceAdapter
import com.example.data.repository.SourcePageRequest
import com.example.data.repository.TorrentRepository
import com.example.domain.model.AccentColorOption
import com.example.domain.model.BackupSelection
import com.example.domain.model.DisplayDensity
import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.RepairVerificationStage
import com.example.domain.model.RestoreMode
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SortOption
import com.example.domain.model.SourceHealth
import com.example.domain.model.SourceProgressState
import com.example.domain.model.SourceType
import com.example.domain.model.ThemeMode
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import com.example.domain.model.UserPreferences
import com.example.ui.viewmodel.SearchViewModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TorrentTrackerReliabilityTest {

  private lateinit var ctx: Context
  private lateinit var db: AppDatabase
  private lateinit var httpClient: TorrentHttpClient
  private lateinit var repo: TorrentRepository
  private lateinit var prefsRepo: PreferencesRepository
  private val servers = mutableListOf<HttpServer>()

  @Before
  fun setup() {
    ctx = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    httpClient = TorrentHttpClient(WebViewCookieJar())
    prefsRepo = PreferencesRepository(ctx)
    repo = TorrentRepository(
      context = ctx,
      database = db,
      httpClient = httpClient,
      imageLoader = ImageLoader(ctx)
    )
  }

  @After
  fun tearDown() {
    servers.forEach { runCatching { it.stop(0) } }
    servers.clear()
    db.close()
  }

  private fun startLocalServer(handler: (String) -> Triple<Int, String, String>): String {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/") { exchange ->
      val query = exchange.requestURI.rawQuery?.let { "?$it" }.orEmpty()
      val fullPath = exchange.requestURI.path + query
      val (status, contentType, body) = handler(fullPath)
      val bytes = body.toByteArray(Charsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", contentType)
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    servers.add(server)
    return "http://127.0.0.1:${server.address.port}"
  }

  private fun sampleItem(
    id: String,
    title: String,
    sourceId: String,
    sourceName: String,
    infoHash: String? = null,
    seeders: Int? = 50,
    leechers: Int? = 5,
    year: Int? = 2024,
    season: Int? = null,
    episode: Int? = null,
    resolution: String? = "1080p",
    sizeBytes: Long? = 1_500_000_000L,
    magnet: String? = null,
    torrentUrl: String? = null
  ) = TorrentItem(
    id = id,
    title = title,
    category = TorrentCategory.MOVIES,
    releaseYear = year,
    season = season,
    episode = episode,
    resolution = resolution,
    fileSizeText = sizeBytes?.let { "${it / (1024 * 1024)} MB" },
    fileSizeBytes = sizeBytes,
    seeders = seeders,
    leechers = leechers,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = "https://$sourceId.example/item/$id",
    magnetLink = magnet ?: if (torrentUrl == null) infoHash?.let { "magnet:?xt=urn:btih:$it" } else null,
    torrentFileUrl = torrentUrl,
    infoHash = infoHash
  )

  // 1. Multi-source duplicate grouping + never merging differing infoHashes + Favorites persistence
  @Test
  fun `groups identical infoHashes across sources keeping all offers and never merges distinct hashes`() = runBlocking {
    val hashA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    val hashB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    val items = listOf(
      sampleItem("id1", "Open Film 2024 1080p", "s1", "AlphaSource", infoHash = hashA, seeders = 40, magnet = null, torrentUrl = "https://alpha/1.torrent"),
      sampleItem("id2", "Open.Film.2024.1080p.WEB", "s2", "BetaSource", infoHash = hashA, seeders = 320, magnet = "magnet:?xt=urn:btih:$hashA"),
      sampleItem("id3", "Open Film 2024 1080p", "s3", "GammaSource", infoHash = hashB, seeders = 999, magnet = "magnet:?xt=urn:btih:$hashB")
    )

    val health = mapOf("s1" to SourceHealth.ONLINE, "s2" to SourceHealth.ONLINE, "s3" to SourceHealth.ONLINE)
    val grouped = repo.deduplicateAndFilter(items, emptySet(), SearchFilterState(), health)

    assertEquals(2, grouped.size)
    val groupA = grouped.first { it.infoHash == hashA }
    assertEquals(2, groupA.sourceCount)
    assertEquals("BetaSource", groupA.sourceName)
    assertEquals(320, groupA.seeders)
    assertTrue(groupA.availableSources.any { it.sourceId == "s1" && it.torrentFileUrl == "https://alpha/1.torrent" })
    assertTrue(groupA.availableSources.any { it.sourceId == "s2" && it.seeders == 320 })

    // Verify source filter matches any offer inside grouped item
    val filteredByS1 = repo.deduplicateAndFilter(
      items,
      emptySet(),
      SearchFilterState(selectedSourceIds = setOf("s1")),
      health
    )
    assertEquals(1, filteredByS1.size)
    assertEquals(2, filteredByS1.first().sourceCount)

    // Save grouped item to Favorites and confirm all source offers survive persistence
    assertTrue(repo.toggleFavorite(groupA))
    val favs = repo.observeFavorites().first()
    assertEquals(1, favs.size)
    assertEquals(2, favs.first().availableSources.size)
    assertTrue(favs.first().availableSources.any { it.sourceName == "AlphaSource" })
    assertTrue(favs.first().availableSources.any { it.sourceName == "BetaSource" })
  }

  @Test
  fun `hashless grouping is conservative on year episode resolution and compatible file size`() {
    val base = sampleItem("h1", "Cosmos 2014 S01E01 1080p", "s1", "Src1", infoHash = null, year = 2014, season = 1, episode = 1, resolution = "1080p", sizeBytes = 1_000_000_000L, magnet = "magnet:?dn=1")
    val matchWithinTolerance = sampleItem("h2", "Cosmos 2014 S01E01 1080p", "s2", "Src2", infoHash = null, year = 2014, season = 1, episode = 1, resolution = "1080p", sizeBytes = 1_050_000_000L, magnet = "magnet:?dn=2")
    val diffResolution = sampleItem("h3", "Cosmos 2014 S01E01 720p", "s3", "Src3", infoHash = null, year = 2014, season = 1, episode = 1, resolution = "720p", sizeBytes = 1_000_000_000L, magnet = "magnet:?dn=3")
    val diffSize = sampleItem("h4", "Cosmos 2014 S01E01 1080p", "s4", "Src4", infoHash = null, year = 2014, season = 1, episode = 1, resolution = "1080p", sizeBytes = 3_000_000_000L, magnet = "magnet:?dn=4")

    val grouped = repo.deduplicateAndFilter(
      listOf(base, matchWithinTolerance, diffResolution, diffSize),
      emptySet(),
      SearchFilterState()
    )
    assertEquals(3, grouped.size)
    val mergedGroup = grouped.first { it.id == "h1" || it.id == "h2" }
    assertEquals(2, mergedGroup.sourceCount)
  }

  // 2. Real pagination across JSON API, RSS/Atom, HTML, and ViewModel loop guard
  @Test
  fun `pagination propagates nextPageUrl across JSON API RSS Atom HTML and ViewModel`() = runBlocking {
    val baseUrl = startLocalServer { path ->
      when {
        path.startsWith("/api?q=") && !path.contains("page=2") -> Triple(
          200,
          "application/json",
          """{"next":"/api?q=linux&page=2","results":[{"title":"Linux ISO Part 1","hash":"1111111111111111111111111111111111111111","seeds":50}]}"""
        )
        path.startsWith("/api?q=linux&page=2") -> Triple(
          200,
          "application/json",
          """{"next":null,"results":[{"title":"Linux ISO Part 2","hash":"2222222222222222222222222222222222222222","seeds":40}]}"""
        )
        path == "/rss/page1" -> Triple(
          200,
          "application/atom+xml",
          """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><link rel="next" href="/rss/page2"/><entry><title>Linux Atom Entry 1</title><id>urn:a1</id><link href="magnet:?xt=urn:btih:3333333333333333333333333333333333333333"/></entry></feed>"""
        )
        path == "/rss/page2" -> Triple(
          200,
          "application/atom+xml",
          """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Linux Atom Entry 2</title><id>urn:a2</id><link href="magnet:?xt=urn:btih:4444444444444444444444444444444444444444"/></entry></feed>"""
        )
        path == "/html/1" -> Triple(
          200,
          "text/html",
          """<html><body><div class="row"><a class="t" href="/d/1">HTML Entry 1</a><a href="magnet:?xt=urn:btih:5555555555555555555555555555555555555555">M</a></div><a rel="next" href="/html/2">Next</a></body></html>"""
        )
        path == "/html/2" -> Triple(
          200,
          "text/html",
          """<html><body><div class="row"><a class="t" href="/d/2">HTML Entry 2</a><a href="magnet:?xt=urn:btih:6666666666666666666666666666666666666666">M</a></div></body></html>"""
        )
        else -> Triple(404, "text/plain", "Not found")
      }
    }

    val adapter = UnifiedSourceAdapter(httpClient)
    val jsonSrc = TorrentSource(
      id = "p_json",
      name = "LocalJSON",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/api?q={query}",
      sourceType = SourceType.JSON_API,
      allowCleartextHttp = true,
      jsonMapping = JsonMappingConfig(itemsPath = "results", titleField = "title", infoHashField = "hash", seedersField = "seeds")
    )
    val jp1 = adapter.fetchPage(jsonSrc, "linux", 1, 5, allowCleartext = true)
    assertTrue(jp1.hasNextPage)
    assertEquals("$baseUrl/api?q=linux&page=2", jp1.nextPageUrl)
    val jp2 = adapter.fetchPage(jsonSrc, "linux", 2, 5, overrideUrl = jp1.nextPageUrl, allowCleartext = true)
    assertFalse(jp2.hasNextPage)
    assertEquals("Linux ISO Part 2", jp2.items.first().title)

    val rssSrc = TorrentSource(
      id = "p_rss",
      name = "LocalAtom",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/rss/page1",
      feedUrl = "$baseUrl/rss/page1",
      sourceType = SourceType.RSS_ATOM,
      allowCleartextHttp = true
    )
    val rp1 = adapter.fetchPage(rssSrc, "linux", 1, 5, allowCleartext = true)
    assertEquals("$baseUrl/rss/page2", rp1.nextPageUrl)
    val rp2 = adapter.fetchPage(rssSrc, "linux", 2, 5, overrideUrl = rp1.nextPageUrl, allowCleartext = true)
    assertFalse(rp2.hasNextPage)
    assertEquals("Linux Atom Entry 2", rp2.items.first().title)

    val htmlSrc = TorrentSource(
      id = "p_html",
      name = "LocalHTML",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/html/1",
      sourceType = SourceType.HTML_SELECTOR,
      allowCleartextHttp = true,
      htmlSelectors = HtmlSelectorConfig(itemContainer = "div.row", title = "a.t", detailsLink = "a.t", magnetLink = "a[href^=magnet:]", nextPageLink = "a[rel=next]")
    )
    val hp1 = adapter.fetchPage(htmlSrc, "", 1, 5, allowCleartext = true)
    assertEquals("$baseUrl/html/2", hp1.nextPageUrl)
    val hp2 = adapter.fetchPage(htmlSrc, "", 2, 5, overrideUrl = hp1.nextPageUrl, allowCleartext = true)
    assertFalse(hp2.hasNextPage)
    assertEquals("HTML Entry 2", hp2.items.first().title)

    // Verify end-to-end SearchViewModel loadNextPage stops cleanly with no duplicate loops
    prefsRepo.updateAutoRefreshOnLaunch(false)
    prefsRepo.updatePreferHttps(false)
    repo.ensureSeeded()
    db.torrentSourceDao().getAll().forEach { existing ->
      db.torrentSourceDao().upsert(existing.copy(enabled = false))
    }
    db.torrentSourceDao().upsert(
      com.example.data.local.db.TorrentSourceEntity(
        id = jsonSrc.id,
        name = jsonSrc.name,
        baseUrl = jsonSrc.baseUrl,
        searchUrlTemplate = jsonSrc.searchUrlTemplate,
        feedUrl = jsonSrc.feedUrl,
        sourceType = SourceType.JSON_API.name,
        enabled = true,
        isBuiltIn = false,
        allowCleartextHttp = true,
        htmlSelectorsJson = "{}",
        jsonMappingJson = """{"itemsPath":"results","titleField":"title","infoHashField":"hash","seedersField":"seeds"}""",
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = 1L
      )
    )

    val vm = SearchViewModel(repo, prefsRepo)
    val collectJob = launch { vm.uiState.collect {} }
    try {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.submitSearch("linux")
      repeat(50) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val s = vm.uiState.value
        if (!s.isLoading && s.results.isNotEmpty()) return@repeat
        delay(40)
      }
      assertEquals(1, vm.uiState.value.results.size)
      assertTrue(vm.uiState.value.hasMorePages)

      vm.loadNextPage()
      repeat(50) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val s = vm.uiState.value
        if (!s.isLoadingMore && s.results.size >= 2) return@repeat
        delay(40)
      }
      assertEquals(2, vm.uiState.value.results.size)
      assertFalse(vm.uiState.value.hasMorePages)
    } finally {
      collectJob.cancel()
    }
  }

  // 3. SourceRepairEngine verified vs unverified suggestions & rollback snapshot
  @Test
  fun `repair engine distinguishes verified feed fixes from unverified form suggestions and preserves rollback`() = runBlocking {
    val baseUrl = startLocalServer { path ->
      when {
        path == "/" -> Triple(
          200,
          "text/html",
          """
          <html>
            <head><link rel="alternate" type="application/rss+xml" title="Working RSS" href="/working.xml"/></head>
            <body>
              <form action="/empty-search"><input name="s" /></form>
            </body>
          </html>
          """.trimIndent()
        )
        path == "/broken" -> Triple(404, "text/html", "Missing")
        path == "/working.xml" -> Triple(
          200,
          "application/rss+xml",
          """<rss version="2.0"><channel><item><title>Verified Feed Item 1080p</title><link>magnet:?xt=urn:btih:7777777777777777777777777777777777777777</link></item></channel></rss>"""
        )
        path.startsWith("/empty-search") -> Triple(200, "text/html", "<html><body>No torrent table yet</body></html>")
        else -> Triple(404, "text/plain", "Not found")
      }
    }

    val adapter = UnifiedSourceAdapter(httpClient)
    val engine = SourceRepairEngine(httpClient, adapter)
    val initialSource = TorrentSource(
      id = "rep_src",
      name = "Repairable",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/broken",
      sourceType = SourceType.HTML_SELECTOR,
      allowCleartextHttp = true,
      lastSuccessAt = 1000L
    )
    repo.saveSource(initialSource, preservePreviousSnapshot = false)

    val report = engine.diagnoseSource(initialSource, timeoutSeconds = 5)
    val verified = report.verifiedReplacements.filter { it.isVerified }
    val unverified = report.verifiedReplacements.filter { !it.isVerified }

    assertTrue(verified.any { it.replacementFeedUrl?.endsWith("/working.xml") == true })
    assertTrue(unverified.any { it.replacementSearchTemplate?.contains("/empty-search") == true })

    val applied = repo.applyVerifiedReplacement(initialSource.id, verified.first())
    assertTrue(applied)
    val afterApply = repo.getSourceById(initialSource.id)!!
    assertEquals("$baseUrl/working.xml", afterApply.feedUrl)
    assertNotNull(afterApply.previousWorkingConfigJson)

    assertTrue(repo.restorePreviousSourceConfig(initialSource.id))
    val afterRestore = repo.getSourceById(initialSource.id)!!
    assertEquals("$baseUrl/broken", afterRestore.searchUrlTemplate)
  }

  // 4. Backup & Restore round-trip, validation, transactional REPLACE/MERGE, zero secrets
  @Test
  fun `backup export and transactional restore validate schema exclude secrets and support merge vs replace`() = runBlocking {
    val srcA = sampleSource("s_a", "Source A", "https://a.example")
    val srcB = sampleSource("s_b", "Source B", "https://b.example")
    repo.saveSource(srcA, preservePreviousSnapshot = false)

    val col = repo.createCollection("Action")
    val fav1 = sampleItem("f_1", "Fav One", "s_a", "Source A", infoHash = "8888888888888888888888888888888888888888")
      .copy(collectionId = col.id)
    val fav2 = sampleItem("f_2", "Fav Two", "s_b", "Source B", infoHash = "9999999999999999999999999999999999999999")
    repo.toggleFavorite(fav1)
    repo.recordSearch("ubuntu", 5, TorrentCategory.ALL)

    val prefs = UserPreferences(
      themeMode = ThemeMode.AMOLED,
      accentColor = AccentColorOption.EMERALD,
      displayDensity = DisplayDensity.COMFORTABLE,
      maxConcurrentSources = 6
    )

    val jsonOut = repo.exportBackupJson(
      selection = BackupSelection(includeSources = true, includeFavoritesAndCollections = true, includePreferences = true, includeSearchHistory = true),
      preferences = prefs,
      appVersion = "1.0"
    )

    assertFalse(jsonOut.contains("cf_clearance", ignoreCase = true))
    assertFalse(jsonOut.contains("cookie", ignoreCase = true))

    // Reject unsupported future schema & corrupt JSON
    assertTrue(repo.parseAndValidateBackup("{bad json").isFailure)
    assertTrue(repo.parseAndValidateBackup("""{"schemaVersion":99,"exportedAt":1}""").isFailure)

    val parsed = repo.parseAndValidateBackup(jsonOut).getOrThrow()
    assertEquals(1, parsed.sources.size)
    assertEquals(1, parsed.favorites.size)

    // Mutate DB: alter srcA locally, add srcB and fav2, remove fav1 before testing MERGE vs REPLACE
    repo.saveSource(srcA.copy(name = "Mutated Source A"), preservePreviousSnapshot = false)
    repo.saveSource(srcB, preservePreviousSnapshot = false)
    repo.toggleFavorite(fav1) // removes fav1
    repo.toggleFavorite(fav2) // adds fav2

    // MERGE updates matching IDs (restoring srcA name) while preserving unrelated srcB & fav2
    val mergeSummary = repo.restoreBackup(parsed, BackupSelection(true, true, true, false), RestoreMode.MERGE, prefsRepo)
    assertEquals(1, mergeSummary.sourcesRestored) // srcA upserted/updated
    assertEquals(1, mergeSummary.favoritesRestored) // fav1 upserted
    val mergedSources = repo.observeSources().first()
    assertTrue(mergedSources.map { it.id }.containsAll(listOf("s_a", "s_b")))
    assertEquals("Source A", mergedSources.first { it.id == "s_a" }.name)
    assertEquals(2, repo.observeFavorites().first().size)

    // REPLACE wipes extra rows so DB matches backup exactly
    val replaceSummary = repo.restoreBackup(parsed, BackupSelection(true, true, false, false), RestoreMode.REPLACE, prefsRepo)
    assertEquals(1, replaceSummary.sourcesRestored)
    assertEquals(1, replaceSummary.favoritesRestored)
    assertEquals(listOf("s_a"), repo.observeSources().first().map { it.id })
    assertEquals(listOf("f_1"), repo.observeFavorites().first().map { it.id })
  }

  private fun sampleSource(id: String, name: String, baseUrl: String) = TorrentSource(
    id = id,
    name = name,
    baseUrl = baseUrl,
    searchUrlTemplate = "$baseUrl/search?q={query}",
    sourceType = SourceType.JSON_API
  )

  // 5. Room MIGRATION_1_2 preserves legacy data & sets allowCleartextHttp properly
  @Test
  fun `room migration 1 to 2 preserves existing data without loss and flags legacy http sources`() = runBlocking {
    val dbFile = File(ctx.getDatabasePath("migration_test_v1.db").path)
    dbFile.parentFile?.mkdirs()
    if (dbFile.exists()) dbFile.delete()

    val v1Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    v1Db.execSQL(
      """
      CREATE TABLE torrent_sources (
        id TEXT NOT NULL PRIMARY KEY,
        name TEXT NOT NULL,
        baseUrl TEXT NOT NULL,
        searchUrlTemplate TEXT NOT NULL,
        feedUrl TEXT NOT NULL,
        sourceType TEXT NOT NULL,
        enabled INTEGER NOT NULL,
        isBuiltIn INTEGER NOT NULL,
        htmlSelectorsJson TEXT NOT NULL,
        jsonMappingJson TEXT NOT NULL,
        lastSuccessAt INTEGER,
        lastCheckedAt INTEGER,
        lastError TEXT,
        lastStatusCode INTEGER,
        lastRedirectUrl TEXT,
        requiresCloudflare INTEGER NOT NULL,
        previousWorkingConfigJson TEXT,
        createdAt INTEGER NOT NULL
      )
      """.trimIndent()
    )
    v1Db.execSQL(
      """
      CREATE TABLE favorite_torrents (
        id TEXT NOT NULL PRIMARY KEY,
        title TEXT NOT NULL,
        category TEXT NOT NULL,
        collectionId TEXT,
        releaseYear INTEGER,
        season INTEGER,
        episode INTEGER,
        resolution TEXT,
        videoQuality TEXT,
        encoding TEXT,
        fileSizeText TEXT,
        fileSizeBytes INTEGER,
        seeders INTEGER,
        leechers INTEGER,
        uploadDateText TEXT,
        uploadTimestamp INTEGER,
        uploader TEXT,
        sourceId TEXT NOT NULL,
        sourceName TEXT NOT NULL,
        sourceUrl TEXT,
        magnetLink TEXT,
        torrentFileUrl TEXT,
        infoHash TEXT,
        coverImageUrl TEXT,
        language TEXT,
        subtitles TEXT,
        tagsJson TEXT NOT NULL,
        description TEXT,
        savedAt INTEGER NOT NULL
      )
      """.trimIndent()
    )
    v1Db.execSQL(
      """
      CREATE TABLE favorite_collections (
        id TEXT NOT NULL PRIMARY KEY,
        name TEXT NOT NULL,
        createdAt INTEGER NOT NULL,
        isSystem INTEGER NOT NULL
      )
      """.trimIndent()
    )
    v1Db.execSQL(
      """
      CREATE TABLE search_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        query TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        resultCount INTEGER NOT NULL,
        categoryLabel TEXT NOT NULL
      )
      """.trimIndent()
    )
    v1Db.execSQL(
      """
      CREATE TABLE cached_torrents (
        id TEXT NOT NULL PRIMARY KEY,
        queryKey TEXT NOT NULL,
        title TEXT NOT NULL,
        category TEXT NOT NULL,
        releaseYear INTEGER,
        season INTEGER,
        episode INTEGER,
        resolution TEXT,
        videoQuality TEXT,
        encoding TEXT,
        fileSizeText TEXT,
        fileSizeBytes INTEGER,
        seeders INTEGER,
        leechers INTEGER,
        uploadDateText TEXT,
        uploadTimestamp INTEGER,
        uploader TEXT,
        sourceId TEXT NOT NULL,
        sourceName TEXT NOT NULL,
        sourceUrl TEXT,
        magnetLink TEXT,
        torrentFileUrl TEXT,
        infoHash TEXT,
        coverImageUrl TEXT,
        language TEXT,
        subtitles TEXT,
        tagsJson TEXT NOT NULL,
        description TEXT,
        cachedAt INTEGER NOT NULL
      )
      """.trimIndent()
    )

    v1Db.execSQL(
      """
      INSERT INTO torrent_sources VALUES
      ('http_src','Legacy HTTP','http://legacy.local','http://legacy.local/q={query}','','HTML_SELECTOR',1,0,'{}','{}',NULL,NULL,NULL,NULL,NULL,0,NULL,100),
      ('https_src','Secure HTTPS','https://secure.local','https://secure.local/q={query}','','RSS_ATOM',1,0,'{}','{}',NULL,NULL,NULL,NULL,NULL,0,NULL,200)
      """.trimIndent()
    )
    v1Db.execSQL(
      """
      INSERT INTO favorite_torrents VALUES
      ('fav_v1','Legacy Favorite','MOVIES',NULL,2022,NULL,NULL,'1080p',NULL,NULL,'1.2 GB',1288490188,15,2,NULL,NULL,NULL,'https_src','Secure HTTPS',NULL,NULL,NULL,'cccccccccccccccccccccccccccccccccccccccc',NULL,NULL,NULL,'[]',NULL,500)
      """.trimIndent()
    )
    v1Db.version = 1
    v1Db.close()

    val migrated = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.name)
      .addMigrations(*AppDatabase.ALL_MIGRATIONS)
      .allowMainThreadQueries()
      .build()

    try {
      val httpEntity = migrated.torrentSourceDao().getById("http_src")
      val httpsEntity = migrated.torrentSourceDao().getById("https_src")
      val favEntity = migrated.favoriteDao().getFavoriteById("fav_v1")

      assertNotNull(httpEntity)
      assertNotNull(httpsEntity)
      assertNotNull(favEntity)
      assertTrue(httpEntity!!.allowCleartextHttp)
      assertFalse(httpsEntity!!.allowCleartextHttp)
      assertEquals("[]", favEntity!!.alternateSourcesJson)
      assertEquals("Legacy Favorite", favEntity.title)
    } finally {
      migrated.close()
      dbFile.delete()
    }
  }

  // 6. WebViewCookieJar domain, expiry, HTTPS-secure scoping
  @Test
  fun `cookie jar scopes cookies by domain path expiry and https without exposing token values`() {
    val jar = WebViewCookieJar()
    val httpsAlpha = "https://alpha.example/search".toHttpUrl()
    val httpAlpha = "http://alpha.example/search".toHttpUrl()
    val httpsOther = "https://other.example/search".toHttpUrl()

    val validSecure = Cookie.Builder()
      .name("cf_clearance")
      .value("secret_clearance_token")
      .domain("alpha.example")
      .path("/")
      .expiresAt(System.currentTimeMillis() + 60_000L)
      .secure()
      .build()

    val expiredCookie = Cookie.Builder()
      .name("old_session")
      .value("expired_val")
      .domain("alpha.example")
      .path("/")
      .expiresAt(System.currentTimeMillis() - 10_000L)
      .build()

    jar.saveFromResponse(httpsAlpha, listOf(validSecure, expiredCookie))

    // Valid on HTTPS alpha.example, hidden on plain HTTP and on unrelated domains
    assertEquals(1, jar.loadForRequest(httpsAlpha).size)
    assertEquals(0, jar.loadForRequest(httpAlpha).size)
    assertEquals(0, jar.loadForRequest(httpsOther).size)

    val status = jar.getSessionStatus("https://alpha.example/search")
    assertTrue(status.isHttps)
    assertTrue(status.hasSessionCookies)
    assertTrue(status.hasClearanceCookie)
  }

  // 7. TorrentHttpClient bounded size, cleartext blocking, coroutine cancellation, & Semaphore concurrency limit
  @Test
  fun `http client enforces cleartext block size cap coroutine cancellation and diagnostic semaphore limit`() = runBlocking {
    val activeCount = AtomicInteger(0)
    val maxObserved = AtomicInteger(0)
    val baseUrl = startLocalServer { path ->
      when (path) {
        "/oversize" -> Triple(200, "text/plain", "A".repeat((5 * 1024 * 1024)))
        "/slow" -> {
          Thread.sleep(600)
          Triple(200, "text/plain", "ok")
        }
        "/concurrent" -> {
          val cur = activeCount.incrementAndGet()
          maxObserved.updateAndGet { prev -> maxOf(prev, cur) }
          Thread.sleep(80)
          activeCount.decrementAndGet()
          Triple(200, "application/json", """[{"title":"Item","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]""")
        }
        else -> Triple(200, "text/plain", "hello")
      }
    }

    // Cleartext block check
    val blocked = httpClient.fetch("$baseUrl/hello", allowCleartext = false)
    assertFalse(blocked.isSuccessful)
    assertTrue(blocked.errorMessage?.contains("HTTPS", ignoreCase = true) == true)

    // Oversize 5 MB response blocked by 4 MB streaming limit
    val oversized = httpClient.fetch("$baseUrl/oversize", allowCleartext = true)
    assertFalse(oversized.isSuccessful)
    assertTrue(oversized.errorMessage?.contains("limit", ignoreCase = true) == true)

    // Coroutine cancellation cancels underlying request promptly
    var cancelled = false
    val job = launch(Dispatchers.IO) {
      try {
        httpClient.fetch("$baseUrl/slow", timeoutSeconds = 5, allowCleartext = true)
      } catch (_: CancellationException) {
        cancelled = true
      }
    }
    delay(60)
    job.cancel()
    job.join()
    assertTrue(cancelled)

    // Bounded Semaphore concurrency on runAllSourcesDiagnostic (maxConcurrency = 2 across 6 sources)
    for (i in 1..6) {
      repo.saveSource(
        TorrentSource(
          id = "conc_$i",
          name = "Conc $i",
          baseUrl = baseUrl,
          searchUrlTemplate = "$baseUrl/concurrent",
          sourceType = SourceType.JSON_API,
          allowCleartextHttp = true,
          jsonMapping = JsonMappingConfig(itemsPath = "", titleField = "title", infoHashField = "hash")
        ),
        preservePreviousSnapshot = false
      )
    }
    val diagResults = repo.runAllSourcesDiagnostic(timeoutSeconds = 5, maxConcurrency = 2, preferHttps = false)
    assertEquals(6, diagResults.size)
    assertTrue(diagResults.all { it.second is SourceProgressState.Success })
    assertTrue("Expected max concurrent <= 2, saw ${maxObserved.get()}", maxObserved.get() <= 2)
  }

  // 8. Global preferHttps=false must NOT unlock HTTP, and HTTPS->HTTP downgrade redirects are blocked BEFORE connecting
  @Test
  fun `global preferHttps false does not unlock http sources and downgrade redirect never connects to http target`() = runBlocking {
    val httpTargetHits = AtomicInteger(0)
    val httpBase = startLocalServer { _ ->
      httpTargetHits.incrementAndGet()
      Triple(200, "application/json", """[{"title":"Should Never Load","hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]""")
    }

    // Intercept https://secure.test/start and emit a 302 Location -> http://127.0.0.1:<port>/downgrade
    val redirectClient = TorrentHttpClient(
      WebViewCookieJar(),
      clientCustomizer = { builder ->
        builder.addInterceptor { chain ->
          val req = chain.request()
          if (req.url.host == "secure.test") {
            Response.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(302)
              .message("Found")
              .header("Location", "$httpBase/downgrade")
              .body("".toResponseBody("text/plain".toMediaType()))
              .build()
          } else {
            chain.proceed(req)
          }
        }
      }
    )

    val downgradeRes = redirectClient.fetch("https://secure.test/start", allowCleartext = false)
    assertFalse(downgradeRes.isSuccessful)
    assertTrue(downgradeRes.errorMessage?.contains("downgrade", ignoreCase = true) == true)
    assertEquals(0, httpTargetHits.get())

    // Even when global preferHttps preference is FALSE, a source with allowCleartextHttp = false MUST stay blocked
    val blockedSrc = TorrentSource(
      id = "unapproved_http",
      name = "Unapproved HTTP",
      baseUrl = httpBase,
      searchUrlTemplate = "$httpBase/search?q={query}",
      sourceType = SourceType.JSON_API,
      allowCleartextHttp = false,
      jsonMapping = JsonMappingConfig(itemsPath = "", titleField = "title", infoHashField = "hash")
    )
    val testRes = repo.testSingleSource(blockedSrc, timeoutSeconds = 5, preferHttps = false)
    assertTrue(testRes is SourceProgressState.Error)
    assertEquals(0, httpTargetHits.get())
  }

  // 9. Backup validation rejects duplicate IDs, bad URLs, broken collection references, and resets HTTP permission on import
  @Test
  fun `backup validation rejects malformed entries and resets cleartext permission on import`() = runBlocking {
    val dupIdJson = """
      {
        "schemaVersion": 1,
        "exportedAt": 1700000000000,
        "sources": [
          {"id":"s1","name":"One","baseUrl":"https://one.example","searchUrlTemplate":"https://one.example/q={query}","sourceType":"RSS_ATOM"},
          {"id":"s1","name":"Two","baseUrl":"https://two.example","searchUrlTemplate":"https://two.example/q={query}","sourceType":"RSS_ATOM"}
        ]
      }
    """.trimIndent()
    assertTrue(repo.parseAndValidateBackup(dupIdJson).isFailure)

    val badSchemeJson = """
      {
        "schemaVersion": 1,
        "exportedAt": 1700000000000,
        "sources": [
          {"id":"s1","name":"Bad","baseUrl":"javascript:alert(1)","searchUrlTemplate":"","sourceType":"RSS_ATOM"}
        ]
      }
    """.trimIndent()
    assertTrue(repo.parseAndValidateBackup(badSchemeJson).isFailure)

    val brokenCollectionJson = """
      {
        "schemaVersion": 1,
        "exportedAt": 1700000000000,
        "collections": [],
        "favorites": [
          {"id":"f1","title":"Movie","category":"MOVIES","collectionId":"missing_col","sourceId":"s1","sourceName":"S1"}
        ]
      }
    """.trimIndent()
    assertTrue(repo.parseAndValidateBackup(brokenCollectionJson).isFailure)

    val httpBackupJson = """
      {
        "schemaVersion": 1,
        "exportedAt": 1700000000000,
        "sources": [
          {"id":"http_imp","name":"Imported HTTP","baseUrl":"http://legacy.example","searchUrlTemplate":"http://legacy.example/q={query}","sourceType":"RSS_ATOM","allowCleartextHttp":true}
        ]
      }
    """.trimIndent()
    val parsedHttp = repo.parseAndValidateBackup(httpBackupJson).getOrThrow()
    repo.restoreBackup(parsedHttp, BackupSelection(true, false, false, false), RestoreMode.REPLACE, prefsRepo)
    val importedSrc = repo.getSourceById("http_imp")!!
    assertFalse("Imported HTTP source must require fresh confirmation", importedSrc.allowCleartextHttp)
  }

  // 10. Pagination handles relative URLs & continues through consecutive duplicate-only pages
  @Test
  fun `pagination resolves relative next links and advances across consecutive duplicate pages`() = runBlocking {
    val dupHash = "abababababababababababababababababababab"
    val newHash = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
    val baseUrl = startLocalServer { path ->
      when (path) {
        "/s/search?q=ubuntu" -> Triple(
          200,
          "application/json",
          """{"next":"?q=ubuntu&page=2","results":[{"title":"Ubuntu 24.04","hash":"$dupHash","seeds":50}]}"""
        )
        "/s/search?q=ubuntu&page=2" -> Triple(
          200,
          "application/json",
          """{"next":"page/3","results":[{"title":"Ubuntu 24.04","hash":"$dupHash","seeds":50}]}"""
        )
        "/s/page/3" -> Triple(
          200,
          "application/json",
          """{"next":null,"results":[{"title":"Ubuntu 24.10","hash":"$newHash","seeds":90}]}"""
        )
        else -> Triple(404, "text/plain", "Not found: $path")
      }
    }

    val adapter = UnifiedSourceAdapter(httpClient)
    val src = TorrentSource(
      id = "rel_json",
      name = "RelativeJSON",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/s/search?q={query}",
      sourceType = SourceType.JSON_API,
      allowCleartextHttp = true,
      jsonMapping = JsonMappingConfig(itemsPath = "results", titleField = "title", infoHashField = "hash", seedersField = "seeds")
    )
    val p1 = adapter.fetchPage(src, "ubuntu", 1, 5, allowCleartext = true)
    assertEquals("$baseUrl/s/search?q=ubuntu&page=2", p1.nextPageUrl)
    val p2 = adapter.fetchPage(src, "ubuntu", 2, 5, overrideUrl = p1.nextPageUrl, allowCleartext = true)
    assertEquals("$baseUrl/s/page/3", p2.nextPageUrl)

    prefsRepo.updateAutoRefreshOnLaunch(false)
    repo.ensureSeeded()
    db.torrentSourceDao().getAll().forEach { db.torrentSourceDao().upsert(it.copy(enabled = false)) }
    repo.saveSource(src, preservePreviousSnapshot = false)

    val vm = SearchViewModel(repo, prefsRepo)
    val job = launch { vm.uiState.collect {} }
    try {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      vm.submitSearch("ubuntu")
      repeat(50) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        if (!vm.uiState.value.isLoading && vm.uiState.value.results.isNotEmpty()) return@repeat
        delay(30)
      }
      assertEquals(1, vm.uiState.value.results.size)
      assertTrue(vm.uiState.value.hasMorePages)

      // Page 2 only has duplicates of page 1, so Cursor still preserves nextPageUrl ("page/3")
      vm.loadNextPage()
      repeat(80) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val st = vm.uiState.value
        if (!st.isLoadingMore && st.currentPage >= 2) return@repeat
        delay(30)
      }
      assertEquals(1, vm.uiState.value.results.size)
      assertEquals(2, vm.uiState.value.currentPage)
      assertTrue("Should still allow loading page 3 after 1 duplicate-only page", vm.uiState.value.hasMorePages)

      // Page 3 adds the new item and reaches end
      vm.loadNextPage()
      repeat(80) {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val st = vm.uiState.value
        if (!st.isLoadingMore && st.currentPage >= 3 && st.results.size >= 2) return@repeat
        delay(30)
      }
      assertEquals(2, vm.uiState.value.results.size)
      assertFalse(vm.uiState.value.hasMorePages)
    } finally {
      job.cancel()
    }
  }

  // 11. Hashless unknown-size items don't collapse, ONLINE beats UNKNOWN, and preferred source offer persists
  @Test
  fun `unknown size does not merge hashless items online outranks unknown and preferred offer persists`() = runBlocking {
    val noSize1 = sampleItem("ns1", "Linux Distro 2024 1080p", "s1", "S1", infoHash = null, sizeBytes = null, magnet = "magnet:?dn=a")
    val noSize2 = sampleItem("ns2", "Linux Distro 2024 1080p", "s2", "S2", infoHash = null, sizeBytes = null, magnet = "magnet:?dn=b")
    val groupedNoSize = repo.deduplicateAndFilter(listOf(noSize1, noSize2), emptySet(), SearchFilterState())
    assertEquals(2, groupedNoSize.size)

    val sharedHash = "efefefefefefefefefefefefefefefefefefefef"
    val unknownHealthyOffer = sampleItem("u1", "Ubuntu ISO 2024 1080p", "s_unk", "UnknownSrc", infoHash = sharedHash, seeders = 500)
    val verifiedOnlineOffer = sampleItem("u2", "Ubuntu ISO 2024 1080p", "s_on", "OnlineSrc", infoHash = sharedHash, seeders = 50)
    val merged = repo.deduplicateAndFilter(
      listOf(unknownHealthyOffer, verifiedOnlineOffer),
      emptySet(),
      SearchFilterState(),
      mapOf("s_unk" to SourceHealth.UNKNOWN, "s_on" to SourceHealth.ONLINE)
    )
    assertEquals(1, merged.size)
    assertEquals("OnlineSrc", merged.first().sourceName)

    // Save favorite and update preferred source offer
    val fav = merged.first()
    assertTrue(repo.toggleFavorite(fav))
    val altOffer = fav.allSourceOffers.first { it.sourceId == "s_unk" }
    repo.updateFavoritePreferredOffer(fav.id, altOffer)
    val reloadedFav = repo.observeFavorites().first().first()
    assertEquals("UnknownSrc", reloadedFav.sourceName)
    assertEquals("s_unk", reloadedFav.allSourceOffers.first().sourceId)
  }

  // 12. WebViewCookieJar hostOnly isolation, path scoping, and clearAllCookies
  @Test
  fun `cookie jar enforces hostOnly subdomain isolation path scoping and clearAllCookies`() {
    val jar = WebViewCookieJar()
    val apexUrl = "https://example.org/search".toHttpUrl()
    val subUrl = "https://sub.example.org/search".toHttpUrl()
    val otherPathUrl = "https://example.org/other".toHttpUrl()

    val hostOnlyPathScoped = Cookie.Builder()
      .name("__cf_bm")
      .value("tok123")
      .hostOnlyDomain("example.org")
      .path("/search")
      .expiresAt(System.currentTimeMillis() + 60_000L)
      .secure()
      .build()

    jar.saveFromResponse(apexUrl, listOf(hostOnlyPathScoped))

    assertEquals(1, jar.loadForRequest(apexUrl).size)
    assertEquals(0, jar.loadForRequest(subUrl).size)
    assertEquals(0, jar.loadForRequest(otherPathUrl).size)

    jar.clearAllCookies()
    assertEquals(0, jar.loadForRequest(apexUrl).size)
  }

  // 13. SourceRepairEngine classifies HTTP 200 with 0 items as PARSED / RECOGNIZED (never verified) & enforces allowCleartextHttp
  @Test
  fun `repair engine never verifies zero-item http 200 and reports verification stage plus safe diagnostics`() = runBlocking {
    val baseUrl = startLocalServer { path ->
      when (path) {
        "/parsed-zero" -> Triple(200, "application/json", """{"results":[]}""")
        "/missing-selector" -> Triple(200, "text/html", "<html><body><div>Empty shell</div></body></html>")
        else -> Triple(200, "text/html", "<html><body>No feeds</body></html>")
      }
    }

    val adapter = UnifiedSourceAdapter(httpClient)
    val engine = SourceRepairEngine(httpClient, adapter)
    val parsedZeroJsonSrc = TorrentSource(
      id = "zero_parse",
      name = "ZeroParse",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/parsed-zero",
      sourceType = SourceType.JSON_API,
      allowCleartextHttp = true,
      jsonMapping = JsonMappingConfig(itemsPath = "results", titleField = "title", infoHashField = "hash")
    )

    val report = engine.diagnoseSource(parsedZeroJsonSrc, timeoutSeconds = 5)
    assertEquals(RepairVerificationStage.PARSED, report.verificationStage)
    assertTrue(report.parsingIssueDetected)
    assertEquals(0, report.extractedCount)

    val candidateRes = engine.testCandidateUrl("$baseUrl/parsed-zero", parsedZeroJsonSrc, timeoutSeconds = 5)
    assertTrue("Expected Parsed Not Usable, got: $candidateRes", candidateRes.contains("Parsed · Not Usable", ignoreCase = true))

    val htmlMissingSrc = TorrentSource(
      id = "missing_sel",
      name = "MissingSelector",
      baseUrl = baseUrl,
      searchUrlTemplate = "$baseUrl/missing-selector",
      sourceType = SourceType.HTML_SELECTOR,
      allowCleartextHttp = true,
      htmlSelectors = HtmlSelectorConfig(itemContainer = "tr.missing", title = "a")
    )
    val recognizedRes = engine.testCandidateUrl("$baseUrl/missing-selector", htmlMissingSrc, timeoutSeconds = 5)
    assertTrue("Expected Recognized Parse Failed, got: $recognizedRes", recognizedRes.contains("Recognized · Parse Failed", ignoreCase = true))

    // Without allowCleartextHttp, testing HTTP candidate is blocked
    val blockedRes = engine.testCandidateUrl("$baseUrl/parsed-zero", parsedZeroJsonSrc.copy(allowCleartextHttp = false), timeoutSeconds = 5)
    assertTrue("Expected Unreachable/blocked message, got: $blockedRes", blockedRes.contains("Unreachable", ignoreCase = true))

    // Diagnostic export contains source summary without any cookies
    repo.saveSource(parsedZeroJsonSrc, preservePreviousSnapshot = false)
    val diagText = repo.buildDiagnosticReportText("1.0")
    assertTrue(diagText.contains("ZeroParse"))
    assertTrue(diagText.contains("Cookies", ignoreCase = true))
    assertFalse(diagText.contains("tok123"))
  }
}
