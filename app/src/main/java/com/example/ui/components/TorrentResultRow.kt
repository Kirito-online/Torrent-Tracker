package com.example.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.domain.model.DisplayDensity
import com.example.domain.model.TorrentCategory
import com.example.domain.model.TorrentItem
import com.example.ui.theme.LeecherAmber
import com.example.ui.theme.SeederGreen

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorrentResultRow(
  item: TorrentItem,
  density: DisplayDensity,
  isSelected: Boolean = false,
  onClick: () -> Unit,
  onToggleFavorite: () -> Unit,
  onFeedback: (String) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var showAlternativesSheet by remember { mutableStateOf(false) }
  val isCompact = density == DisplayDensity.COMPACT
  val verticalPadding = if (isCompact) 8.dp else 12.dp
  val coverWidth = if (isCompact) 58.dp else 70.dp
  val coverHeight = if (isCompact) 82.dp else 98.dp
  val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else Color.Transparent

  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(bg)
      .clickable(onClick = onClick)
      .testTag("torrent_item_${item.id}")
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = verticalPadding),
      verticalAlignment = Alignment.Top
    ) {
      // Left cover image / category emblem
      Box(
        modifier = Modifier
          .size(width = coverWidth, height = coverHeight)
          .clip(RoundedCornerShape(6.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
      ) {
        if (!item.coverImageUrl.isNullOrBlank()) {
          SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
              .data(item.coverImageUrl)
              .crossfade(true)
              .size(200)
              .build(),
            contentDescription = "${item.title} cover",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
              CategoryFallbackIcon(item.category)
            },
            error = {
              CategoryFallbackIcon(item.category)
            }
          )
        } else {
          CategoryFallbackIcon(item.category)
        }
      }

      Spacer(modifier = Modifier.width(10.dp))

      // Right torrent metadata & telemetry
      Column(modifier = Modifier.weight(1f)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.Top
        ) {
          Text(
            text = item.title,
            style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
          )

          IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .size(48.dp)
              .testTag("favorite_toggle_${item.id}")
          ) {
            Icon(
              imageVector = if (item.isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
              contentDescription = if (item.isFavorite) "Remove from favorites" else "Add to favorites",
              tint = if (item.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp)
            )
          }
        }

        // Compact metadata badges (hide unavailable fields)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(top = 2.dp)
        ) {
          CompactLabelBadge(
            text = item.category.label,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
          )
          item.releaseYear?.let { year ->
            CompactLabelBadge(
              text = year.toString(),
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          item.seasonEpisodeLabel?.let { se ->
            CompactLabelBadge(
              text = se,
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
          }
          item.resolution?.let { res ->
            CompactLabelBadge(
              text = res,
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
          }
          val qualityEncoding = listOfNotNull(item.videoQuality, item.encoding).joinToString(" · ")
          if (qualityEncoding.isNotBlank()) {
            CompactLabelBadge(
              text = qualityEncoding,
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          item.language?.let { lang ->
            CompactLabelBadge(
              text = lang,
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          item.subtitles?.let {
            CompactLabelBadge(
              text = "SUB",
              icon = Icons.Outlined.ClosedCaption,
              containerColor = MaterialTheme.colorScheme.surfaceVariant,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Spacer(modifier = Modifier.height(5.dp))

        // Telemetry row: size, seeders, leechers, upload date, uploader, source, magnet indicator
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          item.fileSizeText?.let { size ->
            Text(
              text = size,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface
            )
          }
          item.seeders?.let { seeds ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Seeders",
                tint = SeederGreen,
                modifier = Modifier.size(12.dp)
              )
              Text(
                text = seeds.toString(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = SeederGreen
              )
            }
          }
          item.leechers?.let { leech ->
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = "Leechers",
                tint = LeecherAmber,
                modifier = Modifier.size(12.dp)
              )
              Text(
                text = leech.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = LeecherAmber
              )
            }
          }
          item.uploadDateText?.let { date ->
            Text(
              text = date,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          item.uploader?.let { up ->
            Text(
              text = "@${up.take(18)}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Text(
            text = item.sourceName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          if (item.sourceCount > 1) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                .clickable { showAlternativesSheet = true }
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .testTag("sources_badge_${item.id}")
            ) {
              Text(
                text = "+${item.sourceCount - 1} sources",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
              )
            }
          }
          if (item.hasMagnet) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = "Magnet available",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(12.dp)
              )
              Spacer(modifier = Modifier.width(2.dp))
              Text(
                text = "MAGNET",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }
        }
      }
    }

    HorizontalDivider(
      thickness = 0.5.dp,
      color = MaterialTheme.colorScheme.outlineVariant
    )
  }

  if (showAlternativesSheet) {
    SourceAlternativesBottomSheet(
      item = item,
      onFeedback = onFeedback,
      onDismiss = { showAlternativesSheet = false }
    )
  }
}

@Composable
fun CompactLabelBadge(
  text: String,
  containerColor: Color,
  contentColor: Color,
  icon: ImageVector? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(4.dp))
      .background(containerColor)
      .padding(horizontal = 5.dp, vertical = 1.5.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(11.dp)
      )
      Spacer(modifier = Modifier.width(2.dp))
    }
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

@Composable
fun CategoryFallbackIcon(category: TorrentCategory, modifier: Modifier = Modifier) {
  val icon = when (category) {
    TorrentCategory.MOVIES -> Icons.Outlined.Movie
    TorrentCategory.TV_SHOWS -> Icons.Outlined.LiveTv
    TorrentCategory.ANIME -> Icons.Outlined.AutoAwesome
    TorrentCategory.GAMES -> Icons.Outlined.SportsEsports
    TorrentCategory.SOFTWARE -> Icons.Outlined.Terminal
    TorrentCategory.MUSIC -> Icons.Outlined.Album
    TorrentCategory.BOOKS -> Icons.Outlined.Book
    TorrentCategory.OTHER, TorrentCategory.ALL -> Icons.Outlined.FolderZip
  }
  Icon(
    imageVector = icon,
    contentDescription = category.label,
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    modifier = modifier.size(26.dp)
  )
}
