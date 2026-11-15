package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.parser.TorrentMetadataParser
import com.example.domain.model.FileSizeRange
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SortOption
import com.example.domain.model.SourceHealth
import com.example.domain.model.SourceProgressState
import com.example.domain.model.TorrentSource
import com.example.domain.model.UploadDateWindow
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SeederGreen
import com.example.ui.theme.WarningOrange

@Composable
fun SourceStatusIndicatorPill(
  sources: List<TorrentSource>,
  isLoading: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val enabled = sources.filter { it.enabled }
  val healthyCount = enabled.count { it.health == SourceHealth.ONLINE || it.health == SourceHealth.UNKNOWN }
  val errorCount = enabled.count {
    it.health == SourceHealth.OFFLINE ||
      it.health == SourceHealth.PARSE_ERROR ||
      it.health == SourceHealth.REDIRECTED ||
      it.health == SourceHealth.CLOUDFLARE_REQUIRED
  }

  val dotColor = when {
    enabled.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
    isLoading -> MaterialTheme.colorScheme.primary
    errorCount == 0 -> SeederGreen
    healthyCount > 0 -> WarningOrange
    else -> ErrorRed
  }

  val label = when {
    enabled.isEmpty() -> "0 active"
    isLoading -> "Syncing…"
    errorCount > 0 -> "$healthyCount/${enabled.size} ok · $errorCount err"
    else -> "${enabled.size} online"
  }

  Row(
    modifier = modifier
      .clip(RoundedCornerShape(16.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp)
      .testTag("source_status_indicator"),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (isLoading) {
      CircularProgressIndicator(
        modifier = Modifier.size(10.dp),
        strokeWidth = 1.5.dp,
        color = MaterialTheme.colorScheme.primary
      )
    } else {
      Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(dotColor)
      )
    }
    Spacer(Modifier.width(6.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}

@Composable
fun ProgressiveSourceStatusStrip(
  sourceProgress: Map<String, SourceProgressState>,
  sourceNames: Map<String, String>,
  lastRefreshedAt: Long?,
  usingCached: Boolean,
  onManageSources: () -> Unit
) {
  val loadingNames = sourceProgress.entries
    .filter { it.value is SourceProgressState.Loading }
    .mapNotNull { sourceNames[it.key] }

  val failedEntries = sourceProgress.entries
    .mapNotNull { entry ->
      val err = entry.value as? SourceProgressState.Error ?: return@mapNotNull null
      (sourceNames[entry.key] ?: entry.key) to err
    }

  Column(modifier = Modifier.fillMaxWidth()) {
    AnimatedVisibility(visible = loadingNames.isNotEmpty()) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
          .padding(horizontal = 12.dp, vertical = 6.dp)
      ) {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = "Querying: ${loadingNames.joinToString(", ")}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    AnimatedVisibility(visible = failedEntries.isNotEmpty() && loadingNames.isEmpty()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(ErrorRed.copy(alpha = 0.1f))
          .clickable(onClick = onManageSources)
          .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = "Source warnings",
            tint = ErrorRed,
            modifier = Modifier.size(15.dp)
          )
          Spacer(Modifier.width(6.dp))
          val firstErr = failedEntries.first()
          val extra = if (failedEntries.size > 1) " (+${failedEntries.size - 1} more)" else ""
          Text(
            text = "${firstErr.first}: ${firstErr.second.message}$extra",
            style = MaterialTheme.typography.labelSmall,
            color = ErrorRed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Text(
          text = "Inspect",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 8.dp)
        )
      }
    }

    if (lastRefreshedAt != null || usingCached) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        val timeText = lastRefreshedAt?.let {
          "Updated ${TorrentMetadataParser.formatRelativeDate(it, null)}"
        } ?: "Showing cached results"
        Text(
          text = if (usingCached) "Cached offline results · refreshing…" else timeText,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
fun ActiveFilterChipsBar(
  filterState: SearchFilterState,
  sourceNames: Map<String, String>,
  onUpdateFilter: (SearchFilterState) -> Unit,
  onClearAll: () -> Unit
) {
  if (filterState.isDefault) return

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    filterState.selectedCategories.forEach { cat ->
      ActiveChipItem(label = cat.label) {
        onUpdateFilter(filterState.copy(selectedCategories = filterState.selectedCategories - cat))
      }
    }
    filterState.selectedResolutions.forEach { res ->
      ActiveChipItem(label = res) {
        onUpdateFilter(filterState.copy(selectedResolutions = filterState.selectedResolutions - res))
      }
    }
    if (filterState.minSeeders > 0) {
      ActiveChipItem(label = "≥${filterState.minSeeders} seeds") {
        onUpdateFilter(filterState.copy(minSeeders = 0))
      }
    }
    filterState.maxLeechers?.let { maxL ->
      ActiveChipItem(label = "≤$maxL leech") {
        onUpdateFilter(filterState.copy(maxLeechers = null))
      }
    }
    if (filterState.fileSizeRange != FileSizeRange.ANY) {
      ActiveChipItem(label = filterState.fileSizeRange.label) {
        onUpdateFilter(filterState.copy(fileSizeRange = FileSizeRange.ANY))
      }
    }
    if (filterState.languageQuery.isNotBlank()) {
      ActiveChipItem(label = "Lang: ${filterState.languageQuery}") {
        onUpdateFilter(filterState.copy(languageQuery = ""))
      }
    }
    if (filterState.subtitlesOnly) {
      ActiveChipItem(label = "Subtitles") {
        onUpdateFilter(filterState.copy(subtitlesOnly = false))
      }
    }
    if (filterState.uploadDateWindow != UploadDateWindow.ANY) {
      ActiveChipItem(label = filterState.uploadDateWindow.label) {
        onUpdateFilter(filterState.copy(uploadDateWindow = UploadDateWindow.ANY))
      }
    }
    filterState.selectedSourceIds.forEach { srcId ->
      ActiveChipItem(label = sourceNames[srcId] ?: srcId) {
        onUpdateFilter(filterState.copy(selectedSourceIds = filterState.selectedSourceIds - srcId))
      }
    }
    if (filterState.sortOption != SortOption.RELEVANCE) {
      ActiveChipItem(label = "Sort: ${filterState.sortOption.label}") {
        onUpdateFilter(filterState.copy(sortOption = SortOption.RELEVANCE))
      }
    }
    TextButton(
      onClick = onClearAll,
      modifier = Modifier.height(32.dp)
    ) {
      Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
      Spacer(Modifier.width(4.dp))
      Text("Reset", style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun ActiveChipItem(label: String, onRemove: () -> Unit) {
  AssistChip(
    onClick = onRemove,
    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    trailingIcon = {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "Remove $label filter",
        modifier = Modifier.size(14.dp)
      )
    },
    modifier = Modifier.height(30.dp)
  )
}
