package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.FileSizeRange
import com.example.domain.model.SearchFilterState
import com.example.domain.model.SortOption
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentSource
import com.example.domain.model.UploadDateWindow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
  currentFilter: SearchFilterState,
  availableSources: List<TorrentSource>,
  onApply: (SearchFilterState) -> Unit,
  onClearAll: () -> Unit,
  onDismiss: () -> Unit
) {
  var draft by remember(currentFilter) { mutableStateOf(currentFilter) }
  var maxLeechersInput by remember(currentFilter) {
    mutableStateOf(currentFilter.maxLeechers?.toString().orEmpty())
  }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Filter & Sort", style = MaterialTheme.typography.titleLarge)
        TextButton(
          onClick = {
            draft = SearchFilterState()
            maxLeechersInput = ""
            onClearAll()
            onDismiss()
          },
          modifier = Modifier.testTag("filter_clear_all_button")
        ) {
          Text("Clear all")
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(10.dp))

      // Sorting
      SectionTitle("Sort By")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SortOption.entries.forEach { option ->
          FilterChip(
            selected = draft.sortOption == option,
            onClick = { draft = draft.copy(sortOption = option) },
            label = { Text(option.label, style = MaterialTheme.typography.bodySmall) }
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // Content types
      SectionTitle("Content Type")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TorrentCategory.selectableCategories().forEach { cat ->
          val selected = draft.selectedCategories.contains(cat)
          FilterChip(
            selected = selected,
            onClick = {
              val next = if (selected) draft.selectedCategories - cat else draft.selectedCategories + cat
              draft = draft.copy(selectedCategories = next)
            },
            label = { Text(cat.label, style = MaterialTheme.typography.bodySmall) }
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // Resolution
      SectionTitle("Resolution")
      val resolutions = listOf("480p", "720p", "1080p", "2160p")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        resolutions.forEach { res ->
          val selected = draft.selectedResolutions.contains(res)
          FilterChip(
            selected = selected,
            onClick = {
              val next = if (selected) draft.selectedResolutions - res else draft.selectedResolutions + res
              draft = draft.copy(selectedResolutions = next)
            },
            label = { Text(res, style = MaterialTheme.typography.bodySmall) }
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // File size
      SectionTitle("File Size")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FileSizeRange.entries.forEach { range ->
          FilterChip(
            selected = draft.fileSizeRange == range,
            onClick = { draft = draft.copy(fileSizeRange = range) },
            label = { Text(range.label, style = MaterialTheme.typography.bodySmall) }
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // Upload date
      SectionTitle("Upload Date")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        UploadDateWindow.entries.forEach { win ->
          FilterChip(
            selected = draft.uploadDateWindow == win,
            onClick = { draft = draft.copy(uploadDateWindow = win) },
            label = { Text(win.label, style = MaterialTheme.typography.bodySmall) }
          )
        }
      }

      Spacer(Modifier.height(12.dp))

      // Minimum seeders
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        SectionTitle("Minimum Seeders")
        Text(
          text = if (draft.minSeeders == 0) "Any" else "${draft.minSeeders}+",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary
        )
      }
      Slider(
        value = draft.minSeeders.toFloat(),
        onValueChange = { draft = draft.copy(minSeeders = it.toInt()) },
        valueRange = 0f..100f,
        steps = 9
      )

      // Max leechers & language
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        OutlinedTextField(
          value = maxLeechersInput,
          onValueChange = { txt ->
            val digits = txt.filter { it.isDigit() }
            maxLeechersInput = digits
            draft = draft.copy(maxLeechers = digits.toIntOrNull())
          },
          label = { Text("Max leechers") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
          value = draft.languageQuery,
          onValueChange = { draft = draft.copy(languageQuery = it) },
          label = { Text("Language (e.g. English)") },
          singleLine = true,
          modifier = Modifier.weight(1f)
        )
      }

      Spacer(Modifier.height(8.dp))

      // Subtitles toggle
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Subtitles required", style = MaterialTheme.typography.bodyMedium)
        Switch(
          checked = draft.subtitlesOnly,
          onCheckedChange = { draft = draft.copy(subtitlesOnly = it) }
        )
      }

      Spacer(Modifier.height(10.dp))

      // Source websites
      if (availableSources.isNotEmpty()) {
        SectionTitle("Source Websites")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          availableSources.forEach { src ->
            val selected = draft.selectedSourceIds.contains(src.id)
            FilterChip(
              selected = selected,
              onClick = {
                val next = if (selected) draft.selectedSourceIds - src.id else draft.selectedSourceIds + src.id
                draft = draft.copy(selectedSourceIds = next)
              },
              label = { Text(src.name, style = MaterialTheme.typography.bodySmall) }
            )
          }
        }
      }

      Spacer(Modifier.height(16.dp))

      Button(
        onClick = {
          onApply(draft)
          onDismiss()
        },
        modifier = Modifier
          .fillMaxWidth()
          .testTag("filter_apply_button")
      ) {
        Text("Apply Filters")
      }

      Spacer(Modifier.height(20.dp))
    }
  }
}

@Composable
private fun SectionTitle(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(bottom = 4.dp)
  )
}
