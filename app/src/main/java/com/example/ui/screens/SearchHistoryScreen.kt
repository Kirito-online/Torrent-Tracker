package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.parser.TorrentMetadataParser
import com.example.domain.model.SearchHistoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHistoryScreen(
  history: List<SearchHistoryItem>,
  onBack: () -> Unit,
  onSelectQuery: (String) -> Unit,
  onDeleteItem: (Long) -> Unit,
  onClearAll: () -> Unit
) {
  var confirmClear by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Search History", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("history_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (history.isNotEmpty()) {
            IconButton(
              onClick = { confirmClear = true },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("clear_history_button")
            ) {
              Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all history")
            }
          }
        }
      )
    }
  ) { innerPadding ->
    if (history.isEmpty()) {
      EmptyStateBox(
        icon = { Icon(Icons.Filled.History, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        title = "No search history",
        subtitle = "Your recent multi-source torrent queries will appear here for quick replay.",
        actionLabel = null,
        onAction = {}
      )
    } else {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .testTag("history_list")
      ) {
        items(history, key = { it.id }) { item ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelectQuery(item.query) }
              .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(12.dp))
              Column {
                Text(item.query, style = MaterialTheme.typography.titleSmall)
                val whenText = TorrentMetadataParser.formatRelativeDate(item.timestamp, null) ?: ""
                Text(
                  text = "${item.categoryLabel} · ${item.resultCount} results · $whenText",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
            IconButton(
              onClick = { onDeleteItem(item.id) },
              modifier = Modifier.minimumInteractiveComponentSize()
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Delete entry", modifier = Modifier.size(18.dp))
            }
          }
          HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("Clear Search History?") },
      text = { Text("This removes all saved search queries from local storage.") },
      confirmButton = {
        TextButton(onClick = {
          onClearAll()
          confirmClear = false
        }) {
          Text("Clear All", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
      }
    )
  }
}
