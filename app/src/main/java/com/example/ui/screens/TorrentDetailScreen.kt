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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.domain.model.FavoriteCollection
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSourceOffer
import com.example.ui.components.CategoryFallbackIcon
import com.example.ui.components.CompactLabelBadge
import com.example.ui.components.SourceAlternativesBottomSheet
import com.example.ui.components.TorrentIntents
import com.example.ui.theme.LeecherAmber
import com.example.ui.theme.SeederGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailScreen(
  item: TorrentItem?,
  collections: List<FavoriteCollection>,
  onBack: () -> Unit,
  onToggleFavorite: (TorrentItem) -> Unit,
  onMoveToCollection: (String, String?) -> Unit,
  onSelectSourceOffer: (String, TorrentSourceOffer) -> Unit = { _, _ -> },
  onFeedback: (String) -> Unit
) {
  var selectedOffer by remember(item?.id, item?.availableSources) {
    mutableStateOf(item?.allSourceOffers?.firstOrNull())
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Torrent Details", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("detail_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (item != null) {
            val chosen = selectedOffer ?: item.allSourceOffers.first()
            IconButton(
              onClick = {
                val reordered = listOf(chosen) + item.allSourceOffers.filterNot {
                  it.sourceId == chosen.sourceId &&
                    it.sourceUrl.orEmpty() == chosen.sourceUrl.orEmpty() &&
                    it.magnetLink.orEmpty() == chosen.magnetLink.orEmpty()
                }
                val promoted = item.copy(
                  sourceId = chosen.sourceId,
                  sourceName = chosen.sourceName,
                  sourceUrl = chosen.sourceUrl ?: item.sourceUrl,
                  magnetLink = chosen.magnetLink ?: item.magnetLink,
                  torrentFileUrl = chosen.torrentFileUrl ?: item.torrentFileUrl,
                  seeders = chosen.seeders ?: item.seeders,
                  leechers = chosen.leechers ?: item.leechers,
                  fileSizeText = chosen.fileSizeText ?: item.fileSizeText,
                  fileSizeBytes = chosen.fileSizeBytes ?: item.fileSizeBytes,
                  uploadDateText = chosen.uploadDateText ?: item.uploadDateText,
                  uploadTimestamp = chosen.uploadTimestamp ?: item.uploadTimestamp,
                  uploader = chosen.uploader ?: item.uploader,
                  availableSources = reordered
                )
                onToggleFavorite(promoted)
              },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("detail_favorite_button")
            ) {
              Icon(
                imageVector = if (item.isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = if (item.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      )
    }
  ) { innerPadding ->
    if (item == null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
        contentAlignment = Alignment.Center
      ) {
        Text("Torrent entry not found", style = MaterialTheme.typography.bodyMedium)
      }
    } else {
      TorrentDetailContent(
        item = item,
        collections = collections,
        selectedOffer = selectedOffer ?: item.allSourceOffers.first(),
        onSelectOffer = { offer ->
          selectedOffer = offer
          if (item.isFavorite) {
            onSelectSourceOffer(item.id, offer)
          }
        },
        onToggleFavorite = { onToggleFavorite(item) },
        onMoveToCollection = { colId -> onMoveToCollection(item.id, colId) },
        onFeedback = onFeedback,
        modifier = Modifier.padding(innerPadding)
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentDetailContent(
  item: TorrentItem,
  collections: List<FavoriteCollection>,
  selectedOffer: TorrentSourceOffer = item.allSourceOffers.first(),
  onSelectOffer: (TorrentSourceOffer) -> Unit = {},
  onToggleFavorite: () -> Unit,
  onMoveToCollection: (String?) -> Unit,
  onFeedback: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var showCollectionDialog by remember { mutableStateOf(false) }
  var showAllSourcesSheet by remember { mutableStateOf(false) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    // Cover + Title Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.Top
    ) {
      Box(
        modifier = Modifier
          .size(width = 104.dp, height = 148.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
      ) {
        if (!item.coverImageUrl.isNullOrBlank()) {
          SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
              .data(item.coverImageUrl)
              .crossfade(true)
              .build(),
            contentDescription = "${item.title} poster",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { CategoryFallbackIcon(item.category, modifier = Modifier.size(36.dp)) },
            error = { CategoryFallbackIcon(item.category, modifier = Modifier.size(36.dp)) }
          )
        } else {
          CategoryFallbackIcon(item.category, modifier = Modifier.size(36.dp))
        }
      }

      Spacer(Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.title,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          CompactLabelBadge(
            text = item.category.label,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
          )
          item.releaseYear?.let {
            CompactLabelBadge(
              text = it.toString(),
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          item.seasonEpisodeLabel?.let {
            CompactLabelBadge(
              text = it,
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
          }
          item.resolution?.let {
            CompactLabelBadge(
              text = it,
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
          }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          val activeSeeders = selectedOffer.seeders ?: item.seeders
          val activeLeechers = selectedOffer.leechers ?: item.leechers
          val activeSize = selectedOffer.fileSizeText ?: item.fileSizeText
          activeSeeders?.let { s ->
            Column {
              Text("SEEDERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(s.toString(), style = MaterialTheme.typography.titleMedium, color = SeederGreen)
            }
          }
          activeLeechers?.let { l ->
            Column {
              Text("LEECHERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(l.toString(), style = MaterialTheme.typography.titleMedium, color = LeecherAmber)
            }
          }
          activeSize?.let { sz ->
            Column {
              Text("SIZE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(sz, style = MaterialTheme.typography.titleMedium)
            }
          }
        }
      }
    }

    Spacer(Modifier.height(16.dp))

    // Primary Torrent Actions (targets currently selected source offer)
    val activeMagnet = selectedOffer.magnetLink ?: item.magnetLink
    val activeTorrentUrl = selectedOffer.torrentFileUrl ?: item.torrentFileUrl
    val activeSourceUrl = selectedOffer.sourceUrl ?: item.sourceUrl
    val primaryLink = activeMagnet ?: activeTorrentUrl
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Button(
        onClick = {
          TorrentIntents.openMagnetLink(context, activeMagnet, activeTorrentUrl, onFeedback)
        },
        enabled = primaryLink != null,
        modifier = Modifier
          .weight(1f)
          .height(48.dp)
          .testTag("open_magnet_button")
      ) {
        Icon(Icons.Filled.Link, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (!activeMagnet.isNullOrBlank()) "Open Magnet" else "Open .Torrent")
      }

      FilledTonalButton(
        onClick = {
          TorrentIntents.copyToClipboard(context, "Torrent link", primaryLink, onFeedback)
        },
        enabled = primaryLink != null,
        modifier = Modifier
          .height(48.dp)
          .testTag("copy_magnet_button")
      ) {
        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Copy")
      }
    }

    Spacer(Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedButton(
        onClick = {
          TorrentIntents.shareMagnetOrLink(context, item.title, primaryLink, activeSourceUrl)
        },
        modifier = Modifier
          .weight(1f)
          .height(48.dp)
          .testTag("share_magnet_button")
      ) {
        Icon(Icons.Filled.Share, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Share")
      }

      OutlinedButton(
        onClick = { TorrentIntents.openExternalUrl(context, activeSourceUrl, onFeedback) },
        enabled = !activeSourceUrl.isNullOrBlank(),
        modifier = Modifier
          .weight(1f)
          .height(48.dp)
          .testTag("open_source_page_button")
      ) {
        Icon(Icons.Filled.OpenInBrowser, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Source Page")
      }
    }

    if (item.sourceCount > 1) {
      Spacer(Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("Available Sources (${item.sourceCount})", style = MaterialTheme.typography.titleSmall)
        TextButton(
          onClick = { showAllSourcesSheet = true },
          modifier = Modifier.testTag("detail_compare_sources_button")
        ) {
          Text("Compare All")
        }
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        item.allSourceOffers.forEachIndexed { idx, offer ->
          val isChosen = offer == selectedOffer
          val seedLabel = offer.seeders?.let { " · ↑$it" }.orEmpty()
          FilterChip(
            selected = isChosen,
            onClick = { onSelectOffer(offer) },
            label = {
              Text(
                "${offer.sourceName}$seedLabel${if (idx == 0) " ★" else ""}",
                style = MaterialTheme.typography.labelSmall
              )
            },
            modifier = Modifier.testTag("detail_source_chip_${offer.sourceId}")
          )
        }
      }
    }

    if (item.isFavorite && collections.isNotEmpty()) {
      Spacer(Modifier.height(8.dp))
      OutlinedButton(
        onClick = { showCollectionDialog = true },
        modifier = Modifier.fillMaxWidth()
      ) {
        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        val colName = collections.firstOrNull { it.id == item.collectionId }?.name ?: "None (Category only)"
        Text("Collection: $colName")
      }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))

    // Structured Metadata Table (only available fields!)
    Text("Metadata", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    val rows = buildList {
      add("Source" to selectedOffer.sourceName)
      item.videoQuality?.let { add("Video Quality" to it) }
      item.encoding?.let { add("Encoding" to it) }
      item.language?.let { add("Language" to it) }
      item.subtitles?.let { add("Subtitles" to it) }
      (selectedOffer.uploadDateText ?: item.uploadDateText)?.let { add("Uploaded" to it) }
      (selectedOffer.uploader ?: item.uploader)?.let { add("Uploader" to it) }
      item.infoHash?.let { add("Info Hash" to it) }
      activeSourceUrl?.let { add("Source URL" to it) }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      rows.forEachIndexed { idx, (label, value) ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(108.dp)
          )
          Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
          )
        }
        if (idx < rows.lastIndex) {
          HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
    }

    if (item.tags.isNotEmpty()) {
      Spacer(Modifier.height(14.dp))
      Text("Tags", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        item.tags.forEach { tag ->
          CompactLabelBadge(
            text = tag,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
          )
        }
      }
    }

    if (!item.description.isNullOrBlank()) {
      Spacer(Modifier.height(14.dp))
      Text("Description", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(4.dp))
      Text(
        text = item.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    Spacer(Modifier.height(24.dp))
  }

  if (showAllSourcesSheet) {
    SourceAlternativesBottomSheet(
      item = item,
      onSelectAsPrimary = { offer -> onSelectOffer(offer) },
      onFeedback = onFeedback,
      onDismiss = { showAllSourcesSheet = false }
    )
  }

  if (showCollectionDialog) {
    AlertDialog(
      onDismissRequest = { showCollectionDialog = false },
      title = { Text("Assign to Collection") },
      text = {
        Column {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onMoveToCollection(null)
                showCollectionDialog = false
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
                  onMoveToCollection(col.id)
                  showCollectionDialog = false
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
        TextButton(onClick = { showCollectionDialog = false }) { Text("Close") }
      }
    )
  }
}
