package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.local.db.AppDatabase
import com.example.data.local.prefs.PreferencesRepository
import com.example.data.logging.ActivityCategory
import com.example.data.logging.ActivityLogger
import com.example.data.network.TorrentHttpClient
import com.example.data.repository.TorrentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class AppContainer(application: Application) {
  val activityLogger: ActivityLogger by lazy {
    ActivityLogger(File(application.filesDir, "activity_error_log.jsonl"))
  }
  val database: AppDatabase by lazy { AppDatabase.build(application) }
  val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(application) }
  val httpClient: TorrentHttpClient by lazy { TorrentHttpClient(logger = activityLogger) }

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
      imageLoader = imageLoader,
      logger = activityLogger
    )
  }
}

class TorrentTrackerApp : Application(), ImageLoaderFactory {
  lateinit var container: AppContainer
    private set

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
    container.activityLogger.installCrashHandler()
    appScope.launch {
      container.preferencesRepository.preferencesFlow
        .map { it.activityLoggingEnabled }
        .distinctUntilChanged()
        .collect { enabled ->
          container.activityLogger.setEnabled(enabled)
          if (enabled) {
            container.activityLogger.info(
              ActivityCategory.SYSTEM,
              "App session active",
              "Version ${BuildConfig.VERSION_NAME} initialized with activity & error logging."
            )
          }
        }
    }
  }

  override fun newImageLoader(): ImageLoader = container.imageLoader
}
