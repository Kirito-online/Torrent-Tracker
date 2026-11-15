package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.DisplayDensity
import com.example.domain.model.FavoriteCollection
import com.example.domain.model.SortOption
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.ui.components.TorrentResultRow
import com.example.ui.viewmodel.FavoritesFilterState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
  favorites: List<TorrentItem>,
  collections: List<FavoriteCollection>,
  filterState: FavoritesFilterState,
  density: DisplayDensity,
  onBack: () -> Unit,
  onSearchQueryChanged: (String) -> Unit,
  onSelectCategory: (TorrentCategory) -> Unit,
  onSelectCollection: (String?) -> Unit,
  onSetSort: (SortOption) -> Unit,
  onCreateCollection: (String) -> Unit,
  onRenameCollection: (String, String) -> Unit,
  onDeleteCollection: (String) -> Unit,
  onMoveToCollection: (String, String?) -> Unit,
  onToggleFavorite: (TorrentItem) -> Unit,
  onOpenDetail: (TorrentItem) -> Unit
) {
  var showSortMenu by remember { mutableStateOf(false) }
  var showNewCollectionDialog by remember { mutableStateOf(false) }
  var renamingCollection by remember { mutableStateOf<FavoriteCollection?>(null) }
  var movingItem by remember { mutableStateOf<TorrentItem?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Favorites", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("favorites_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          Box {
            IconButton(
              onClick = { showSortMenu = true },
              modifier = Modifier.minimumInteractiveComponentSize()
            ) {
              Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort favorites")
            }
            DropdownMenu(
              expanded = showSortMenu,
              onDismissRequest = { showSortMenu = false }
            ) {
              SortOption.entries.forEach { option ->
                DropdownMenuItem(
                  text = { Text(option.label) },
                  onClick = {
                    onSetSort(option)
                    showSortMenu = false
                  }
                )
              }
            }
          }
          IconButton(
            onClick = { showNewCollectionDialog = true },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("create_collection_button")
          ) {
            Icon(Icons.Filled.Add, contentDescription = "Create collection")
          }
        }
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      // Search inside favorites
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp)
          .height(44.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
          if (filterState.searchQuery.isEmpty()) {
            Text(
              "Search saved favorites (offline)…",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          BasicTextField(
            value = filterState.searchQuery,
            onValueChange = onSearchQueryChanged,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("favorites_search_input")
          )
        }
        if (filterState.searchQuery.isNotEmpty()) {
          IconButton(onClick = { onSearchQueryChanged("") }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
          }
        }
      }

      // Custom Collections Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        FilterChip(
          selected = filterState.selectedCollectionId == null,
          onClick = { onSelectCollection(null) },
          label = { Text("All Categories", style = MaterialTheme.typography.labelSmall) }
        )
        collections.forEach { col ->
          val selected = filterState.selectedCollectionId == col.id
          FilterChip(
            selected = selected,
            onClick = { onSelectCollection(col.id) },
            leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(14.dp)) },
            label = { Text(col.name, style = MaterialTheme.typography.labelSmall) },
            trailingIcon = {
              Icon(
                Icons.Filled.Edit,
                contentDescription = "Edit collection",
                modifier = Modifier
                  .size(14.dp)
                  .clickable { renamingCollection = col }
              )
            }
          )
        }
      }

      // Category Tabs (when not filtering by a custom collection)
      if (filterState.selectedCollectionId == null) {
        val categories = TorrentCategory.entries
        val selectedIndex = categories.indexOf(filterState.selectedCategory).coerceAtLeast(0)
        ScrollableTabRow(
          selectedTabIndex = selectedIndex,
          edgePadding = 12.dp
        ) {
          categories.forEach { cat ->
            Tab(
              selected = filterState.selectedCategory == cat,
              onClick = { onSelectCategory(cat) },
              text = { Text(cat.label, style = MaterialTheme.typography.labelLarge) }
            )
          }
        }
      }

      HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

      if (favorites.isEmpty()) {
        EmptyStateBox(
          icon = {
            Icon(
              Icons.Filled.BookmarkBorder,
              null,
              modifier = Modifier.size(42.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          },
          title = "No saved favorites here",
          subtitle = "Bookmark torrents from search results to keep their metadata accessible offline.",
          actionLabel = null,
          onAction = {}
        )
      } else {
        LazyColumn(
          contentPadding = PaddingValues(bottom = 24.dp),
          modifier = Modifier
            .fillMaxSize()
            .testTag("favorites_list")
        ) {
          items(favorites, key = { it.id }) { item ->
            Column {
              TorrentResultRow(
                item = item,
                density = density,
                onClick = { onOpenDetail(item) },
                onToggleFavorite = { onToggleFavorite(item) }
              )
              if (collections.isNotEmpty()) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .clickable { movingItem = item }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  val colLabel = collections.firstOrNull { it.id == item.collectionId }?.name ?: "No collection"
                  Text(
                    text = "Collection: $colLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("Move", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                  }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
              }
            }
          }
        }
      }
    }
  }

  if (showNewCollectionDialog) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showNewCollectionDialog = false },
      title = { Text("New Collection") },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Collection name") },
          singleLine = true,
          modifier = Modifier.testTag("new_collection_name_input")
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onCreateCollection(name)
            showNewCollectionDialog = false
          },
          enabled = name.isNotBlank()
        ) { Text("Create") }
      },
      dismissButton = {
        TextButton(onClick = { showNewCollectionDialog = false }) { Text("Cancel") }
      }
    )
  }

  renamingCollection?.let { col ->
    var newName by remember(col) { mutableStateOf(col.name) }
    AlertDialog(
      onDismissRequest = { renamingCollection = null },
      title = { Text("Edit Collection") },
      text = {
        Column {
          OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Collection name") },
            singleLine = true
          )
          Spacer(Modifier.height(10.dp))
          TextButton(
            onClick = {
              onDeleteCollection(col.id)
              renamingCollection = null
            }
          ) {
            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Delete Collection", color = MaterialTheme.colorScheme.error)
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            onRenameCollection(col.id, newName)
            renamingCollection = null
          },
          enabled = newName.isNotBlank()
        ) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { renamingCollection = null }) { Text("Cancel") }
      }
    )
  }

  movingItem?.let { item ->
    AlertDialog(
      onDismissRequest = { movingItem = null },
      title = { Text("Move to Collection") },
      text = {
        Column {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onMoveToCollection(item.id, null)
                movingItem = null
              }
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            RadioButton(selected = item.collectionId == null, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("None (Category only)")
          }
          collections.forEach { col ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  onMoveToCollection(item.id, col.id)
                  movingItem = null
                }
                .padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = item.collectionId == col.id, onClick = null)
              Spacer(Modifier.width(8.dp))
              Text(col.name)
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { movingItem = null }) { Text("Close") }
      }
    )
  }
}
