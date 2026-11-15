package com.example.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "torrent_sources")
data class TorrentSourceEntity(
  @PrimaryKey val id: String,
  val name: String,
  val baseUrl: String,
  val searchUrlTemplate: String,
  val feedUrl: String,
  val sourceType: String,
  val enabled: Boolean,
  val isBuiltIn: Boolean,
  val allowCleartextHttp: Boolean = false,
  val htmlSelectorsJson: String,
  val jsonMappingJson: String,
  val lastSuccessAt: Long?,
  val lastCheckedAt: Long?,
  val lastError: String?,
  val lastStatusCode: Int?,
  val lastRedirectUrl: String?,
  val requiresCloudflare: Boolean,
  val previousWorkingConfigJson: String?,
  val createdAt: Long
)

@Entity(tableName = "favorite_torrents")
data class FavoriteTorrentEntity(
  @PrimaryKey val id: String,
  val title: String,
  val category: String,
  val collectionId: String?,
  val releaseYear: Int?,
  val season: Int?,
  val episode: Int?,
  val resolution: String?,
  val videoQuality: String?,
  val encoding: String?,
  val fileSizeText: String?,
  val fileSizeBytes: Long?,
  val seeders: Int?,
  val leechers: Int?,
  val uploadDateText: String?,
  val uploadTimestamp: Long?,
  val uploader: String?,
  val sourceId: String,
  val sourceName: String,
  val sourceUrl: String?,
  val magnetLink: String?,
  val torrentFileUrl: String?,
  val infoHash: String?,
  val coverImageUrl: String?,
  val language: String?,
  val subtitles: String?,
  val tagsJson: String,
  val description: String?,
  val savedAt: Long,
  val alternateSourcesJson: String = "[]"
)

@Entity(tableName = "favorite_collections")
data class FavoriteCollectionEntity(
  @PrimaryKey val id: String,
  val name: String,
  val createdAt: Long,
  val isSystem: Boolean
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val query: String,
  val timestamp: Long,
  val resultCount: Int,
  val categoryLabel: String
)

@Entity(tableName = "cached_torrents")
data class CachedTorrentEntity(
  @PrimaryKey val id: String,
  val queryKey: String,
  val title: String,
  val category: String,
  val releaseYear: Int?,
  val season: Int?,
  val episode: Int?,
  val resolution: String?,
  val videoQuality: String?,
  val encoding: String?,
  val fileSizeText: String?,
  val fileSizeBytes: Long?,
  val seeders: Int?,
  val leechers: Int?,
  val uploadDateText: String?,
  val uploadTimestamp: Long?,
  val uploader: String?,
  val sourceId: String,
  val sourceName: String,
  val sourceUrl: String?,
  val magnetLink: String?,
  val torrentFileUrl: String?,
  val infoHash: String?,
  val coverImageUrl: String?,
  val language: String?,
  val subtitles: String?,
  val tagsJson: String,
  val description: String?,
  val cachedAt: Long,
  val alternateSourcesJson: String = "[]"
)
