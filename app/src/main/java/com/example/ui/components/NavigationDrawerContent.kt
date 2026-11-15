package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.TorrentCategory

sealed interface DrawerDestination {
  data object Home : DrawerDestination
  data object Favorites : DrawerDestination
  data class Category(val category: TorrentCategory) : DrawerDestination
  data object Sources : DrawerDestination
  data object SearchHistory : DrawerDestination
  data object Settings : DrawerDestination
  data object About : DrawerDestination
}

@Composable
fun NavigationDrawerContent(
  currentRouteKey: String,
  activeCategory: TorrentCategory,
  enabledSourcesCount: Int,
  totalSourcesCount: Int,
  onNavigate: (DrawerDestination) -> Unit
) {
  var otherExpanded by remember { mutableStateOf(false) }

  ModalDrawerSheet(
    modifier = Modifier.width(292.dp),
    drawerContainerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .fillMaxHeight()
        .verticalScroll(rememberScrollState())
    ) {
      // Clean technical header with subtle banner and app icon
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(112.dp)
      ) {
        Image(
          painter = painterResource(id = R.drawable.img_discovery_banner_1794733095940),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.matchParentSize()
        )
        Box(
          modifier = Modifier
            .matchParentSize()
            .background(
              Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.25f), MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
              )
            )
        )
        Row(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Image(
            painter = painterResource(id = R.drawable.ic_launcher_art),
            contentDescription = "Torrent Tracker icon",
            modifier = Modifier
              .size(38.dp)
              .clip(RoundedCornerShape(10.dp))
          )
          Spacer(Modifier.width(12.dp))
          Column {
            Text(
              text = "Torrent Tracker",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "$enabledSourcesCount/$totalSourcesCount sources active",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
          }
        }
      }

      Spacer(Modifier.height(6.dp))

      DrawerNavItem(
        label = "Home",
        icon = Icons.Filled.Home,
        selected = currentRouteKey == "home" && activeCategory == TorrentCategory.ALL,
        testTag = "drawer_home",
        onClick = { onNavigate(DrawerDestination.Home) }
      )
      DrawerNavItem(
        label = "Favorites",
        icon = Icons.Filled.Bookmark,
        selected = currentRouteKey == "favorites",
        testTag = "drawer_favorites",
        onClick = { onNavigate(DrawerDestination.Favorites) }
      )

      HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

      DrawerNavItem(
        label = "Movies",
        icon = Icons.Filled.Movie,
        selected = currentRouteKey == "home" && activeCategory == TorrentCategory.MOVIES,
        testTag = "drawer_movies",
        onClick = { onNavigate(DrawerDestination.Category(TorrentCategory.MOVIES)) }
      )
      DrawerNavItem(
        label = "TV Shows",
        icon = Icons.Filled.LiveTv,
        selected = currentRouteKey == "home" && activeCategory == TorrentCategory.TV_SHOWS,
        testTag = "drawer_tv_shows",
        onClick = { onNavigate(DrawerDestination.Category(TorrentCategory.TV_SHOWS)) }
      )
      DrawerNavItem(
        label = "Anime",
        icon = Icons.Outlined.AutoAwesome,
        selected = currentRouteKey == "home" && activeCategory == TorrentCategory.ANIME,
        testTag = "drawer_anime",
        onClick = { onNavigate(DrawerDestination.Category(TorrentCategory.ANIME)) }
      )

      // Expandable Other Categories section
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 2.dp)
          .clip(RoundedCornerShape(24.dp))
          .clickable { otherExpanded = !otherExpanded }
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .testTag("drawer_other_categories"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Outlined.Category,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
          )
          Spacer(Modifier.width(12.dp))
          Text(
            text = "Other Categories",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Icon(
          imageVector = if (otherExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(18.dp)
        )
      }

      AnimatedVisibility(visible = otherExpanded) {
        Column(modifier = Modifier.padding(start = 16.dp)) {
          val others = listOf(
            TorrentCategory.GAMES to Icons.Outlined.SportsEsports,
            TorrentCategory.SOFTWARE to Icons.Outlined.Terminal,
            TorrentCategory.MUSIC to Icons.Outlined.Album,
            TorrentCategory.BOOKS to Icons.Outlined.Book,
            TorrentCategory.OTHER to Icons.Outlined.FolderZip
          )
          others.forEach { (cat, icon) ->
            DrawerNavItem(
              label = cat.label,
              icon = icon,
              selected = currentRouteKey == "home" && activeCategory == cat,
              testTag = "drawer_cat_${cat.name.lowercase()}",
              onClick = { onNavigate(DrawerDestination.Category(cat)) }
            )
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp, horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)

      DrawerNavItem(
        label = "Torrent Sources",
        icon = Icons.Filled.Dns,
        selected = currentRouteKey == "sources",
        testTag = "drawer_sources",
        onClick = { onNavigate(DrawerDestination.Sources) }
      )
      DrawerNavItem(
        label = "Search History",
        icon = Icons.Filled.History,
        selected = currentRouteKey == "history",
        testTag = "drawer_history",
        onClick = { onNavigate(DrawerDestination.SearchHistory) }
      )
      DrawerNavItem(
        label = "Settings",
        icon = Icons.Filled.Settings,
        selected = currentRouteKey == "settings",
        testTag = "drawer_settings",
        onClick = { onNavigate(DrawerDestination.Settings) }
      )
      DrawerNavItem(
        label = "About",
        icon = Icons.Filled.Info,
        selected = currentRouteKey == "about",
        testTag = "drawer_about",
        onClick = { onNavigate(DrawerDestination.About) }
      )

      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun DrawerNavItem(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  testTag: String,
  onClick: () -> Unit
) {
  NavigationDrawerItem(
    label = { Text(label, style = MaterialTheme.typography.labelLarge) },
    icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
    selected = selected,
    onClick = onClick,
    colors = NavigationDrawerItemDefaults.colors(
      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
      selectedIconColor = MaterialTheme.colorScheme.primary,
      selectedTextColor = MaterialTheme.colorScheme.primary
    ),
    modifier = Modifier
      .padding(horizontal = 12.dp, vertical = 1.dp)
      .height(48.dp)
      .testTag(testTag)
  )
}
