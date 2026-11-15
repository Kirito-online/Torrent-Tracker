package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.db.AppDatabase
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.network.TorrentHttpClient
import com.example.data.repository.TorrentRepository
import java.io.File

class AppContainer(application: Application) {
  val database: AppDatabase by lazy { AppDatabase.build(application) }
  val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(application) }
  val httpClient: TorrentHttpClient by lazy { TorrentHttpClient() }

  val imageLoader: ImageLoader by lazy {
    ImageLoader.Builder(application)
      .memoryCache {
        MemoryCache.Builder(application)
          .maxSizePercent(0.18)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(File(application.cacheDir, "image_cache"))
          .maxSizeBytes(64L * 1024 * 1024)
          .build()
      }
      .crossfade(true)
      .build()
  }

  val torrentRepository: TorrentRepository by lazy {
    TorrentRepository(
      context = application,
      database = database,
      httpClient = httpClient,
      imageLoader = imageLoader
    )
  }
}

class TorrentTrackerApp : Application(), ImageLoaderFactory {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }

  override fun newImageLoader(): ImageLoader = container.imageLoader
}
