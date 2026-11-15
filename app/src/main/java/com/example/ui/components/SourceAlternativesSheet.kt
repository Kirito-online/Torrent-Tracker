package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSourceOffer
import com.example.ui.theme.LeecherAmber
import com.example.ui.theme.SeederGreen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceAlternativesBottomSheet(
  item: TorrentItem,
  onSelectAsPrimary: ((TorrentSourceOffer) -> Unit)? = null,
  onFeedback: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val context = LocalContext.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val offers = item.allSourceOffers

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
      Text(
        text = "Available Sources (${offers.size})",
        style = MaterialTheme.typography.titleMedium
      )
      Text(
        text = item.title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
      Spacer(Modifier.height(10.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp)
          .testTag("source_alternatives_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        itemsIndexed(offers) { index, offer ->
          val isRecommended = index == 0
          val borderColor = if (isRecommended) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.outlineVariant
          }
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
              .border(0.8.dp, borderColor, RoundedCornerShape(10.dp))
              .padding(10.dp)
              .testTag("source_offer_${offer.sourceId}_$index")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = offer.sourceName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
              )
              if (isRecommended) {
                CompactLabelBadge(
                  text = "Recommended",
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
              }
            }

            Spacer(Modifier.height(4.dp))

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              offer.seeders?.let { seeds ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Filled.ArrowUpward, contentDescription = "Seeders", tint = SeederGreen, modifier = Modifier.size(13.dp))
                  Text(
                    text = seeds.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = SeederGreen
                  )
                }
              }
              offer.leechers?.let { leech ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(Icons.Filled.ArrowDownward, contentDescription = "Leechers", tint = LeecherAmber, modifier = Modifier.size(13.dp))
                  Text(
                    text = leech.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LeecherAmber
                  )
                }
              }
              offer.fileSizeText?.let { size ->
                Text(size, style = MaterialTheme.typography.labelMedium)
              }
              offer.uploadDateText?.let { date ->
                Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              offer.uploader?.let { up ->
                Text("@${up.take(18)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }

            Spacer(Modifier.height(8.dp))

            val primaryLink = offer.magnetLink ?: offer.torrentFileUrl
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Button(
                onClick = {
                  TorrentIntents.openMagnetLink(context, offer.magnetLink, offer.torrentFileUrl, onFeedback)
                },
                enabled = primaryLink != null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(36.dp)
                  .testTag("offer_open_link_${offer.sourceId}")
              ) {
                Icon(Icons.Filled.Link, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                  text = if (offer.hasMagnet) "Magnet" else ".Torrent",
                  style = MaterialTheme.typography.labelSmall
                )
              }

              OutlinedButton(
                onClick = {
                  TorrentIntents.copyToClipboard(context, "Torrent link", primaryLink, onFeedback)
                },
                enabled = primaryLink != null,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
              ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link", modifier = Modifier.size(14.dp))
              }

              OutlinedButton(
                onClick = {
                  TorrentIntents.openExternalUrl(context, offer.sourceUrl, onFeedback)
                },
                enabled = !offer.sourceUrl.isNullOrBlank(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
              ) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = "Open source page", modifier = Modifier.size(14.dp))
              }

              if (onSelectAsPrimary != null) {
                OutlinedButton(
                  onClick = {
                    onSelectAsPrimary(offer)
                    onDismiss()
                  },
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                  modifier = Modifier.height(36.dp)
                ) {
                  Text("Use", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        }
      }
    }
  }
}
