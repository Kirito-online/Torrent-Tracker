package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.logging.ActivityLogEntry
import com.example.data.logging.ActivityLogLevel
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityLogScreen(
  entries: List<ActivityLogEntry>,
  loggingEnabled: Boolean,
  onToggleLogging: (Boolean) -> Unit,
  onClearLogs: () -> Unit,
  onBuildLogText: () -> String,
  onFeedback: (String) -> Unit,
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var selectedLevel by remember { mutableStateOf<ActivityLogLevel?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  var expandedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

  val errorCount = remember(entries) { entries.count { it.level == ActivityLogLevel.ERROR } }
  val warnCount = remember(entries) { entries.count { it.level == ActivityLogLevel.WARN } }
  val infoCount = remember(entries) { entries.count { it.level == ActivityLogLevel.INFO } }

  val filteredEntries = remember(entries, selectedLevel, searchQuery) {
    val q = searchQuery.trim()
    entries.filter { entry ->
      val levelOk = selectedLevel == null || entry.level == selectedLevel
      val queryOk = q.isEmpty() ||
        entry.action.contains(q, ignoreCase = true) ||
        entry.detail.contains(q, ignoreCase = true) ||
        entry.category.label.contains(q, ignoreCase = true) ||
        entry.throwableClass?.contains(q, ignoreCase = true) == true
      levelOk && queryOk
    }
  }

  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("text/plain")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      runCatching {
        val text = onBuildLogText()
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
          } ?: error("Unable to open output stream")
        }
      }.onSuccess {
        onFeedback("Activity & error log exported")
      }.onFailure { err ->
        onFeedback("Log export failed: ${err.localizedMessage ?: "I/O error"}")
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Activity & Error Log", style = MaterialTheme.typography.titleLarge)
            Text(
              text = "${entries.size} events · $errorCount errors",
              style = MaterialTheme.typography.labelSmall,
              color = if (errorCount > 0) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("activity_log_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = {
              val text = onBuildLogText()
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              clipboard?.setPrimaryClip(ClipData.newPlainText("Torrent Tracker Activity Log", text))
              onFeedback("Activity log copied to clipboard")
            },
            enabled = entries.isNotEmpty(),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("activity_log_copy_button")
          ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy all logs")
          }
          IconButton(
            onClick = {
              val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
              exportLauncher.launch("torrent-tracker-activity-log-$stamp.txt")
            },
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("activity_log_export_top_button")
          ) {
            Icon(Icons.Filled.Download, contentDescription = "Export activity log")
          }
          IconButton(
            onClick = onClearLogs,
            enabled = entries.isNotEmpty(),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("activity_log_clear_top_button")
          ) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear activity log")
          }
        }
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp)
    ) {
      Card(
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = if (loggingEnabled) "Recording active" else "Recording paused",
              style = MaterialTheme.typography.titleSmall
            )
            Text(
              text = "Captures network, parser, repair, backup, and crash errors locally (cookies redacted).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Switch(
            checked = loggingEnabled,
            onCheckedChange = onToggleLogging,
            modifier = Modifier.testTag("activity_log_inspector_switch")
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        placeholder = { Text("Filter by source, error, URL, or exception…") },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("activity_log_search_field")
      )

      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        FilterChip(
          selected = selectedLevel == null,
          onClick = { selectedLevel = null },
          label = { Text("All (${entries.size})") }
        )
        FilterChip(
          selected = selectedLevel == ActivityLogLevel.ERROR,
          onClick = {
            selectedLevel = if (selectedLevel == ActivityLogLevel.ERROR) null else ActivityLogLevel.ERROR
          },
          label = { Text("Errors ($errorCount)") }
        )
        FilterChip(
          selected = selectedLevel == ActivityLogLevel.WARN,
          onClick = {
            selectedLevel = if (selectedLevel == ActivityLogLevel.WARN) null else ActivityLogLevel.WARN
          },
          label = { Text("Warnings ($warnCount)") }
        )
        FilterChip(
          selected = selectedLevel == ActivityLogLevel.INFO,
          onClick = {
            selectedLevel = if (selectedLevel == ActivityLogLevel.INFO) null else ActivityLogLevel.INFO
          },
          label = { Text("Info ($infoCount)") }
        )
      }

      Spacer(Modifier.height(8.dp))

      if (filteredEntries.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              Icons.Filled.BugReport,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
              text = if (entries.isEmpty()) {
                if (loggingEnabled) "No activities logged yet" else "Activity & error logging is off"
              } else {
                "No log entries match your filter"
              },
              style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
              text = if (entries.isEmpty() && !loggingEnabled) {
                "Turn on recording above to capture errors, searches, source diagnostics, and crashes."
              } else {
                "Events appear here automatically as you search, test sources, repair endpoints, or restore backups."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(filteredEntries, key = { it.id }) { entry ->
            val isExpanded = expandedIds.contains(entry.id)
            ActivityLogEntryCard(
              entry = entry,
              expanded = isExpanded,
              onToggleExpand = {
                expandedIds = if (isExpanded) expandedIds - entry.id else expandedIds + entry.id
              }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ActivityLogEntryCard(
  entry: ActivityLogEntry,
  expanded: Boolean,
  onToggleExpand: () -> Unit
) {
  val accentColor = when (entry.level) {
    ActivityLogLevel.ERROR -> ErrorRed
    ActivityLogLevel.WARN -> WarningOrange
    ActivityLogLevel.INFO -> MaterialTheme.colorScheme.primary
  }
  val levelIcon = when (entry.level) {
    ActivityLogLevel.ERROR -> Icons.Filled.ErrorOutline
    ActivityLogLevel.WARN -> Icons.Filled.WarningAmber
    ActivityLogLevel.INFO -> Icons.Filled.Info
  }
  val timeFormatted = remember(entry.timestamp) {
    SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
  }
  val hasExpandableDetails = !entry.stackSummary.isNullOrBlank() || !entry.throwableClass.isNullOrBlank()

  Card(
    shape = RoundedCornerShape(10.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (entry.level == ActivityLogLevel.ERROR) {
        ErrorRed.copy(alpha = 0.08f)
      } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
      }
    ),
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 0.6.dp,
        color = accentColor.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp)
      )
      .clip(RoundedCornerShape(10.dp))
      .clickable(enabled = hasExpandableDetails) { onToggleExpand() }
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = levelIcon,
            contentDescription = entry.level.label,
            tint = accentColor,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = entry.level.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accentColor
          )
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              text = entry.category.label,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = timeFormatted,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          if (hasExpandableDetails) {
            Spacer(Modifier.width(4.dp))
            Icon(
              imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
              contentDescription = if (expanded) "Collapse stack trace" else "Expand stack trace",
              modifier = Modifier.size(18.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      Spacer(Modifier.height(6.dp))
      Text(
        text = entry.action,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis
      )

      if (entry.detail.isNotBlank()) {
        Spacer(Modifier.height(3.dp))
        Text(
          text = entry.detail,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (expanded) Int.MAX_VALUE else 3,
          overflow = TextOverflow.Ellipsis
        )
      }

      AnimatedVisibility(visible = expanded && hasExpandableDetails) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
          if (!entry.throwableClass.isNullOrBlank()) {
            Text(
              text = "Exception: ${entry.throwableClass}",
              style = MaterialTheme.typography.labelSmall,
              fontFamily = FontFamily.Monospace,
              color = accentColor
            )
          }
          if (!entry.stackSummary.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(8.dp)
            ) {
              Text(
                text = entry.stackSummary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }
    }
  }
}
