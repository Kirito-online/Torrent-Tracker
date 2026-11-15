package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.SearchHistoryItem
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import com.example.domain.model.UserPreferences
import com.example.ui.components.ActiveFilterChipsBar
import com.example.ui.components.FilterBottomSheet
import com.example.ui.components.ProgressiveSourceStatusStrip
import com.example.ui.components.SourceStatusIndicatorPill
import com.example.ui.components.TorrentResultRow
import com.example.ui.viewmodel.SearchUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSearchScreen(
  uiState: SearchUiState,
  sources: List<TorrentSource>,
  preferences: UserPreferences,
  recentHistory: List<SearchHistoryItem>,
  onOpenDrawer: () -> Unit,
  onQueryChanged: (String) -> Unit,
  onSubmitSearch: (String) -> Unit,
  onRefresh: () -> Unit,
  onLoadMore: () -> Unit,
  onUpdateFilter: (com.example.domain.model.SearchFilterState) -> Unit,
  onClearFilters: () -> Unit,
  onToggleFavorite: (TorrentItem) -> Unit,
  onOpenDetail: (TorrentItem) -> Unit,
  onSelectDetailInPane: (TorrentItem) -> Unit,
  onOpenSources: () -> Unit,
  onOpenHistory: () -> Unit,
  onFeedback: (String) -> Unit
) {
  var showFilterSheet by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  val shouldLoadMore by remember {
    derivedStateOf {
      val layoutInfo = listState.layoutInfo
      val total = layoutInfo.totalItemsCount
      val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      total > 5 && lastVisible >= total - 3
    }
  }

  LaunchedEffect(shouldLoadMore, uiState.hasMorePages, uiState.isLoadingMore) {
    if (shouldLoadMore && uiState.hasMorePages && !uiState.isLoadingMore && !uiState.isLoading) {
      onLoadMore()
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .windowInsetsPadding(WindowInsets.statusBars)
  ) {
    // Compact Top Header
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(
        onClick = onOpenDrawer,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("menu_drawer_button")
      ) {
        Icon(Icons.Filled.Menu, contentDescription = "Open navigation menu")
      }

      Image(
        painter = painterResource(id = R.drawable.ic_launcher_art),
        contentDescription = "Torrent Tracker icon",
        modifier = Modifier
          .size(26.dp)
          .clip(RoundedCornerShape(6.dp))
      )

      Spacer(Modifier.width(8.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Torrent Tracker",
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (uiState.activeCategoryTab != TorrentCategory.ALL) {
          Text(
            text = uiState.activeCategoryTab.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }

      SourceStatusIndicatorPill(
        sources = sources,
        isLoading = uiState.isLoading || uiState.isRefreshing,
        onClick = onOpenSources
      )
      Spacer(Modifier.width(4.dp))
    }

    // Prominent Search Bar + Filter Button directly beside it
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        modifier = Modifier
          .weight(1f)
          .height(48.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Filled.Search,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
          if (uiState.query.isEmpty()) {
            Text(
              text = "Search torrents across sources…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          BasicTextField(
            value = uiState.query,
            onValueChange = onQueryChanged,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
              color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmitSearch(uiState.query) }),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("search_input")
          )
        }
        if (uiState.query.isNotEmpty()) {
          IconButton(
            onClick = { onQueryChanged("") },
            modifier = Modifier
              .size(36.dp)
              .testTag("clear_search_button")
          ) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = "Clear search",
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }

      Spacer(Modifier.width(8.dp))

      FilledTonalIconButton(
        onClick = { showFilterSheet = true },
        modifier = Modifier
          .size(48.dp)
          .testTag("filter_button")
      ) {
        BadgedBox(
          badge = {
            if (uiState.filterState.activeCount > 0) {
              Badge { Text(uiState.filterState.activeCount.toString()) }
            }
          }
        ) {
          Icon(Icons.Filled.FilterList, contentDescription = "Filter and sort")
        }
      }
    }

    // Recent search chips when query is empty & history exists
    if (uiState.query.isBlank() && recentHistory.isNotEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = Icons.Filled.History,
          contentDescription = "Recent searches",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier
            .size(16.dp)
            .clickable(onClick = onOpenHistory)
        )
        recentHistory.take(6).forEach { hist ->
          SuggestionChip(
            onClick = { onSubmitSearch(hist.query) },
            label = { Text(hist.query, style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.height(28.dp)
          )
        }
      }
    }

    ActiveFilterChipsBar(
      filterState = uiState.filterState,
      sourceNames = uiState.sourceNames,
      onUpdateFilter = onUpdateFilter,
      onClearAll = onClearFilters
    )

    ProgressiveSourceStatusStrip(
      sourceProgress = uiState.sourceProgress,
      sourceNames = uiState.sourceNames,
      lastRefreshedAt = uiState.lastRefreshedAt,
      usingCached = uiState.usingCachedResults,
      onManageSources = onOpenSources
    )

    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

    // Responsive Main Content (Single list on compact, List-Detail on expanded tablets)
    BoxWithConstraints(modifier = Modifier.weight(1f)) {
      val isWideScreen = maxWidth >= 840.dp

      Row(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
          isRefreshing = uiState.isRefreshing,
          onRefresh = onRefresh,
          modifier = Modifier
            .weight(if (isWideScreen) 0.52f else 1f)
            .fillMaxHeight()
            .testTag("results_pull_refresh")
        ) {
          when {
            sources.none { it.enabled } -> {
              EmptyStateBox(
                icon = { Icon(Icons.Outlined.Dns, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary) },
                title = "No active torrent sources",
                subtitle = "Enable or add an RSS, JSON API, or HTML source to begin searching.",
                actionLabel = "Manage Sources",
                onAction = onOpenSources
              )
            }
            uiState.isLoading && uiState.results.isEmpty() -> {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                  CircularProgressIndicator(modifier = Modifier.size(32.dp))
                  Spacer(Modifier.height(10.dp))
                  Text("Querying enabled sources…", style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
            !uiState.isLoading && uiState.results.isEmpty() -> {
              val hasFilterOrQuery = uiState.query.isNotBlank() || !uiState.filterState.isDefault
              EmptyStateBox(
                icon = { Icon(Icons.Outlined.CloudOff, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = if (hasFilterOrQuery) "No matching torrents" else "Ready to discover",
                subtitle = if (hasFilterOrQuery) {
                  "Try clearing filters, adjusting your search terms, or checking source connectivity."
                } else {
                  "Pull down to refresh feeds or enter a keyword in the search bar."
                },
                actionLabel = if (!uiState.filterState.isDefault) "Clear Filters" else "Refresh Now",
                onAction = if (!uiState.filterState.isDefault) onClearFilters else onRefresh
              )
            }
            else -> {
              LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier
                  .fillMaxSize()
                  .testTag("torrent_results_list")
              ) {
                items(
                  items = uiState.results,
                  key = { it.id }
                ) { item ->
                  TorrentResultRow(
                    item = item,
                    density = preferences.displayDensity,
                    isSelected = isWideScreen && uiState.selectedTorrent?.id == item.id,
                    onClick = {
                      if (isWideScreen) onSelectDetailInPane(item) else onOpenDetail(item)
                    },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onFeedback = onFeedback
                  )
                }

                if (uiState.hasMorePages || uiState.isLoadingMore) {
                  item(key = "pagination_footer") {
                    Box(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                      contentAlignment = Alignment.Center
                    ) {
                      if (uiState.isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                      } else {
                        OutlinedButton(
                          onClick = onLoadMore,
                          modifier = Modifier.testTag("load_more_button")
                        ) {
                          Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                          Spacer(Modifier.width(6.dp))
                          Text("Load more results")
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }

        if (isWideScreen) {
          VerticalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
          Box(
            modifier = Modifier
              .weight(0.48f)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surface)
          ) {
            val selected = uiState.selectedTorrent
            if (selected != null) {
              TorrentDetailContent(
                item = selected,
                collections = emptyList(),
                onToggleFavorite = { onToggleFavorite(selected) },
                onMoveToCollection = {},
                onFeedback = onFeedback
              )
            } else {
              EmptyStateBox(
                icon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Select a torrent",
                subtitle = "Tap any entry on the left to inspect full metadata, magnet links, and actions.",
                actionLabel = null,
                onAction = {}
              )
            }
          }
        }
      }
    }
  }

  if (showFilterSheet) {
    FilterBottomSheet(
      currentFilter = uiState.filterState,
      availableSources = sources,
      onApply = onUpdateFilter,
      onClearAll = onClearFilters,
      onDismiss = { showFilterSheet = false }
    )
  }
}

@Composable
fun EmptyStateBox(
  icon: @Composable () -> Unit,
  title: String,
  subtitle: String,
  actionLabel: String?,
  onAction: () -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.width(300.dp)
    ) {
      icon()
      Spacer(Modifier.height(12.dp))
      Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
      Spacer(Modifier.height(4.dp))
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
      if (!actionLabel.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        Button(onClick = onAction) {
          Text(actionLabel)
        }
      }
    }
  }
}
