package com.example.ui.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.R

object TorrentIntents {

  fun openMagnetLink(
    context: Context,
    magnetLink: String?,
    torrentFileUrl: String? = null,
    onFeedback: (String) -> Unit
  ) {
    val target = magnetLink?.takeIf { it.isNotBlank() } ?: torrentFileUrl?.takeIf { it.isNotBlank() }
    if (target == null) {
      onFeedback("No magnet or torrent link available for this entry")
      return
    }
    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      onFeedback(context.getString(R.string.no_torrent_client_installed))
    } catch (e: Exception) {
      onFeedback("Unable to open link: ${e.localizedMessage ?: "Unknown error"}")
    }
  }

  fun copyToClipboard(
    context: Context,
    label: String,
    text: String?,
    onFeedback: (String) -> Unit
  ) {
    if (text.isNullOrBlank()) {
      onFeedback("Nothing available to copy")
      return
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    onFeedback(
      if (text.startsWith("magnet:", true)) context.getString(R.string.magnet_copied)
      else context.getString(R.string.link_copied)
    )
  }

  fun shareMagnetOrLink(
    context: Context,
    title: String,
    magnetLink: String?,
    sourceUrl: String?
  ) {
    val payload = buildString {
      append(title)
      if (!magnetLink.isNullOrBlank()) {
        append("\n\n")
        append(magnetLink)
      } else if (!sourceUrl.isNullOrBlank()) {
        append("\n\n")
        append(sourceUrl)
      }
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, title)
      putExtra(Intent.EXTRA_TEXT, payload)
    }
    val chooser = Intent.createChooser(sendIntent, "Share torrent").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
  }

  fun openExternalUrl(context: Context, url: String?, onFeedback: (String) -> Unit) {
    if (url.isNullOrBlank()) {
      onFeedback("No original page URL available")
      return
    }
    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
      onFeedback("No browser available to open this URL")
    }
  }
}
