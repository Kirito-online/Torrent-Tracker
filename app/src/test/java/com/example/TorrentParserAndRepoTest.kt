package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.AppDatabase
import com.example.data.local.db.DefaultSourcesSeeder
import com.example.data.network.TorrentHttpClient
import com.example.data.parser.TorrentMetadataParser
import com.example.data.parser.UnifiedSourceAdapter
import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TorrentParserAndRepoTest {

  private lateinit var db: AppDatabase
  private lateinit var adapter: UnifiedSourceAdapter

  @Before
  fun setup() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    adapter = UnifiedSourceAdapter(TorrentHttpClient())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `metadata parser extracts season episode resolution codec and infohash`() {
    val title = "Big.Buck.Bunny.2008.S01E02.1080p.BluRay.x265.English.Subbed"
    val meta = TorrentMetadataParser.extractTitleMetadata(title)
    assertEquals(2008, meta.releaseYear)
    assertEquals(1, meta.season)
    assertEquals(2, meta.episode)
    assertEquals("1080p", meta.resolution)
    assertTrue(meta.videoQuality?.contains("BluRay", ignoreCase = true) == true)
    assertTrue(meta.encoding?.contains("x265", ignoreCase = true) == true)
    assertNotNull(meta.language)
    assertNotNull(meta.subtitles)

    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Test"
    assertEquals("0123456789abcdef0123456789abcdef01234567", TorrentMetadataParser.extractInfoHash(magnet))
    assertEquals(2_147_483_648L, TorrentMetadataParser.parseFileSizeBytes("2.0 GB"))
  }

  @Test
  fun `rss adapter parses nyaa rss items with nyaa namespace tags`() {
    val rss = """
      <rss version="2.0" xmlns:nyaa="https://nyaa.si/xmlns/nyaa">
        <channel>
          <title>Nyaa Feed</title>
          <item>
            <title>[SubsPlease] Frieren - 28 (1080p) [ABCDEF12].mkv</title>
            <link>https://nyaa.si/download/12345.torrent</link>
            <guid isPermaLink="true">https://nyaa.si/view/12345</guid>
            <pubDate>Mon, 10 Nov 2026 12:00:00 +0000</pubDate>
            <nyaa:seeders>840</nyaa:seeders>
            <nyaa:leechers>25</nyaa:leechers>
            <nyaa:size>1.4 GiB</nyaa:size>
            <nyaa:infoHash>aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</nyaa:infoHash>
            <nyaa:category>Anime - English-translated</nyaa:category>
          </item>
        </channel>
      </rss>
    """.trimIndent()
    val src = TorrentSource("s_nyaa", "Nyaa Anime", "https://nyaa.si", "", "https://nyaa.si/?page=rss", SourceType.RSS_ATOM)
    val outcome = adapter.parseRssOrAtom(src, rss, "frieren")
    assertEquals(null, outcome.parseError)
    assertEquals(1, outcome.items.size)
    val item = outcome.items.first()
    assertEquals(840, item.seeders)
    assertEquals(25, item.leechers)
    assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", item.infoHash)
    assertEquals(TorrentCategory.ANIME, item.category)
    assertEquals("https://nyaa.si/view/12345", item.sourceUrl)
    assertEquals("https://nyaa.si/download/12345.torrent", item.torrentFileUrl)
    assertTrue(item.magnetLink?.startsWith("magnet:?xt=urn:btih:") == true)
  }

  @Test
  fun `json adapter parses tpb root array and yts nested torrents`() {
    val tpbJson = """
      [
        {
          "id": "778899",
          "name": "Big Buck Bunny 2008 1080p BluRay",
          "info_hash": "0123456789ABCDEF0123456789ABCDEF01234567",
          "leechers": "14",
          "seeders": "320",
          "size": "2147483648",
          "username": "openfilm",
          "added": "1730000000",
          "category": "201"
        }
      ]
    """.trimIndent()
    val tpbSource = TorrentSource(
      id = "s_tpb",
      name = "The Pirate Bay",
      baseUrl = "https://thepiratebay.org",
      searchUrlTemplate = "https://apibay.org/q.php?q={query}",
      sourceType = SourceType.JSON_API,
      jsonMapping = JsonMappingConfig(
        itemsPath = "",
        titleField = "name",
        identifierField = "id",
        detailsUrlTemplate = "https://thepiratebay.org/description.php?id={id}",
        infoHashField = "info_hash",
        sizeField = "size",
        seedersField = "seeders",
        leechersField = "leechers",
        dateField = "added",
        uploaderField = "username",
        categoryField = "category"
      )
    )
    val tpbOutcome = adapter.parseJsonApi(tpbSource, tpbJson, "bunny", 1)
    assertEquals(null, tpbOutcome.parseError)
    assertEquals(1, tpbOutcome.items.size)
    val tpbItem = tpbOutcome.items.first()
    assertEquals(TorrentCategory.MOVIES, tpbItem.category)
    assertEquals(320, tpbItem.seeders)
    assertEquals("https://thepiratebay.org/description.php?id=778899", tpbItem.sourceUrl)

    val ytsJson = """
      {
        "status": "ok",
        "data": {
          "movies": [
            {
              "id": 42,
              "url": "https://yts.mx/movies/sintel-2010",
              "title_long": "Sintel (2010)",
              "year": 2010,
              "language": "en",
              "medium_cover_image": "https://yts.mx/assets/sintel.jpg",
              "date_uploaded_unix": 1730000000,
              "torrents": [
                {
                  "url": "https://yts.mx/torrent/download/HASH1",
                  "hash": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                  "quality": "1080p",
                  "type": "bluray",
                  "seeds": 190,
                  "peers": 12,
                  "size_bytes": 1610612736
                }
              ]
            }
          ]
        }
      }
    """.trimIndent()
    val ytsSource = TorrentSource(
      id = "s_yts",
      name = "YTS Movies",
      baseUrl = "https://yts.mx",
      searchUrlTemplate = "https://yts.mx/api/v2/list_movies.json?query_term={query}",
      sourceType = SourceType.JSON_API,
      jsonMapping = JsonMappingConfig(
        itemsPath = "data.movies",
        titleField = "title_long",
        identifierField = "id",
        imageField = "medium_cover_image",
        dateField = "date_uploaded_unix",
        categoryField = "genres",
        yearField = "year",
        languageField = "language"
      )
    )
    val ytsOutcome = adapter.parseJsonApi(ytsSource, ytsJson, "sintel", 1)
    assertEquals(null, ytsOutcome.parseError)
    assertEquals(1, ytsOutcome.items.size)
    val ytsItem = ytsOutcome.items.first()
    assertEquals(2010, ytsItem.releaseYear)
    assertEquals("1080p", ytsItem.resolution)
    assertEquals(TorrentCategory.MOVIES, ytsItem.category)
    assertEquals(190, ytsItem.seeders)
    assertEquals("https://yts.mx/assets/sintel.jpg", ytsItem.coverImageUrl)
    assertEquals("https://yts.mx/movies/sintel-2010", ytsItem.sourceUrl)
  }

  @Test
  fun `html adapter parses custom css selectors`() {
    val html = """
      <html><body>
        <table>
          <tr class="t-row">
            <td><a class="t-title" href="/view/42">Cosmos S01E01 2160p x265</a></td>
            <td><a href="magnet:?xt=urn:btih:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb">Magnet</a></td>
            <td class="t-size">3.5 GB</td>
            <td class="t-seeds">88</td>
            <td class="t-leech">12</td>
          </tr>
        </table>
      </body></html>
    """.trimIndent()
    val src = TorrentSource(
      id = "s3",
      name = "CustomHTML",
      baseUrl = "https://example.org",
      searchUrlTemplate = "",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = HtmlSelectorConfig(
        itemContainer = "tr.t-row",
        title = "a.t-title",
        detailsLink = "a.t-title",
        magnetLink = "a[href^=magnet:]",
        fileSize = "td.t-size",
        seeders = "td.t-seeds",
        leechers = "td.t-leech"
      )
    )
    val outcome = adapter.parseHtmlWithSelectors(src, html, "https://example.org", "cosmos")
    assertEquals(null, outcome.parseError)
    assertEquals(1, outcome.items.size)
    val item = outcome.items.first()
    assertEquals(88, item.seeders)
    assertEquals(12, item.leechers)
    assertEquals("2160p", item.resolution)
    assertEquals("S01E01", item.seasonEpisodeLabel)
  }

  @Test
  fun `default sources seeder removes internet archive and populates famous torrent sites`() = runTest {
    db.torrentSourceDao().upsert(
      com.example.data.local.db.TorrentSourceEntity(
        id = "builtin_archive_media",
        name = "Internet Archive Media",
        baseUrl = "https://archive.org",
        searchUrlTemplate = "",
        feedUrl = "",
        sourceType = SourceType.JSON_API.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = "{}",
        jsonMappingJson = "{}",
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
    DefaultSourcesSeeder.seedIfEmpty(db.torrentSourceDao())
    val all = db.torrentSourceDao().observeAll().first()
    assertTrue(all.none { it.baseUrl.contains("archive.org", ignoreCase = true) || it.name.contains("Archive", ignoreCase = true) })
    assertTrue(all.any { it.id == "builtin_tpb_apibay" })
    assertTrue(all.any { it.id == "builtin_yts_api" })
    assertTrue(all.any { it.id == "builtin_nyaa_rss" })
    assertTrue(all.any { it.id == "builtin_1337x" })
  }

  @Test
  fun `source directory search includes famous torrent sites and excludes internet archive`() {
    assertTrue(com.example.data.parser.SourceDirectory.catalog.none { it.baseUrl.contains("archive.org", ignoreCase = true) })
    assertTrue(com.example.data.parser.SourceDirectory.search("tpb").any { it.name.contains("Pirate Bay", ignoreCase = true) })
    assertTrue(com.example.data.parser.SourceDirectory.search("1337x").any { it.name == "1337x" })
    assertTrue(com.example.data.parser.SourceDirectory.search("nyaa").any { it.name.contains("Nyaa", ignoreCase = true) })
    assertTrue(com.example.data.parser.SourceDirectory.search("fitgirl").any { it.name.contains("FitGirl", ignoreCase = true) })

    val debianMatches = com.example.data.parser.SourceDirectory.search("cdimage.debian.org")
    assertEquals(1, debianMatches.size)
    assertEquals(SourceType.HTML_SELECTOR, debianMatches.first().sourceType)
  }

  @Test
  fun `html structure analyzer detects card grid containers and fallback alt titles`() {
    val html = """
      <html><body>
        <div class="movies-grid">
          <div class="movie-card">
            <a href="/movie/101"><img src="/posters/101.jpg" alt="The Matrix (1999) 1080p" /></a>
          </div>
          <div class="movie-card">
            <a href="/movie/102"><img src="/posters/102.jpg" alt="Inception (2010) 2160p" /></a>
          </div>
          <div class="movie-card">
            <a href="/movie/103"><img src="/posters/103.jpg" alt="Interstellar (2014) 1080p" /></a>
          </div>
        </div>
      </body></html>
    """.trimIndent()

    val analysis = adapter.analyzeHtmlStructure(
      html = html,
      finalUrl = "https://watchsomuch.example",
      sourceId = "wsm",
      sourceName = "WatchSoMuch",
      baseUrl = "https://watchsomuch.example"
    )
    assertEquals(false, analysis.isLikelySpa)
    assertNotNull(analysis.suggestedConfig)
    assertEquals("div.movie-card", analysis.suggestedConfig?.itemContainer)
    assertEquals(3, analysis.extractedCount)

    val src = TorrentSource(
      id = "wsm",
      name = "WatchSoMuch",
      baseUrl = "https://watchsomuch.example",
      searchUrlTemplate = "https://watchsomuch.example",
      sourceType = SourceType.HTML_SELECTOR,
      htmlSelectors = analysis.suggestedConfig!!
    )
    val outcome = adapter.parseHtmlWithSelectors(src, html, "https://watchsomuch.example", "")
    assertEquals(null, outcome.parseError)
    assertEquals(3, outcome.items.size)
    assertEquals(1999, outcome.items.first().releaseYear)
    assertEquals("1080p", outcome.items.first().resolution)
    assertEquals("https://watchsomuch.example/posters/101.jpg", outcome.items.first().coverImageUrl)
  }

  @Test
  fun `html structure analyzer identifies javascript SPA shells`() {
    val spaHtml = """
      <!doctype html>
      <html>
        <head><title>SPA Site</title></head>
        <body>
          <div id="root"></div>
          <script src="/static/js/vendor.js"></script>
          <script src="/static/js/main.js"></script>
        </body>
      </html>
    """.trimIndent()

    val analysis = adapter.analyzeHtmlStructure(
      html = spaHtml,
      finalUrl = "https://spa.example",
      sourceId = "spa",
      sourceName = "SPA Site",
      baseUrl = "https://spa.example"
    )
    assertTrue(analysis.isLikelySpa)
    assertEquals(0, analysis.extractedCount)
  }
}
