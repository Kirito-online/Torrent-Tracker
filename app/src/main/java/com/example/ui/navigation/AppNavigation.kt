package com.example.ui.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.ui.components.DrawerDestination
import com.example.ui.components.NavigationDrawerContent
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.CloudflareVerificationScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HomeSearchScreen
import com.example.ui.screens.SearchHistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SourceRepairScreen
import com.example.ui.screens.SourcesScreen
import com.example.ui.screens.TorrentDetailScreen
import com.example.ui.viewmodel.FavoritesViewModel
import com.example.ui.viewmodel.SearchViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SourcesViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data class DetailRoute(val torrentId: String)
@Serializable data object FavoritesRoute
@Serializable data object SourcesRoute
@Serializable data class SourceRepairRoute(val sourceId: String)
@Serializable data class CloudflareVerifyRoute(val sourceId: String)
@Serializable data object SearchHistoryRoute
@Serializable data object SettingsRoute
@Serializable data object AboutRoute

@Composable
fun AppNavigation(
  searchViewModel: SearchViewModel,
  sourcesViewModel: SourcesViewModel,
  favoritesViewModel: FavoritesViewModel,
  settingsViewModel: SettingsViewModel
) {
  val navController = rememberNavController()
  val drawerState = rememberDrawerState(DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
  val sources by sourcesViewModel.sources.collectAsStateWithLifecycle()
  val sourcesUiState by sourcesViewModel.uiState.collectAsStateWithLifecycle()
  val preferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
  val favorites by favoritesViewModel.filteredFavorites.collectAsStateWithLifecycle()
  val favFilter by favoritesViewModel.filter.collectAsStateWithLifecycle()
  val collections by favoritesViewModel.collections.collectAsStateWithLifecycle()
  val history by favoritesViewModel.searchHistory.collectAsStateWithLifecycle()
  val storageUsage by settingsViewModel.storageUsage.collectAsStateWithLifecycle()
  val diagnosticsResults by settingsViewModel.diagnosticsResults.collectAsStateWithLifecycle()
  val isRunningBulkDiag by settingsViewModel.isRunningBulkDiagnostics.collectAsStateWithLifecycle()
  val pendingImportBackup by settingsViewModel.pendingImportBackup.collectAsStateWithLifecycle()
  val isProcessingBackup by settingsViewModel.isProcessingBackup.collectAsStateWithLifecycle()
  val settingsFeedback by settingsViewModel.feedbackMessage.collectAsStateWithLifecycle()

  val backStackEntry by navController.currentBackStackEntryAsState()
  val routeStr = backStackEntry?.destination?.route.orEmpty()
  val currentRouteKey = when {
    routeStr.contains("FavoritesRoute") -> "favorites"
    routeStr.contains("SourcesRoute") -> "sources"
    routeStr.contains("SearchHistoryRoute") -> "history"
    routeStr.contains("SettingsRoute") -> "settings"
    routeStr.contains("AboutRoute") -> "about"
    else -> "home"
  }

  LaunchedEffect(sourcesUiState.statusMessage) {
    sourcesUiState.statusMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      sourcesViewModel.clearStatusMessage()
    }
  }
  LaunchedEffect(settingsFeedback) {
    settingsFeedback?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      settingsViewModel.clearFeedback()
    }
  }

  fun showToast(msg: String) {
    scope.launch { snackbarHostState.showSnackbar(msg) }
  }

  fun handleDrawerDestination(dest: DrawerDestination) {
    scope.launch { drawerState.close() }
    when (dest) {
      DrawerDestination.Home -> {
        searchViewModel.selectDrawerCategory(TorrentCategory.ALL)
        navController.navigate(HomeRoute) {
          popUpTo(HomeRoute) { inclusive = true }
          launchSingleTop = true
        }
      }
      is DrawerDestination.Category -> {
        searchViewModel.selectDrawerCategory(dest.category)
        navController.navigate(HomeRoute) {
          popUpTo(HomeRoute) { inclusive = true }
          launchSingleTop = true
        }
      }
      DrawerDestination.Favorites -> navController.navigate(FavoritesRoute) { launchSingleTop = true }
      DrawerDestination.Sources -> navController.navigate(SourcesRoute) { launchSingleTop = true }
      DrawerDestination.SearchHistory -> navController.navigate(SearchHistoryRoute) { launchSingleTop = true }
      DrawerDestination.Settings -> navController.navigate(SettingsRoute) { launchSingleTop = true }
      DrawerDestination.About -> navController.navigate(AboutRoute) { launchSingleTop = true }
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      NavigationDrawerContent(
        currentRouteKey = currentRouteKey,
        activeCategory = searchState.activeCategoryTab,
        enabledSourcesCount = sources.count { it.enabled },
        totalSourcesCount = sources.size,
        onNavigate = ::handleDrawerDestination
      )
    }
  ) {
    Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { _ ->
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val showRail = maxWidth >= 840.dp

        Row(modifier = Modifier.fillMaxSize()) {
          if (showRail) {
            NavigationRail(
              containerColor = MaterialTheme.colorScheme.surface
            ) {
              NavigationRailItem(
                selected = currentRouteKey == "home",
                onClick = { handleDrawerDestination(DrawerDestination.Home) },
                icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                label = { Text("Home") }
              )
              NavigationRailItem(
                selected = currentRouteKey == "favorites",
                onClick = { handleDrawerDestination(DrawerDestination.Favorites) },
                icon = { Icon(Icons.Filled.Bookmark, contentDescription = "Favorites") },
                label = { Text("Favorites") }
              )
              NavigationRailItem(
                selected = currentRouteKey == "sources",
                onClick = { handleDrawerDestination(DrawerDestination.Sources) },
                icon = { Icon(Icons.Filled.Dns, contentDescription = "Sources") },
                label = { Text("Sources") }
              )
              NavigationRailItem(
                selected = currentRouteKey == "history",
                onClick = { handleDrawerDestination(DrawerDestination.SearchHistory) },
                icon = { Icon(Icons.Filled.History, contentDescription = "History") },
                label = { Text("History") }
              )
              NavigationRailItem(
                selected = currentRouteKey == "settings",
                onClick = { handleDrawerDestination(DrawerDestination.Settings) },
                icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                label = { Text("Settings") }
              )
            }
          }

          NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = Modifier
              .weight(1f)
              .padding(0.dp)
          ) {
            composable<HomeRoute> {
              HomeSearchScreen(
                uiState = searchState,
                sources = sources,
                preferences = preferences,
                recentHistory = history,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onQueryChanged = searchViewModel::onQueryChanged,
                onSubmitSearch = searchViewModel::submitSearch,
                onRefresh = { searchViewModel.refresh() },
                onLoadMore = searchViewModel::loadNextPage,
                onUpdateFilter = searchViewModel::updateFilters,
                onClearFilters = searchViewModel::clearAllFilters,
                onToggleFavorite = searchViewModel::toggleFavorite,
                onOpenDetail = { item ->
                  searchViewModel.selectTorrent(item)
                  navController.navigate(DetailRoute(item.id))
                },
                onSelectDetailInPane = searchViewModel::selectTorrent,
                onOpenSources = { navController.navigate(SourcesRoute) },
                onOpenHistory = { navController.navigate(SearchHistoryRoute) },
                onFeedback = ::showToast
              )
            }

            composable<DetailRoute> { entry ->
              val route = entry.toRoute<DetailRoute>()
              val favIds by searchViewModel.favoriteIds.collectAsStateWithLifecycle()
              val loadedItem by produceState<TorrentItem?>(initialValue = searchState.selectedTorrent, route.torrentId, favIds) {
                val found = searchViewModel.findTorrentById(route.torrentId)
                value = found?.copy(isFavorite = favIds.contains(found.id))
              }
              TorrentDetailScreen(
                item = loadedItem,
                collections = collections,
                onBack = { navController.popBackStack() },
                onToggleFavorite = searchViewModel::toggleFavorite,
                onMoveToCollection = favoritesViewModel::moveFavoriteToCollection,
                onSelectSourceOffer = favoritesViewModel::selectPreferredSourceOffer,
                onFeedback = ::showToast
              )
            }

            composable<FavoritesRoute> {
              FavoritesScreen(
                favorites = favorites,
                collections = collections,
                filterState = favFilter,
                density = preferences.displayDensity,
                onBack = { navController.popBackStack() },
                onSearchQueryChanged = favoritesViewModel::updateSearchQuery,
                onSelectCategory = favoritesViewModel::selectCategory,
                onSelectCollection = favoritesViewModel::selectCollection,
                onSetSort = favoritesViewModel::setSortOption,
                onCreateCollection = favoritesViewModel::createCollection,
                onRenameCollection = favoritesViewModel::renameCollection,
                onDeleteCollection = favoritesViewModel::deleteCollection,
                onMoveToCollection = favoritesViewModel::moveFavoriteToCollection,
                onToggleFavorite = favoritesViewModel::toggleFavorite,
                onOpenDetail = { item ->
                  searchViewModel.selectTorrent(item)
                  navController.navigate(DetailRoute(item.id))
                },
                onFeedback = ::showToast
              )
            }

            composable<SourcesRoute> {
              SourcesScreen(
                sources = sources,
                uiState = sourcesUiState,
                preferHttps = preferences.preferHttps,
                onBack = { navController.popBackStack() },
                onSourceSearchQueryChanged = sourcesViewModel::onSourceSearchQueryChanged,
                onDiscoverQueryChanged = sourcesViewModel::onDiscoverQueryChanged,
                onProbeWebsite = sourcesViewModel::probeWebsiteForDiscovery,
                onQuickAddSource = { newSource -> sourcesViewModel.quickAddSource(newSource) },
                onToggleSource = sourcesViewModel::toggleSource,
                onSaveSource = sourcesViewModel::saveSource,
                onDeleteSource = sourcesViewModel::deleteSource,
                onTestSource = sourcesViewModel::testSource,
                onRefreshAll = sourcesViewModel::refreshAllSources,
                onDetectFeeds = sourcesViewModel::detectFeeds,
                onClearDetectedFeeds = sourcesViewModel::clearDetectedFeeds,
                onTestSelectorsPreview = sourcesViewModel::testSelectorsPreview,
                onOpenRepair = { id -> navController.navigate(SourceRepairRoute(id)) },
                onOpenVerification = { id -> navController.navigate(CloudflareVerifyRoute(id)) }
              )
            }

            composable<SourceRepairRoute> { entry ->
              val route = entry.toRoute<SourceRepairRoute>()
              val src = sources.firstOrNull { it.id == route.sourceId }
              SourceRepairScreen(
                sourceId = route.sourceId,
                source = src,
                preferHttps = preferences.preferHttps,
                uiState = sourcesUiState,
                onBack = { navController.popBackStack() },
                onRunDiagnostic = sourcesViewModel::runRepairDiagnostic,
                onTestCandidateUrl = sourcesViewModel::testReplacementCandidate,
                onApplyReplacement = sourcesViewModel::applyReplacement,
                onSaveSource = { updated ->
                  sourcesViewModel.saveSource(updated)
                  sourcesViewModel.runRepairDiagnostic(updated.id)
                },
                onRestorePrevious = sourcesViewModel::restorePreviousConfig,
                onDetectFeeds = sourcesViewModel::detectFeeds,
                onClearDetectedFeeds = sourcesViewModel::clearDetectedFeeds,
                onTestSelectorsPreview = sourcesViewModel::testSelectorsPreview,
                onOpenVerification = { id -> navController.navigate(CloudflareVerifyRoute(id)) }
              )
            }

            composable<CloudflareVerifyRoute> { entry ->
              val route = entry.toRoute<CloudflareVerifyRoute>()
              val src = sources.firstOrNull { it.id == route.sourceId }
              CloudflareVerificationScreen(
                source = src,
                onBack = { navController.popBackStack() },
                onVerifyAndRetrySource = sourcesViewModel::retryAfterCloudflareVerification,
                onFeedback = ::showToast
              )
            }

            composable<SearchHistoryRoute> {
              SearchHistoryScreen(
                history = history,
                onBack = { navController.popBackStack() },
                onSelectQuery = { q ->
                  searchViewModel.submitSearch(q)
                  navController.navigate(HomeRoute) {
                    popUpTo(HomeRoute) { inclusive = true }
                  }
                },
                onDeleteItem = favoritesViewModel::deleteHistoryItem,
                onClearAll = favoritesViewModel::clearSearchHistory
              )
            }

            composable<SettingsRoute> {
              SettingsScreen(
                preferences = preferences,
                storageUsage = storageUsage,
                diagnosticsResults = diagnosticsResults,
                isRunningDiagnostics = isRunningBulkDiag,
                pendingImportBackup = pendingImportBackup,
                isProcessingBackup = isProcessingBackup,
                onBack = { navController.popBackStack() },
                onRefreshStorageStats = settingsViewModel::refreshStorageStats,
                onThemeChanged = settingsViewModel::setThemeMode,
                onAccentChanged = settingsViewModel::setAccentColor,
                onDensityChanged = settingsViewModel::setDisplayDensity,
                onDefaultCategoryChanged = settingsViewModel::setDefaultCategory,
                onDefaultSortChanged = settingsViewModel::setDefaultSort,
                onDebounceChanged = settingsViewModel::setSearchDebounce,
                onAutoRefreshChanged = settingsViewModel::setAutoRefreshOnLaunch,
                onTimeoutChanged = settingsViewModel::setSourceTimeout,
                onConcurrencyChanged = settingsViewModel::setMaxConcurrency,
                onPreferHttpsChanged = settingsViewModel::setPreferHttps,
                onOpenSources = { navController.navigate(SourcesRoute) },
                onRunDiagnostics = settingsViewModel::runConnectionDiagnostics,
                onClearImageCache = settingsViewModel::clearImageCache,
                onClearSearchHistory = settingsViewModel::clearSearchHistory,
                onClearTempCache = settingsViewModel::clearTemporaryCache,
                onClearCookies = settingsViewModel::clearVerificationCookies,
                onBuildDiagnosticReport = settingsViewModel::buildDiagnosticReportText,
                onBuildBackupJson = settingsViewModel::buildBackupJson,
                onLoadImportJson = settingsViewModel::loadBackupForPreview,
                onConfirmRestoreBackup = settingsViewModel::confirmRestoreBackup,
                onDismissImportPreview = settingsViewModel::dismissImportPreview,
                onBackupFeedback = settingsViewModel::notifyFeedback,
                onOpenAbout = { navController.navigate(AboutRoute) }
              )
            }

            composable<AboutRoute> {
              AboutScreen(onBack = { navController.popBackStack() })
            }
          }
        }
      }
    }
  }
}
