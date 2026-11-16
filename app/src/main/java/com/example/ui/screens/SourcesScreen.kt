package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.parser.TorrentMetadataParser
import com.example.domain.model.SourceHealth
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentSource
import com.example.ui.components.CompactLabelBadge
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SeederGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.SourcesUiState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
  sources: List<TorrentSource>,
  uiState: SourcesUiState,
  preferHttps: Boolean,
  onBack: () -> Unit,
  onSourceSearchQueryChanged: (String) -> Unit,
  onDiscoverQueryChanged: (String) -> Unit,
  onProbeWebsite: (String) -> Unit,
  onQuickAddSource: (TorrentSource) -> Unit,
  onToggleSource: (String, Boolean) -> Unit,
  onSaveSource: (TorrentSource) -> Unit,
  onDeleteSource: (String) -> Unit,
  onTestSource: (TorrentSource) -> Unit,
  onRefreshAll: () -> Unit,
  onDetectFeeds: (String) -> Unit,
  onClearDetectedFeeds: () -> Unit,
  onTestSelectorsPreview: (TorrentSource, String) -> Unit,
  onOpenRepair: (String) -> Unit,
  onOpenVerification: (String) -> Unit
) {
  var editingSource by remember { mutableStateOf<TorrentSource?>(null) }
  var deletingSource by remember { mutableStateOf<TorrentSource?>(null) }
  var showSearchAndAddSheet by remember { mutableStateOf(false) }

  val filteredSources = remember(sources, uiState.sourceSearchQuery) {
    val q = uiState.sourceSearchQuery.trim()
    if (q.isBlank()) sources
    else sources.filter { src ->
      src.name.contains(q, ignoreCase = true) ||
        src.baseUrl.contains(q, ignoreCase = true) ||
        src.sourceType.label.contains(q, ignoreCase = true)
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Torrent Sources", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("sources_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = { showSearchAndAddSheet = true },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("open_discover_sources_button")
          ) {
            Icon(Icons.Filled.TravelExplore, contentDescription = "Search and add new sources")
          }
          IconButton(
            onClick = onRefreshAll,
            enabled = !uiState.isRefreshingAll,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("refresh_all_sources_button")
          ) {
            if (uiState.isRefreshingAll) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
              Icon(Icons.Filled.Refresh, contentDescription = "Refresh all sources")
            }
          }
        }
      )
    },
    floatingActionButton = {
      FloatingActionButton(
        onClick = { showSearchAndAddSheet = true },
        modifier = Modifier.testTag("add_source_fab")
      ) {
        Icon(Icons.Filled.Add, contentDescription = "Search & add torrent source")
      }
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      // Compact Search & Quick-Add bar at top of SourcesScreen
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
          )
          Spacer(Modifier.width(8.dp))
          Box(modifier = Modifier.weight(1f)) {
            if (uiState.sourceSearchQuery.isEmpty()) {
              Text(
                "Filter saved sources…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            BasicTextField(
              value = uiState.sourceSearchQuery,
              onValueChange = onSourceSearchQueryChanged,
              singleLine = true,
              textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("sources_filter_input")
            )
          }
          if (uiState.sourceSearchQuery.isNotEmpty()) {
            IconButton(
              onClick = { onSourceSearchQueryChanged("") },
              modifier = Modifier.size(32.dp)
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
            }
          }
        }

        FilledTonalButton(
          onClick = { showSearchAndAddSheet = true },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
          modifier = Modifier
            .height(44.dp)
            .testTag("find_add_source_button")
        ) {
          Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Find & Add", style = MaterialTheme.typography.labelMedium)
        }
      }

      HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

      if (filteredSources.isEmpty()) {
        EmptyStateBox(
          icon = {
            Icon(
              Icons.Filled.TravelExplore,
              null,
              modifier = Modifier.size(40.dp),
              tint = MaterialTheme.colorScheme.primary
            )
          },
          title = if (uiState.sourceSearchQuery.isNotBlank()) "No matching saved sources" else "No sources configured",
          subtitle = "Search our directory or probe any website URL to add a torrent source.",
          actionLabel = "Search & Add Source",
          onAction = {
            if (uiState.sourceSearchQuery.isNotBlank()) {
              onDiscoverQueryChanged(uiState.sourceSearchQuery)
            }
            showSearchAndAddSheet = true
          }
        )
      } else {
        LazyColumn(
          contentPadding = PaddingValues(bottom = 84.dp),
          modifier = Modifier
            .fillMaxSize()
            .testTag("sources_list")
        ) {
          items(filteredSources, key = { it.id }) { source ->
            val isTesting = uiState.testingSourceIds.contains(source.id)
            SourceRowItem(
              source = source,
              isTesting = isTesting,
              onToggle = { enabled -> onToggleSource(source.id, enabled) },
              onTest = { onTestSource(source) },
              onEdit = {
                onClearDetectedFeeds()
                editingSource = source
              },
              onRepair = { onOpenRepair(source.id) },
              onVerifyCloudflare = { onOpenVerification(source.id) },
              onDelete = { deletingSource = source }
            )
          }
        }
      }
    }
  }

  if (showSearchAndAddSheet) {
    SourceSearchAndAddSheet(
      existingSources = sources,
      uiState = uiState,
      onQueryChanged = onDiscoverQueryChanged,
      onProbeUrl = onProbeWebsite,
      onQuickAdd = { newSource ->
        onQuickAddSource(newSource)
      },
      onCustomizeAndAdd = { templateSource ->
        showSearchAndAddSheet = false
        onClearDetectedFeeds()
        editingSource = templateSource
      },
      onManualAdd = {
        showSearchAndAddSheet = false
        onClearDetectedFeeds()
        editingSource = TorrentSource(
          id = "custom_${UUID.randomUUID().toString().take(8)}",
          name = "",
          baseUrl = "https://",
          searchUrlTemplate = "",
          feedUrl = "",
          sourceType = SourceType.RSS_ATOM
        )
      },
      onDismiss = { showSearchAndAddSheet = false }
    )
  }

  editingSource?.let { src ->
    SourceEditorSheet(
      initialSource = src,
      preferHttps = preferHttps,
      uiState = uiState,
      onDetectFeeds = onDetectFeeds,
      onTestPreview = onTestSelectorsPreview,
      onSave = { saved ->
        onSaveSource(saved)
        editingSource = null
      },
      onDismiss = { editingSource = null }
    )
  }

  deletingSource?.let { src ->
    AlertDialog(
      onDismissRequest = { deletingSource = null },
      title = { Text("Remove Source?") },
      text = { Text("Remove '${src.name}' from your configured torrent sources?") },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteSource(src.id)
            deletingSource = null
          }
        ) {
          Text("Remove", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { deletingSource = null }) { Text("Cancel") }
      }
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SourceSearchAndAddSheet(
  existingSources: List<TorrentSource>,
  uiState: SourcesUiState,
  onQueryChanged: (String) -> Unit,
  onProbeUrl: (String) -> Unit,
  onQuickAdd: (TorrentSource) -> Unit,
  onCustomizeAndAdd: (TorrentSource) -> Unit,
  onManualAdd: () -> Unit,
  onDismiss: () -> Unit
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val existingKeys = remember(existingSources) {
    existingSources.flatMap {
      listOf(it.id.lowercase(), it.feedUrl.trim().lowercase(), it.searchUrlTemplate.trim().lowercase())
    }.filter { it.isNotBlank() }.toSet()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 20.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Search & Add Sources", style = MaterialTheme.typography.titleLarge)
        TextButton(
          onClick = onManualAdd,
          modifier = Modifier.testTag("manual_add_source_button")
        ) {
          Text("Manual Setup")
        }
      }

      Text(
        "Search popular torrent sites (The Pirate Bay, 1337x, YTS, Nyaa, EZTV, TorrentGalaxy…) or enter any URL to auto-detect.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(Modifier.height(10.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = uiState.discoverQuery,
          onValueChange = onQueryChanged,
          placeholder = { Text("Search site name, topic, or enter URL…") },
          leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) },
          trailingIcon = {
            if (uiState.discoverQuery.isNotEmpty()) {
              IconButton(onClick = { onQueryChanged("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
              }
            }
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onProbeUrl(uiState.discoverQuery) }),
          modifier = Modifier
            .weight(1f)
            .testTag("discover_source_input")
        )

        FilledTonalButton(
          onClick = { onProbeUrl(uiState.discoverQuery) },
          enabled = uiState.discoverQuery.isNotBlank() && !uiState.isDiscovering,
          modifier = Modifier
            .height(52.dp)
            .testTag("probe_url_button")
        ) {
          if (uiState.isDiscovering) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Filled.Public, null, modifier = Modifier.size(16.dp))
          }
          Spacer(Modifier.width(4.dp))
          Text("Probe")
        }
      }

      Spacer(Modifier.height(10.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .height(380.dp)
          .testTag("discover_results_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Live Website Probe Card
        if (uiState.isDiscovering) {
          item(key = "probing_loader") {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
              Spacer(Modifier.width(10.dp))
              Text("Probing website for RSS/Atom feeds, JSON, and search endpoints…", style = MaterialTheme.typography.bodySmall)
            }
          }
        }

        uiState.discoveredCandidate?.let { candidate ->
          item(key = "live_probe_result") {
            val candSource = candidate.source
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f))
                .border(0.8.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                .padding(12.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text("Live Website Probe", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (candSource != null) {
                  CompactLabelBadge(
                    text = candSource.sourceType.label,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                  )
                }
              }
              Spacer(Modifier.height(4.dp))
              if (candSource != null) {
                Text(candSource.name, style = MaterialTheme.typography.titleMedium)
                Text(
                  candSource.feedUrl.ifBlank { candSource.searchUrlTemplate.ifBlank { candSource.baseUrl } },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
              }
              Text(candidate.summary, style = MaterialTheme.typography.bodySmall)
              if (candSource != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Button(
                    onClick = { onQuickAdd(candSource) },
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Source")
                  }
                  OutlinedButton(
                    onClick = { onCustomizeAndAdd(candSource) },
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Customize")
                  }
                }
              }
            }
          }
        }

        // Directory Catalog Matches
        item(key = "directory_header") {
          Text(
            text = "DIRECTORY (${uiState.directoryMatches.size})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
          )
        }

        if (uiState.directoryMatches.isEmpty()) {
          item(key = "no_dir_match") {
            Text(
              "No directory presets match '${uiState.discoverQuery}'. Tap Probe above to inspect it as a website URL.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(vertical = 8.dp)
            )
          }
        } else {
          items(uiState.directoryMatches, key = { it.id }) { entry ->
            val alreadyAdded = existingKeys.contains(entry.id.lowercase()) ||
              (entry.feedUrl.isNotBlank() && existingKeys.contains(entry.feedUrl.trim().lowercase())) ||
              (entry.searchUrlTemplate.isNotBlank() && existingKeys.contains(entry.searchUrlTemplate.trim().lowercase()))

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .padding(10.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                  Text(entry.name, style = MaterialTheme.typography.titleSmall)
                  Text(
                    entry.baseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                  )
                }
                if (alreadyAdded) {
                  OutlinedButton(
                    onClick = {},
                    enabled = false,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                  ) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Added", style = MaterialTheme.typography.labelSmall)
                  }
                } else {
                  Button(
                    onClick = { onQuickAdd(entry.toTorrentSource()) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                      .height(34.dp)
                      .testTag("quick_add_${entry.id}")
                  ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", style = MaterialTheme.typography.labelSmall)
                  }
                }
              }
              Spacer(Modifier.height(4.dp))
              Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(Modifier.height(6.dp))
              FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactLabelBadge(
                  text = entry.sourceType.label,
                  containerColor = MaterialTheme.colorScheme.surface,
                  contentColor = MaterialTheme.colorScheme.onSurface
                )
                CompactLabelBadge(
                  text = entry.categoryHint,
                  containerColor = MaterialTheme.colorScheme.surface,
                  contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRowItem(
  source: TorrentSource,
  isTesting: Boolean,
  onToggle: (Boolean) -> Unit,
  onTest: () -> Unit,
  onEdit: () -> Unit,
  onRepair: () -> Unit,
  onVerifyCloudflare: () -> Unit,
  onDelete: () -> Unit
) {
  val statusColor = when (source.health) {
    SourceHealth.ONLINE -> SeederGreen
    SourceHealth.REDIRECTED, SourceHealth.CLOUDFLARE_REQUIRED -> WarningOrange
    SourceHealth.OFFLINE, SourceHealth.PARSE_ERROR -> ErrorRed
    SourceHealth.DISABLED, SourceHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(10.dp)
          .clip(CircleShape)
          .background(statusColor)
      )
      Spacer(Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = source.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.width(6.dp))
          Icon(
            imageVector = if (source.isHttps) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (source.isHttps) "HTTPS" else "Insecure HTTP",
            tint = if (source.isHttps) SeederGreen else WarningOrange,
            modifier = Modifier.size(14.dp)
          )
        }
        Text(
          text = source.baseUrl,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Switch(
        checked = source.enabled,
        onCheckedChange = onToggle,
        modifier = Modifier.testTag("source_switch_${source.id}")
      )
    }

    Spacer(Modifier.height(6.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      val healthLabel = when (source.health) {
        SourceHealth.ONLINE -> "Online"
        SourceHealth.CLOUDFLARE_REQUIRED -> "Verify CF"
        SourceHealth.PARSE_ERROR -> "Parse Error"
        SourceHealth.REDIRECTED -> "Redirected"
        SourceHealth.OFFLINE -> "Offline"
        SourceHealth.DISABLED -> "Disabled"
        SourceHealth.UNKNOWN -> "Untested"
      }
      CompactLabelBadge(
        text = healthLabel,
        containerColor = statusColor.copy(alpha = 0.16f),
        contentColor = statusColor
      )
      CompactLabelBadge(
        text = source.sourceType.label,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
      )
      val lastOk = source.lastSuccessAt?.let {
        "Synced ${TorrentMetadataParser.formatRelativeDate(it, null)}"
      } ?: "Not synced yet"
      CompactLabelBadge(
        text = lastOk,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
      )
      source.lastCheckedAt?.let { checked ->
        if (source.lastSuccessAt == null || checked - (source.lastSuccessAt ?: 0L) > 60_000L) {
          CompactLabelBadge(
            text = "Checked ${TorrentMetadataParser.formatRelativeDate(checked, null)}",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      if (!source.isHttps) {
        CompactLabelBadge(
          text = if (source.allowCleartextHttp) "HTTP Allowed" else "HTTP Blocked",
          containerColor = WarningOrange.copy(alpha = 0.16f),
          contentColor = WarningOrange
        )
      }
    }

    if (!source.lastError.isNullOrBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = source.lastError,
        style = MaterialTheme.typography.bodySmall,
        color = if (source.requiresCloudflare) WarningOrange else ErrorRed
      )
    }

    Spacer(Modifier.height(6.dp))

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      OutlinedButton(
        onClick = onTest,
        enabled = !isTesting,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
          .height(34.dp)
          .testTag("source_test_${source.id}")
      ) {
        if (isTesting) {
          CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("Test", style = MaterialTheme.typography.labelSmall)
      }

      OutlinedButton(
        onClick = onEdit,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
          .height(34.dp)
          .testTag("source_edit_${source.id}")
      ) {
        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Edit", style = MaterialTheme.typography.labelSmall)
      }

      OutlinedButton(
        onClick = onRepair,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
          .height(34.dp)
          .testTag("source_repair_${source.id}")
      ) {
        Icon(Icons.Filled.Build, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Repair", style = MaterialTheme.typography.labelSmall)
      }

      OutlinedButton(
        onClick = onVerifyCloudflare,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier
          .height(34.dp)
          .testTag("source_verify_${source.id}")
      ) {
        Icon(Icons.Filled.VerifiedUser, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text("Open Verification", style = MaterialTheme.typography.labelSmall)
      }

      if (!source.isBuiltIn) {
        IconButton(
          onClick = onDelete,
          modifier = Modifier
            .size(34.dp)
            .testTag("source_delete_${source.id}")
        ) {
          Icon(Icons.Filled.Delete, contentDescription = "Delete source", tint = ErrorRed, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
  HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SourceEditorSheet(
  initialSource: TorrentSource,
  preferHttps: Boolean,
  uiState: SourcesUiState,
  onDetectFeeds: (String) -> Unit,
  onTestPreview: (TorrentSource, String) -> Unit,
  onSave: (TorrentSource) -> Unit,
  onDismiss: () -> Unit
) {
  var name by remember(initialSource) { mutableStateOf(initialSource.name) }
  var baseUrl by remember(initialSource) { mutableStateOf(initialSource.baseUrl) }
  var searchTemplate by remember(initialSource) { mutableStateOf(initialSource.searchUrlTemplate) }
  var feedUrl by remember(initialSource) { mutableStateOf(initialSource.feedUrl) }
  var sourceType by remember(initialSource) { mutableStateOf(initialSource.sourceType) }
  var htmlCfg by remember(initialSource) { mutableStateOf(initialSource.htmlSelectors) }
  var jsonCfg by remember(initialSource) { mutableStateOf(initialSource.jsonMapping) }
  var acknowledgedHttpRisk by remember(initialSource) { mutableStateOf(initialSource.allowCleartextHttp) }
  var showHttpConfirmDialog by remember { mutableStateOf(false) }

  val hasHttpUrl = baseUrl.trim().startsWith("http://", true) ||
    searchTemplate.trim().startsWith("http://", true) ||
    feedUrl.trim().startsWith("http://", true)
  val isHttpWarning = hasHttpUrl

  fun buildDraft(): TorrentSource = initialSource.copy(
    name = name.trim().ifBlank { "Custom Source" },
    baseUrl = baseUrl.trim(),
    searchUrlTemplate = searchTemplate.trim(),
    feedUrl = feedUrl.trim(),
    sourceType = sourceType,
    allowCleartextHttp = hasHttpUrl && acknowledgedHttpRisk,
    htmlSelectors = htmlCfg,
    jsonMapping = jsonCfg
  )

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
      Text(
        text = if (initialSource.name.isBlank()) "Add Torrent Source" else "Edit Torrent Source",
        style = MaterialTheme.typography.titleLarge
      )
      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Source Name") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("edit_source_name")
      )
      Spacer(Modifier.height(8.dp))

      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it; acknowledgedHttpRisk = false },
          label = { Text("Website URL (https://...)") },
          singleLine = true,
          modifier = Modifier
            .weight(1f)
            .testTag("edit_source_base_url")
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
          onClick = { onDetectFeeds(baseUrl) },
          enabled = baseUrl.length > 8 && !uiState.isDetectingFeeds,
          modifier = Modifier.height(52.dp)
        ) {
          if (uiState.isDetectingFeeds) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Filled.AutoFixHigh, null, modifier = Modifier.size(16.dp))
          }
          Spacer(Modifier.width(4.dp))
          Text("Auto-Detect")
        }
      }

      if (uiState.detectedFeeds.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("Detected feeds (tap to apply):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          uiState.detectedFeeds.forEach { feed ->
            FilterChip(
              selected = feedUrl == feed.url,
              onClick = {
                feedUrl = feed.url
                sourceType = feed.type
              },
              label = { Text("${feed.title} (${feed.type.label})", style = MaterialTheme.typography.labelSmall) }
            )
          }
        }
      }

      if (isHttpWarning) {
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(WarningOrange.copy(alpha = 0.14f))
            .border(0.5.dp, WarningOrange, RoundedCornerShape(8.dp))
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Filled.Warning, null, tint = WarningOrange, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text("Unencrypted HTTP permission required", style = MaterialTheme.typography.labelLarge, color = WarningOrange)
            Text(
              "Cleartext HTTP is blocked by default. Saving explicitly authorizes HTTP for this source only.",
              style = MaterialTheme.typography.bodySmall
            )
          }
          TextButton(onClick = {
            baseUrl = baseUrl.replaceFirst("http://", "https://", true)
            searchTemplate = searchTemplate.replaceFirst("http://", "https://", true)
            feedUrl = feedUrl.replaceFirst("http://", "https://", true)
          }) {
            Text("Use HTTPS")
          }
        }
      }

      Spacer(Modifier.height(10.dp))
      Text("Adapter Format", style = MaterialTheme.typography.titleSmall)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SourceType.entries.forEach { t ->
          FilterChip(
            selected = sourceType == t,
            onClick = { sourceType = t },
            label = { Text(t.label, style = MaterialTheme.typography.labelSmall) }
          )
        }
      }

      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = feedUrl,
        onValueChange = { feedUrl = it },
        label = { Text("RSS / Atom Feed URL (optional for default feed)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(
        value = searchTemplate,
        onValueChange = { searchTemplate = it },
        label = { Text("Search URL Template (use {query} and {page})") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("edit_source_search_template")
      )

      if (sourceType == SourceType.HTML_SELECTOR) {
        Spacer(Modifier.height(12.dp))
        Text("CSS Selectors (Jsoup)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        SelectorField("Item Container", htmlCfg.itemContainer) { htmlCfg = htmlCfg.copy(itemContainer = it) }
        SelectorField("Title", htmlCfg.title) { htmlCfg = htmlCfg.copy(title = it) }
        SelectorField("Magnet Link", htmlCfg.magnetLink) { htmlCfg = htmlCfg.copy(magnetLink = it) }
        SelectorField("Details Page Link", htmlCfg.detailsLink) { htmlCfg = htmlCfg.copy(detailsLink = it) }
        SelectorField("Cover Image", htmlCfg.coverImage) { htmlCfg = htmlCfg.copy(coverImage = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("File Size", htmlCfg.fileSize, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(fileSize = it) }
          SelectorField("Seeders", htmlCfg.seeders, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(seeders = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("Leechers", htmlCfg.leechers, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(leechers = it) }
          SelectorField("Upload Date", htmlCfg.uploadDate, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(uploadDate = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("Uploader", htmlCfg.uploader, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(uploader = it) }
          SelectorField("Next Page", htmlCfg.nextPageLink, Modifier.weight(1f)) { htmlCfg = htmlCfg.copy(nextPageLink = it) }
        }
      } else if (sourceType == SourceType.JSON_API) {
        Spacer(Modifier.height(12.dp))
        Text("JSON Field Mapping", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        SelectorField("Items Array Path (dot notation)", jsonCfg.itemsPath) { jsonCfg = jsonCfg.copy(itemsPath = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("Title Field", jsonCfg.titleField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(titleField = it) }
          SelectorField("Magnet Field", jsonCfg.magnetField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(magnetField = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("InfoHash Field", jsonCfg.infoHashField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(infoHashField = it) }
          SelectorField("Size Field", jsonCfg.sizeField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(sizeField = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          SelectorField("Seeders Field", jsonCfg.seedersField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(seedersField = it) }
          SelectorField("Image Field", jsonCfg.imageField, Modifier.weight(1f)) { jsonCfg = jsonCfg.copy(imageField = it) }
        }
      }

      Spacer(Modifier.height(10.dp))
      OutlinedButton(
        onClick = { onTestPreview(buildDraft(), "ubuntu") },
        modifier = Modifier.fillMaxWidth()
      ) {
        Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Test Extraction Preview")
      }

      uiState.selectorPreviewMessage?.let { msg ->
        Spacer(Modifier.height(6.dp))
        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        uiState.selectorPreviewItems.forEach { previewItem ->
          Text(
            "• ${previewItem.title} (${previewItem.fileSizeText ?: "unknown size"})",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Spacer(Modifier.height(14.dp))
      Button(
        onClick = {
          if (isHttpWarning && !acknowledgedHttpRisk) {
            showHttpConfirmDialog = true
          } else {
            onSave(buildDraft())
          }
        },
        enabled = name.isNotBlank() && (baseUrl.startsWith("http://", true) || baseUrl.startsWith("https://", true)),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("save_source_button")
      ) {
        Text("Save Source")
      }

      Spacer(Modifier.height(20.dp))
    }
  }

  if (showHttpConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showHttpConfirmDialog = false },
      title = { Text("Authorize Insecure HTTP?") },
      text = {
        Text(
          "This source uses unencrypted http:// instead of https://. Saving grants per-source cleartext HTTP permission for this source only. Continue?"
        )
      },
      confirmButton = {
        TextButton(onClick = {
          acknowledgedHttpRisk = true
          showHttpConfirmDialog = false
          onSave(buildDraft())
        }) { Text("Save Anyway") }
      },
      dismissButton = {
        TextButton(onClick = { showHttpConfirmDialog = false }) { Text("Cancel") }
      }
    )
  }
}

@Composable
private fun SelectorField(
  label: String,
  value: String,
  modifier: Modifier = Modifier.fillMaxWidth(),
  onChange: (String) -> Unit
) {
  OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    singleLine = true,
    textStyle = MaterialTheme.typography.labelMedium,
    modifier = modifier.padding(vertical = 3.dp)
  )
}
