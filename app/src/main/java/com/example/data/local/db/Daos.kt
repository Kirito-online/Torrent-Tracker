package com.example.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TorrentSourceDao {
  @Query("SELECT * FROM torrent_sources ORDER BY isBuiltIn DESC, createdAt ASC")
  fun observeAll(): Flow<List<TorrentSourceEntity>>

  @Query("SELECT * FROM torrent_sources ORDER BY isBuiltIn DESC, createdAt ASC")
  suspend fun getAll(): List<TorrentSourceEntity>

  @Query("SELECT * FROM torrent_sources WHERE enabled = 1")
  suspend fun getEnabled(): List<TorrentSourceEntity>

  @Query("SELECT * FROM torrent_sources WHERE id = :id")
  suspend fun getById(id: String): TorrentSourceEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(source: TorrentSourceEntity)

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAllIgnore(sources: List<TorrentSourceEntity>)

  @Update
  suspend fun update(source: TorrentSourceEntity)

  @Query("DELETE FROM torrent_sources WHERE id = :id")
  suspend fun deleteById(id: String)

  @Query("DELETE FROM torrent_sources WHERE id IN ('builtin_archive_media', 'builtin_archive_software', 'dir_archive_audio') OR LOWER(baseUrl) LIKE '%archive.org%'")
  suspend fun deleteArchiveSources()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(sources: List<TorrentSourceEntity>)

  @Query("DELETE FROM torrent_sources")
  suspend fun deleteAll()

  @Query("SELECT COUNT(*) FROM torrent_sources")
  suspend fun count(): Int
}

@Dao
interface FavoriteDao {
  @Query("SELECT * FROM favorite_torrents ORDER BY savedAt DESC")
  fun observeAllFavorites(): Flow<List<FavoriteTorrentEntity>>

  @Query("SELECT id FROM favorite_torrents")
  fun observeFavoriteIds(): Flow<List<String>>

  @Query("SELECT * FROM favorite_torrents WHERE id = :id")
  suspend fun getFavoriteById(id: String): FavoriteTorrentEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertFavorite(item: FavoriteTorrentEntity)

  @Query("DELETE FROM favorite_torrents WHERE id = :id")
  suspend fun deleteFavoriteById(id: String)

  @Query("UPDATE favorite_torrents SET collectionId = :collectionId WHERE id = :torrentId")
  suspend fun moveToCollection(torrentId: String, collectionId: String?)

  @Query("UPDATE favorite_torrents SET collectionId = NULL WHERE collectionId = :collectionId")
  suspend fun clearCollectionReference(collectionId: String)

  @Query("SELECT * FROM favorite_torrents ORDER BY savedAt DESC")
  suspend fun getAllFavoritesOnce(): List<FavoriteTorrentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertFavorites(items: List<FavoriteTorrentEntity>)

  @Query("DELETE FROM favorite_torrents")
  suspend fun deleteAllFavorites()

  @Query("SELECT COUNT(*) FROM favorite_torrents")
  suspend fun countFavorites(): Int

  @Query("SELECT * FROM favorite_collections ORDER BY isSystem DESC, createdAt ASC")
  fun observeCollections(): Flow<List<FavoriteCollectionEntity>>

  @Query("SELECT * FROM favorite_collections ORDER BY isSystem DESC, createdAt ASC")
  suspend fun getAllCollectionsOnce(): List<FavoriteCollectionEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCollection(collection: FavoriteCollectionEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCollections(collections: List<FavoriteCollectionEntity>)

  @Query("UPDATE favorite_collections SET name = :newName WHERE id = :id")
  suspend fun renameCollection(id: String, newName: String)

  @Query("DELETE FROM favorite_collections WHERE id = :id AND isSystem = 0")
  suspend fun deleteCollection(id: String)

  @Query("DELETE FROM favorite_collections")
  suspend fun deleteAllCollections()
}

@Dao
interface SearchHistoryDao {
  @Query("SELECT * FROM search_history ORDER BY timestamp DESC LIMIT 50")
  fun observeHistory(): Flow<List<SearchHistoryEntity>>

  @Query("DELETE FROM search_history WHERE LOWER(query) = LOWER(:query)")
  suspend fun deleteByQuery(query: String)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entry: SearchHistoryEntity)

  @Query("DELETE FROM search_history WHERE id = :id")
  suspend fun deleteById(id: Long)

  @Query("SELECT * FROM search_history ORDER BY timestamp DESC")
  suspend fun getAllHistoryOnce(): List<SearchHistoryEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(entries: List<SearchHistoryEntity>)

  @Query("DELETE FROM search_history")
  suspend fun clearAll()

  @Query("SELECT COUNT(*) FROM search_history")
  suspend fun count(): Int
}

@Dao
interface CachedTorrentDao {
  @Query("SELECT * FROM cached_torrents WHERE queryKey = :queryKey ORDER BY cachedAt DESC")
  suspend fun getByQueryKey(queryKey: String): List<CachedTorrentEntity>

  @Query("SELECT * FROM cached_torrents ORDER BY cachedAt DESC LIMIT 120")
  suspend fun getRecentCached(): List<CachedTorrentEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<CachedTorrentEntity>)

  @Query("DELETE FROM cached_torrents WHERE sourceId IN ('builtin_archive_media', 'builtin_archive_software', 'dir_archive_audio')")
  suspend fun deleteArchiveCached()

  @Query("DELETE FROM cached_torrents WHERE cachedAt < :olderThan")
  suspend fun pruneOlderThan(olderThan: Long)

  @Query("DELETE FROM cached_torrents")
  suspend fun clearAll()

  @Query("SELECT COUNT(*) FROM cached_torrents")
  suspend fun count(): Int
}
