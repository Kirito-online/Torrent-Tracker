package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.logging.ActivityLogEntry
import com.example.data.logging.ActivityLogLevel
import com.example.data.parser.TorrentMetadataParser
import com.example.domain.model.AccentColorOption
import com.example.domain.model.BackupSelection
import com.example.domain.model.DisplayDensity
import com.example.domain.model.RestoreMode
import com.example.domain.model.SortOption
import com.example.domain.model.SourceProgressState
import com.example.domain.model.StorageUsageInfo
import com.example.domain.model.ThemeMode
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentSource
import com.example.domain.model.TorrentTrackerBackup
import com.example.domain.model.UserPreferences
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SeederGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  preferences: UserPreferences,
  storageUsage: StorageUsageInfo,
  diagnosticsResults: List<Pair<TorrentSource, SourceProgressState>>,
  isRunningDiagnostics: Boolean,
  pendingImportBackup: TorrentTrackerBackup? = null,
  isProcessingBackup: Boolean = false,
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
  onClearCookies: () -> Unit = {},
  onBuildDiagnosticReport: suspend () -> String = { "" },
  activityLogs: List<ActivityLogEntry> = emptyList(),
  onActivityLoggingChanged: (Boolean) -> Unit = {},
  onOpenActivityLog: () -> Unit = {},
  onBuildActivityLogText: () -> String = { "" },
  onClearActivityLogs: () -> Unit = {},
  onBuildBackupJson: suspend (BackupSelection) -> String = { "{}" },
  onLoadImportJson: (String) -> Unit = {},
  onConfirmRestoreBackup: (BackupSelection, RestoreMode) -> Unit = { _, _ -> },
  onDismissImportPreview: () -> Unit = {},
  onBackupFeedback: (String) -> Unit = {},
  onOpenAbout: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var showExportDialog by remember { mutableStateOf(false) }
  var exportSelection by remember { mutableStateOf(BackupSelection()) }

  val createBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        val json = onBuildBackupJson(exportSelection)
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open output file")
        }
      }.onSuccess {
        onBackupFeedback("Backup exported successfully")
      }.onFailure { err ->
        onBackupFeedback("Export failed: ${err.localizedMessage ?: "I/O error"}")
      }
    }
  }

  val openBackupLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val maxChars = 4 * 1024 * 1024
            val sb = StringBuilder()
            val buf = CharArray(8192)
            var total = 0
            while (true) {
              val read = reader.read(buf)
              if (read == -1) break
              total += read
              if (total > maxChars) error("Backup file exceeds 4 MB safety limit")
              sb.appendRange(buf, 0, read)
            }
            sb.toString()
          } ?: error("Unable to read selected file")
        }
      }.onSuccess { jsonText ->
        onLoadImportJson(jsonText)
      }.onFailure { err ->
        onBackupFeedback("Could not read backup: ${err.localizedMessage ?: "I/O error"}")
      }
    }
  }

  val createDiagnosticReportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        val reportText = onBuildDiagnosticReport()
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(reportText.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open output file")
        }
      }.onSuccess {
        onBackupFeedback("Diagnostic report exported (cookies excluded)")
      }.onFailure { err ->
        onBackupFeedback("Diagnostic export failed: ${err.localizedMessage ?: "I/O error"}")
      }
    }
  }

  val createActivityLogLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        val logText = onBuildActivityLogText()
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(logText.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open output file")
        }
      }.onSuccess {
        onBackupFeedback("Activity & error log exported")
      }.onFailure { err ->
        onBackupFeedback("Activity log export failed: ${err.localizedMessage ?: "I/O error"}")
      }
    }
  }

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
        subtitle = "Warn before saving http:// URLs; never bypasses per-source HTTP permission or OS security policy",
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

      Spacer(Modifier.height(6.dp))
      OutlinedButton(
        onClick = {
          val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
          createDiagnosticReportLauncher.launch("torrent-tracker-diagnostics-$stamp.txt")
        },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("export_diagnostics_button")
      ) {
        Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Export Diagnostic Report (Zero Cookies)")
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

      // 4. Activity & Error Log
      val errorCount = remember(activityLogs) { activityLogs.count { it.level == ActivityLogLevel.ERROR } }
      SettingsSectionHeader("Activity & Error Log")
      SettingsSwitchRow(
        title = "Record activity & error log",
        subtitle = "Capture searches, network/parser failures, repairs, backups, and crashes locally for debugging (cookies redacted)",
        checked = preferences.activityLoggingEnabled,
        onCheckedChange = onActivityLoggingChanged,
        switchTag = "activity_logging_switch"
      )
      Text(
        text = "Recorded: ${activityLogs.size} events · $errorCount errors",
        style = MaterialTheme.typography.labelSmall,
        color = if (errorCount > 0) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onOpenActivityLog,
          modifier = Modifier
            .weight(1f)
            .testTag("inspect_activity_log_button")
        ) {
          Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Inspect", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
          onClick = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            createActivityLogLauncher.launch("torrent-tracker-activity-log-$stamp.txt")
          },
          modifier = Modifier
            .weight(1f)
            .testTag("export_activity_log_button")
        ) {
          Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Export", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
          onClick = onClearActivityLogs,
          enabled = activityLogs.isNotEmpty(),
          modifier = Modifier
            .testTag("clear_activity_log_button")
        ) {
          Text("Clear", style = MaterialTheme.typography.labelMedium)
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 5. Storage
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

      Spacer(Modifier.height(6.dp))
      OutlinedButton(
        onClick = onClearCookies,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("clear_cookies_button")
      ) {
        Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text("Clear Verification & Session Cookies", style = MaterialTheme.typography.labelSmall)
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 5. Backup & Restore
      SettingsSectionHeader("Backup & Restore")
      Text(
        "Export or import sources, favorites, collections, preferences, and optional search history as portable JSON. Session cookies and private tokens are never included.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { showExportDialog = true },
          enabled = !isProcessingBackup,
          modifier = Modifier
            .weight(1f)
            .testTag("export_backup_button")
        ) {
          Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Export Backup", style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
          onClick = { openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
          enabled = !isProcessingBackup,
          modifier = Modifier
            .weight(1f)
            .testTag("import_backup_button")
        ) {
          Icon(Icons.Filled.UploadFile, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Import Backup", style = MaterialTheme.typography.labelMedium)
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)

      // 6. About
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

  if (showExportDialog) {
    AlertDialog(
      onDismissRequest = { showExportDialog = false },
      title = { Text("Export Backup") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            "Choose what to include in the JSON backup:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(Modifier.height(4.dp))
          BackupCategoryCheckbox(
            label = "Torrent Sources & Selectors",
            checked = exportSelection.includeSources,
            onToggle = { exportSelection = exportSelection.copy(includeSources = it) }
          )
          BackupCategoryCheckbox(
            label = "Favorites & Collections",
            checked = exportSelection.includeFavoritesAndCollections,
            onToggle = { exportSelection = exportSelection.copy(includeFavoritesAndCollections = it) }
          )
          BackupCategoryCheckbox(
            label = "App Preferences",
            checked = exportSelection.includePreferences,
            onToggle = { exportSelection = exportSelection.copy(includePreferences = it) }
          )
          BackupCategoryCheckbox(
            label = "Search History (Optional)",
            checked = exportSelection.includeSearchHistory,
            onToggle = { exportSelection = exportSelection.copy(includeSearchHistory = it) }
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            showExportDialog = false
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            createBackupLauncher.launch("torrent-tracker-backup-$stamp.json")
          },
          enabled = exportSelection.hasAnySelected
        ) {
          Text("Save File…")
        }
      },
      dismissButton = {
        TextButton(onClick = { showExportDialog = false }) { Text("Cancel") }
      }
    )
  }

  pendingImportBackup?.let { backup ->
    var importSelection by remember(backup) {
      mutableStateOf(
        BackupSelection(
          includeSources = backup.sources.isNotEmpty(),
          includeFavoritesAndCollections = backup.favorites.isNotEmpty() || backup.collections.isNotEmpty(),
          includePreferences = backup.preferences != null,
          includeSearchHistory = backup.searchHistory.isNotEmpty()
        )
      )
    }
    var restoreMode by remember(backup) { mutableStateOf(RestoreMode.MERGE) }
    val dateFmt = remember(backup.exportedAt) {
      SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(backup.exportedAt))
    }

    AlertDialog(
      onDismissRequest = { if (!isProcessingBackup) onDismissImportPreview() },
      title = { Text("Restore Backup Preview") },
      text = {
        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            "Schema v${backup.schemaVersion} · Exported $dateFmt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (backup.sources.isNotEmpty()) {
            BackupCategoryCheckbox(
              label = "Sources (${backup.sources.size})",
              checked = importSelection.includeSources,
              onToggle = { importSelection = importSelection.copy(includeSources = it) }
            )
          }
          if (backup.favorites.isNotEmpty() || backup.collections.isNotEmpty()) {
            BackupCategoryCheckbox(
              label = "Favorites (${backup.favorites.size}) & Collections (${backup.collections.size})",
              checked = importSelection.includeFavoritesAndCollections,
              onToggle = { importSelection = importSelection.copy(includeFavoritesAndCollections = it) }
            )
          }
          if (backup.preferences != null) {
            BackupCategoryCheckbox(
              label = "App Preferences",
              checked = importSelection.includePreferences,
              onToggle = { importSelection = importSelection.copy(includePreferences = it) }
            )
          }
          if (backup.searchHistory.isNotEmpty()) {
            BackupCategoryCheckbox(
              label = "Search History (${backup.searchHistory.size})",
              checked = importSelection.includeSearchHistory,
              onToggle = { importSelection = importSelection.copy(includeSearchHistory = it) }
            )
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
          Text("Restore Mode", style = MaterialTheme.typography.titleSmall)
          RestoreMode.entries.forEach { mode ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { restoreMode = mode }
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              RadioButton(selected = restoreMode == mode, onClick = null)
              Spacer(Modifier.width(6.dp))
              Column {
                Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                  text = if (mode == RestoreMode.MERGE) {
                    "Updates matching IDs and preserves your other existing items."
                  } else {
                    "Deletes all existing items in selected categories before restoring."
                  },
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          if (restoreMode == RestoreMode.REPLACE) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(ErrorRed.copy(alpha = 0.12f))
                .border(0.5.dp, ErrorRed, RoundedCornerShape(8.dp))
                .padding(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Filled.Warning, null, tint = ErrorRed, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text(
                "Destructive action: existing data in checked categories will be overwritten.",
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { onConfirmRestoreBackup(importSelection, restoreMode) },
          enabled = importSelection.hasAnySelected && !isProcessingBackup,
          colors = if (restoreMode == RestoreMode.REPLACE) {
            ButtonDefaults.buttonColors(containerColor = ErrorRed)
          } else {
            ButtonDefaults.buttonColors()
          }
        ) {
          if (isProcessingBackup) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          } else {
            Text(if (restoreMode == RestoreMode.REPLACE) "Replace Data" else "Merge Backup")
          }
        }
      },
      dismissButton = {
        TextButton(
          onClick = onDismissImportPreview,
          enabled = !isProcessingBackup
        ) { Text("Cancel") }
      }
    )
  }
}

@Composable
private fun BackupCategoryCheckbox(
  label: String,
  checked: Boolean,
  onToggle: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(6.dp))
      .clickable { onToggle(!checked) }
      .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium)
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
  onCheckedChange: (Boolean) -> Unit,
  switchTag: String? = null
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
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = if (switchTag != null) Modifier.testTag(switchTag) else Modifier
    )
  }
}
