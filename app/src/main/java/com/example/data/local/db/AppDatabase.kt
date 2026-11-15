package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.model.HtmlSelectorConfig
import com.example.domain.model.JsonMappingConfig
import com.example.domain.model.SourceType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Database(
  entities = [
    TorrentSourceEntity::class,
    FavoriteTorrentEntity::class,
    FavoriteCollectionEntity::class,
    SearchHistoryEntity::class,
    CachedTorrentEntity::class
  ],
  version = 2,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun torrentSourceDao(): TorrentSourceDao
  abstract fun favoriteDao(): FavoriteDao
  abstract fun searchHistoryDao(): SearchHistoryDao
  abstract fun cachedTorrentDao(): CachedTorrentDao

  companion object {
    const val DB_NAME = "torrent_tracker.db"

    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE torrent_sources ADD COLUMN allowCleartextHttp INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
          """
          UPDATE torrent_sources
          SET allowCleartextHttp = 1
          WHERE LOWER(baseUrl) LIKE 'http://%'
             OR LOWER(searchUrlTemplate) LIKE 'http://%'
             OR LOWER(feedUrl) LIKE 'http://%'
          """.trimIndent()
        )
        db.execSQL("ALTER TABLE favorite_torrents ADD COLUMN alternateSourcesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE cached_torrents ADD COLUMN alternateSourcesJson TEXT NOT NULL DEFAULT '[]'")
      }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

    fun build(context: Context): AppDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        DB_NAME
      )
        .addMigrations(*ALL_MIGRATIONS)
        .build()
    }
  }
}

object DefaultSourcesSeeder {
  private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  suspend fun seedIfEmpty(sourceDao: TorrentSourceDao) {
    // Always remove legacy Internet Archive built-ins if present from earlier installs
    sourceDao.deleteArchiveSources()

    val defaultHtml = json.encodeToString(HtmlSelectorConfig())
    val defaultJson = json.encodeToString(JsonMappingConfig())

    val tpbJson = json.encodeToString(
      JsonMappingConfig(
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

    val ytsJson = json.encodeToString(
      JsonMappingConfig(
        itemsPath = "data.movies",
        titleField = "title_long",
        identifierField = "id",
        imageField = "medium_cover_image",
        dateField = "date_uploaded_unix",
        categoryField = "genres",
        yearField = "year",
        languageField = "language",
        descriptionField = "synopsis"
      )
    )

    val eztvHtml = json.encodeToString(
      HtmlSelectorConfig(
        itemContainer = "tr.forum_header_border",
        title = "a.epinfo",
        detailsLink = "a.epinfo",
        magnetLink = "a.magnet",
        torrentFileLink = "a.download_1, a[href*=.torrent]",
        fileSize = "td:nth-child(4)",
        seeders = "td:nth-child(6)",
        uploadDate = "td:nth-child(5)"
      )
    )

    val x1337Html = json.encodeToString(
      HtmlSelectorConfig(
        itemContainer = "table.table-list tbody tr",
        title = "td.name a:nth-of-type(2)",
        detailsLink = "td.name a:nth-of-type(2)",
        fileSize = "td.size",
        seeders = "td.seeds",
        leechers = "td.leeches",
        uploadDate = "td.coll-date",
        uploader = "td.uploader a"
      )
    )

    val now = System.currentTimeMillis()
    val builtIns = listOf(
      TorrentSourceEntity(
        id = "builtin_tpb_apibay",
        name = "The Pirate Bay",
        baseUrl = "https://thepiratebay.org",
        searchUrlTemplate = "https://apibay.org/q.php?q={query}",
        feedUrl = "https://apibay.org/precompiled/data_top100_all.json",
        sourceType = SourceType.JSON_API.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = tpbJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now
      ),
      TorrentSourceEntity(
        id = "builtin_yts_api",
        name = "YTS Movies",
        baseUrl = "https://yts.mx",
        searchUrlTemplate = "https://yts.mx/api/v2/list_movies.json?query_term={query}&page={page}&limit=25&sort_by=seeds",
        feedUrl = "https://yts.mx/api/v2/list_movies.json?sort_by=download_count&limit=25&page={page}",
        sourceType = SourceType.JSON_API.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = ytsJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 1
      ),
      TorrentSourceEntity(
        id = "builtin_nyaa_rss",
        name = "Nyaa Anime",
        baseUrl = "https://nyaa.si",
        searchUrlTemplate = "https://nyaa.si/?page=rss&q={query}",
        feedUrl = "https://nyaa.si/?page=rss",
        sourceType = SourceType.RSS_ATOM.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 2
      ),
      TorrentSourceEntity(
        id = "builtin_eztv_html",
        name = "EZTV Shows",
        baseUrl = "https://eztvx.to",
        searchUrlTemplate = "https://eztvx.to/search/{query}",
        feedUrl = "https://eztvx.to/",
        sourceType = SourceType.HTML_SELECTOR.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = eztvHtml,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 3
      ),
      TorrentSourceEntity(
        id = "builtin_1337x",
        name = "1337x",
        baseUrl = "https://1337x.to",
        searchUrlTemplate = "https://1337x.to/search/{query}/{page}/",
        feedUrl = "https://1337x.to/trending",
        sourceType = SourceType.HTML_SELECTOR.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = x1337Html,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 4
      ),
      TorrentSourceEntity(
        id = "builtin_academic_torrents",
        name = "Academic Torrents RSS",
        baseUrl = "https://academictorrents.com",
        searchUrlTemplate = "https://academictorrents.com/collection.rss",
        feedUrl = "https://academictorrents.com/collection.rss",
        sourceType = SourceType.RSS_ATOM.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 5
      ),
      TorrentSourceEntity(
        id = "builtin_fosstorrents_rss",
        name = "FOSS Torrents RSS",
        baseUrl = "https://fosstorrents.com",
        searchUrlTemplate = "https://fosstorrents.com/feed/",
        feedUrl = "https://fosstorrents.com/feed/",
        sourceType = SourceType.RSS_ATOM.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 6
      ),
      TorrentSourceEntity(
        id = "builtin_linuxtracker_rss",
        name = "LinuxTracker RSS",
        baseUrl = "https://linuxtracker.org",
        searchUrlTemplate = "https://linuxtracker.org/rss.php",
        feedUrl = "https://linuxtracker.org/rss.php",
        sourceType = SourceType.RSS_ATOM.name,
        enabled = true,
        isBuiltIn = true,
        htmlSelectorsJson = defaultHtml,
        jsonMappingJson = defaultJson,
        lastSuccessAt = null,
        lastCheckedAt = null,
        lastError = null,
        lastStatusCode = null,
        lastRedirectUrl = null,
        requiresCloudflare = false,
        previousWorkingConfigJson = null,
        createdAt = now + 7
      )
    )
    sourceDao.insertAllIgnore(builtIns)
  }
}
