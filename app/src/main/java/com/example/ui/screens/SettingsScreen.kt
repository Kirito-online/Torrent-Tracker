package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.parser.TorrentMetadataParser
import com.example.domain.model.AccentColorOption
import com.example.domain.model.DisplayDensity
import com.example.domain.model.SortOption
import com.example.domain.model.SourceProgressState
import com.example.domain.model.StorageUsageInfo
import com.example.domain.model.ThemeMode
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentSource
import com.example.domain.model.UserPreferences
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SeederGreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  preferences: UserPreferences,
  storageUsage: StorageUsageInfo,
  diagnosticsResults: List<Pair<TorrentSource, SourceProgressState>>,
  isRunningDiagnostics: Boolean,
  onBack: () -> Unit,
  onRefreshStorageStats: () -> Unit,
  onThemeChanged: (ThemeMode) -> Unit,
  onAccentChanged: (AccentColorOption) -> Unit,
  onDensityChanged: (DisplayDensity) -> Unit,
  onDefaultCategoryChanged: (TorrentCategory) -> Unit,
  onDefaultSortChanged: (SortOption) -> Unit,
  onDebounceChanged: (Int) -> Unit,
  onAutoRefreshChanged: (Boolean) -> Unit,
  onTimeoutChanged: (Int) -> Unit,
  onConcurrencyChanged: (Int) -> Unit,
  onPreferHttpsChanged: (Boolean) -> Unit,
  onOpenSources: () -> Unit,
  onRunDiagnostics: () -> Unit,
  onClearImageCache: () -> Unit,
  onClearSearchHistory: () -> Unit,
  onClearTempCache: () -> Unit,
  onOpenAbout: () -> Unit
) {
  LaunchedEffect(Unit) {
    onRefreshStorageStats()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("settings_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        }
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
      // 1. General
      SettingsSectionHeader("General")
      Text("Default Content Category", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(4.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
          TorrentCategory.ALL,
          TorrentCategory.MOVIES,
          TorrentCategory.TV_SHOWS,
          TorrentCategory.ANIME,
          TorrentCategory.SOFTWARE
        ).forEach { cat ->
          FilterChip(
            selected = preferences.defaultCategory == cat,
            onClick = { onDefaultCategoryChanged(cat) },
            label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) }
          )
        }
      }

      Spacer(Modifier.height(10.dp))
      Text("Default Sorting Method", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(4.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SortOption.entries.forEach { sort ->
          FilterChip(
            selected = preferences.defaultSort == sort,
            onClick = { onDefaultSortChanged(sort) },
            label = { Text(sort.label, style = MaterialTheme.typography.labelSmall) }
          )
        }
      }

      Spacer(Modifier.height(10.dp))
      SettingsSwitchRow(
        title = "Auto-refresh feeds on launch",
        subtitle = "Fetch latest entries from enabled sources when opening the app",
        checked = preferences.autoRefreshOnLaunch,
        onCheckedChange = onAutoRefreshChanged
      )

      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Search input debounce", style = MaterialTheme.typography.bodyMedium)
        Text("${preferences.searchDebounceMs} ms", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      Slider(
        value = preferences.searchDebounceMs.toFloat(),
        onValueChange = { onDebounceChanged(it.toInt()) },
        valueRange = 200f..1000f,
        steps = 7
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 2. Appearance
      SettingsSectionHeader("Appearance")
      Text("Theme", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(4.dp))
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemeMode.entries.forEach { mode ->
          FilterChip(
            selected = preferences.themeMode == mode,
            onClick = { onThemeChanged(mode) },
            label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.testTag("theme_option_${mode.name.lowercase()}")
          )
        }
      }

      Spacer(Modifier.height(10.dp))
      Text("Accent Color", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(6.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        AccentColorOption.entries.forEach { accent ->
          val selected = preferences.accentColor == accent
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(Color(accent.primaryHex))
              .border(
                width = if (selected) 2.5.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                shape = CircleShape
              )
              .clickable { onAccentChanged(accent) }
              .testTag("accent_option_${accent.name.lowercase()}"),
            contentAlignment = Alignment.Center
          ) {
            if (selected) {
              Icon(Icons.Filled.Check, contentDescription = accent.label, tint = Color.Black, modifier = Modifier.size(18.dp))
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))
      Text("Result List Density", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.height(4.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayDensity.entries.forEach { d ->
          FilterChip(
            selected = preferences.displayDensity == d,
            onClick = { onDensityChanged(d) },
            label = { Text(d.label, style = MaterialTheme.typography.labelSmall) }
          )
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 3. Sources
      SettingsSectionHeader("Sources")
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable(onClick = onOpenSources)
          .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Dns, null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(12.dp))
          Column {
            Text("Manage Torrent Sources", style = MaterialTheme.typography.titleSmall)
            Text("Add, edit CSS/JSON/RSS adapters, or repair URLs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        Icon(Icons.Filled.ChevronRight, null)
      }

      SettingsSwitchRow(
        title = "Prefer HTTPS & warn on HTTP",
        subtitle = "Show security warning before saving unencrypted HTTP sources",
        checked = preferences.preferHttps,
        onCheckedChange = onPreferHttpsChanged
      )

      Spacer(Modifier.height(6.dp))
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Source request timeout", style = MaterialTheme.typography.bodyMedium)
        Text("${preferences.sourceTimeoutSeconds}s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      Slider(
        value = preferences.sourceTimeoutSeconds.toFloat(),
        onValueChange = { onTimeoutChanged(it.toInt()) },
        valueRange = 5f..30f,
        steps = 4
      )

      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Max concurrent source connections", style = MaterialTheme.typography.bodyMedium)
        Text("${preferences.maxConcurrentSources}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      Slider(
        value = preferences.maxConcurrentSources.toFloat(),
        onValueChange = { onConcurrencyChanged(it.toInt()) },
        valueRange = 1f..8f,
        steps = 6
      )

      OutlinedButton(
        onClick = onRunDiagnostics,
        enabled = !isRunningDiagnostics,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("run_bulk_diagnostics_button")
      ) {
        if (isRunningDiagnostics) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
          Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text("Run Source Connection Diagnostics")
      }

      if (diagnosticsResults.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp)
        ) {
          diagnosticsResults.forEach { (src, state) ->
            val ok = state is SourceProgressState.Success
            val summary = when (state) {
              is SourceProgressState.Success -> "OK (${state.count} items, ${state.durationMs}ms)"
              is SourceProgressState.Error -> state.message
              SourceProgressState.Loading -> "Testing…"
            }
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(src.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
              Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (ok) SeederGreen else ErrorRed
              )
            }
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 4. Storage
      SettingsSectionHeader("Storage")
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(12.dp)
      ) {
        val totalFmt = TorrentMetadataParser.formatBytes(storageUsage.totalBytes) ?: "0 KB"
        val imgFmt = TorrentMetadataParser.formatBytes(storageUsage.imageCacheBytes) ?: "0 KB"
        val dbFmt = TorrentMetadataParser.formatBytes(storageUsage.databaseBytes) ?: "0 KB"
        Text("App Storage Used: $totalFmt", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
          "Cover cache: $imgFmt · Local database: $dbFmt",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          "${storageUsage.favoritesCount} favorites · ${storageUsage.cachedTorrentsCount} cached entries · ${storageUsage.searchHistoryCount} history items",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClearImageCache, modifier = Modifier.weight(1f)) {
          Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(15.dp))
          Spacer(Modifier.width(4.dp))
          Text("Images", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onClearSearchHistory, modifier = Modifier.weight(1f)) {
          Text("History", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onClearTempCache, modifier = Modifier.weight(1f)) {
          Text("Temp Cache", style = MaterialTheme.typography.labelSmall)
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 5. About
      SettingsSectionHeader("About")
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .clickable(onClick = onOpenAbout)
          .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.width(12.dp))
          Column {
            Text("Torrent Tracker v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text("Open-source licenses & privacy architecture", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        Icon(Icons.Filled.ChevronRight, null)
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun SettingsSectionHeader(title: String) {
  Text(
    text = title.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
  )
}

@Composable
private fun SettingsSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}
