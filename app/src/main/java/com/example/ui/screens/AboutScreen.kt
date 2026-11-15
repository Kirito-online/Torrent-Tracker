package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("About", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("about_back_button")
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
        .padding(16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
          painter = painterResource(id = R.drawable.ic_launcher_art),
          contentDescription = "Torrent Tracker logo",
          modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column {
          Text("Torrent Tracker", style = MaterialTheme.typography.headlineSmall)
          Text(
            "Version ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }

      Spacer(Modifier.height(16.dp))
      Text(
        "Torrent Tracker is a native Android discovery and organization utility for searching across user-configured RSS/Atom feeds, JSON APIs, and HTML torrent sources, inspecting full metadata, managing categorized favorites offline, and handing magnet links to your preferred torrent client.",
        style = MaterialTheme.typography.bodyMedium
      )

      Spacer(Modifier.height(16.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(14.dp))

      Text("Privacy & Security Architecture", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(6.dp))
      val privacyPoints = listOf(
        "Zero accounts & zero cloud tracking: all sources, favorites, collections, search history, and preferences stay locally on your device in Room & DataStore.",
        "HTTPS-first networking: warns before connecting to unencrypted HTTP sources and never sends requests through unauthorized third-party proxies.",
        "Isolated verification: manual Cloudflare verification runs inside a scoped WebView session without credential harvesting or automated CAPTCHA bypassing.",
        "No arbitrary script execution: HTML sources are parsed safely as static DOM documents using Jsoup."
      )
      privacyPoints.forEach { point ->
        Text("• $point", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 3.dp))
      }

      Spacer(Modifier.height(16.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(14.dp))

      Text("Open-Source Licenses", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))

      val licenses = listOf(
        "Jetpack Compose & Material 3" to "Apache License 2.0 — Google / AOSP",
        "AndroidX Room & DataStore" to "Apache License 2.0 — Google / AOSP",
        "Kotlin Coroutines & Serialization" to "Apache License 2.0 — JetBrains",
        "OkHttp" to "Apache License 2.0 — Square, Inc.",
        "Jsoup HTML Parser" to "MIT License — Jonathan Hedley",
        "Coil Image Loader" to "Apache License 2.0 — Coil Contributors"
      )

      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(12.dp)
      ) {
        licenses.forEachIndexed { idx, (lib, lic) ->
          Text(lib, style = MaterialTheme.typography.titleSmall)
          Text(lic, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (idx < licenses.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
          }
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }
}
