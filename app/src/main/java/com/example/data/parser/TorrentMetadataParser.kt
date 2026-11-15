package com.example.data.parser

import com.example.domain.model.TorrentCategory
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

data class ParsedTitleMetadata(
  val releaseYear: Int?,
  val season: Int?,
  val episode: Int?,
  val resolution: String?,
  val videoQuality: String?,
  val encoding: String?,
  val language: String?,
  val subtitles: String?,
  val tags: List<String>
)

object TorrentMetadataParser {
  private val yearRegex = Regex("""\b(19[5-9]\d|20[0-3]\d)\b""")
  private val seasonEpisodeRegex = Regex("""\b[Ss](\d{1,2})[Ee](\d{1,3})\b""")
  private val seasonOnlyRegex = Regex("""\b[Ss](?:eason)?\s*(\d{1,2})\b""")
  private val resolutionRegex = Regex("""\b(480p|576p|720p|1080p|1080i|2160p|4K|8K)\b""", RegexOption.IGNORE_CASE)
  private val qualityRegex = Regex(
    """\b(BluRay|Blu-Ray|BDRip|BRRip|WEB-DL|WEBRip|HDTV|DVDRip|REMUX|HDR10\+?|Dolby\.?Vision|DV|IMAX)\b""",
    RegexOption.IGNORE_CASE
  )
  private val encodingRegex = Regex(
    """\b(x264|x265|H\.?264|H\.?265|HEVC|AV1|VP9|XviD|10bit|AAC|AC3|DTS|DDP5\.1|FLAC|MP3|Opus)\b""",
    RegexOption.IGNORE_CASE
  )
  private val languageRegex = Regex(
    """\b(English|ENG|Multi|Dual[\s.-]?Audio|Japanese|JPN|French|FRE|Spanish|ESP|German|GER|Italian|ITA|Korean|KOR|Russian|RUS|Arabic|ARA)\b""",
    RegexOption.IGNORE_CASE
  )
  private val subtitleRegex = Regex(
    """\b(Subbed|Eng[\s.-]?Sub|Multi[\s.-]?Sub|HardSub|SoftSub|Subtitles|CC)\b""",
    RegexOption.IGNORE_CASE
  )
  private val btihRegex = Regex("""urn:btih:([a-fA-F0-9]{40}|[a-zA-Z2-7]{32})""", RegexOption.IGNORE_CASE)
  private val sizeRegex = Regex("""([\d.,]+)\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB|B)\b""", RegexOption.IGNORE_CASE)

  fun extractTitleMetadata(title: String, extraText: String? = null): ParsedTitleMetadata {
    val combined = if (extraText.isNullOrBlank()) title else "$title $extraText"
    val year = yearRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val seMatch = seasonEpisodeRegex.find(title)
    val season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
      ?: seasonOnlyRegex.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()

    val resRaw = resolutionRegex.find(combined)?.groupValues?.getOrNull(1)?.uppercase()
    val resolution = when (resRaw) {
      "4K" -> "2160p"
      else -> resRaw?.lowercase()?.replace("k", "K")
    }

    val qualities = qualityRegex.findAll(combined).map { it.value }.distinct().toList()
    val encodings = encodingRegex.findAll(combined).map { it.value }.distinct().toList()
    val languages = languageRegex.findAll(combined).map { it.value }.distinct().toList()
    val subs = subtitleRegex.findAll(combined).map { it.value }.distinct().toList()

    val tags = (qualities + encodings).distinct().take(6)

    return ParsedTitleMetadata(
      releaseYear = year,
      season = season,
      episode = episode,
      resolution = resolution,
      videoQuality = qualities.take(2).joinToString(" · ").ifBlank { null },
      encoding = encodings.take(2).joinToString(" · ").ifBlank { null },
      language = languages.take(2).joinToString(", ").ifBlank { null },
      subtitles = subs.take(2).joinToString(", ").ifBlank { null },
      tags = tags
    )
  }

  fun extractInfoHash(magnetOrHash: String?): String? {
    if (magnetOrHash.isNullOrBlank()) return null
    val match = btihRegex.find(magnetOrHash)
    if (match != null) return match.groupValues[1].lowercase()
    val trimmed = magnetOrHash.trim()
    if (trimmed.matches(Regex("^[a-fA-F0-9]{40}$"))) return trimmed.lowercase()
    return null
  }

  fun buildMagnetFromHash(infoHash: String, title: String): String {
    val encodedName = java.net.URLEncoder.encode(title, "UTF-8")
    return "magnet:?xt=urn:btih:$infoHash&dn=$encodedName"
  }

  fun parseFileSizeBytes(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val clean = raw.trim()
    clean.toLongOrNull()?.let { if (it > 0) return it }
    val match = sizeRegex.find(clean) ?: return null
    val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
    val unit = match.groupValues[2].uppercase()
    val multiplier = when (unit) {
      "TB", "TIB" -> 1_099_511_627_776.0
      "GB", "GIB" -> 1_073_741_824.0
      "MB", "MIB" -> 1_048_576.0
      "KB", "KIB" -> 1_024.0
      else -> 1.0
    }
    return (number * multiplier).toLong()
  }

  fun formatBytes(bytes: Long?): String? {
    if (bytes == null || bytes <= 0L) return null
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIdx = 0
    while (value >= 1024.0 && unitIdx < units.lastIndex) {
      value /= 1024.0
      unitIdx++
    }
    return if (unitIdx <= 1) {
      "${value.toInt()} ${units[unitIdx]}"
    } else {
      String.format(Locale.US, "%.2f %s", value, units[unitIdx])
    }
  }

  fun inferCategory(rawCategory: String?, title: String, season: Int?): TorrentCategory {
    val rawCode = rawCategory?.trim()?.toIntOrNull()
    if (rawCode != null) {
      when (rawCode) {
        102, 601 -> return TorrentCategory.BOOKS
        in 100..199 -> return TorrentCategory.MUSIC
        205, 208, 212 -> return TorrentCategory.TV_SHOWS
        in 200..299 -> return TorrentCategory.MOVIES
        in 300..399 -> return TorrentCategory.SOFTWARE
        in 400..499 -> return TorrentCategory.GAMES
      }
    }
    val combined = "${rawCategory.orEmpty()} $title".lowercase()
    return when {
      combined.contains("anime") || combined.contains("fansub") || combined.contains("nyaa") -> TorrentCategory.ANIME
      season != null || combined.contains("tv") || combined.contains("episode") || combined.contains("series") -> TorrentCategory.TV_SHOWS
      combined.contains("movie") || combined.contains("film") || combined.contains("cinema") || combined.contains("movies") -> TorrentCategory.MOVIES
      combined.contains("game") || combined.contains("repack") || combined.contains("iso") && combined.contains("pc") -> TorrentCategory.GAMES
      combined.contains("software") || combined.contains("linux") || combined.contains("ubuntu") || combined.contains("debian") || combined.contains("distro") || combined.contains("app") -> TorrentCategory.SOFTWARE
      combined.contains("audio") || combined.contains("music") || combined.contains("flac") || combined.contains("mp3") || combined.contains("album") -> TorrentCategory.MUSIC
      combined.contains("book") || combined.contains("texts") || combined.contains("epub") || combined.contains("pdf") || combined.contains("audiobook") -> TorrentCategory.BOOKS
      else -> TorrentCategory.OTHER
    }
  }

  fun parseDateToTimestamp(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    trimmed.toLongOrNull()?.let {
      return if (it < 10_000_000_000L) it * 1000L else it
    }
    val formats = listOf(
      "EEE, dd MMM yyyy HH:mm:ss Z",
      "EEE, dd MMM yyyy HH:mm:ss z",
      "yyyy-MM-dd'T'HH:mm:ss'Z'",
      "yyyy-MM-dd'T'HH:mm:ssXXX",
      "yyyy-MM-dd HH:mm:ss",
      "yyyy-MM-dd"
    )
    for (pattern in formats) {
      try {
        val sdf = SimpleDateFormat(pattern, Locale.US).apply {
          timeZone = TimeZone.getTimeZone("UTC")
        }
        val parsed = sdf.parse(trimmed)
        if (parsed != null) return parsed.time
      } catch (_: Exception) {
      }
    }
    return null
  }

  fun formatRelativeDate(timestamp: Long?, fallbackText: String?): String? {
    if (timestamp == null) return fallbackText?.take(16)?.ifBlank { null }
    val diff = System.currentTimeMillis() - timestamp
    if (diff < 0) return fallbackText?.take(10)
    val mins = diff / 60_000
    val hours = mins / 60
    val days = hours / 24
    return when {
      mins < 60 -> "${mins.coerceAtLeast(1)}m ago"
      hours < 24 -> "${hours}h ago"
      days < 30 -> "${days}d ago"
      days < 365 -> "${days / 30}mo ago"
      else -> "${days / 365}y ago"
    }
  }

  fun computeDedupeKey(infoHash: String?, sourceUrl: String?, title: String): String {
    if (!infoHash.isNullOrBlank()) return "btih:${infoHash.lowercase()}"
    val normalizedTitle = title.lowercase()
      .replace(Regex("[^a-z0-9]"), "")
      .take(80)
    if (normalizedTitle.isNotBlank()) return "title:$normalizedTitle"
    val digest = MessageDigest.getInstance("SHA-256")
      .digest((sourceUrl ?: title).toByteArray())
      .joinToString("") { "%02x".format(it) }
      .take(24)
    return "hash:$digest"
  }

  fun computeRelevanceScore(title: String, query: String, seeders: Int?): Int {
    if (query.isBlank()) return (seeders ?: 0).coerceAtMost(10_000)
    val qTokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val lowerTitle = title.lowercase()
    var score = 0
    if (lowerTitle == query.lowercase()) score += 500
    else if (lowerTitle.contains(query.lowercase())) score += 250
    for (token in qTokens) {
      if (lowerTitle.contains(token)) score += 80
    }
    score += ((seeders ?: 0).coerceAtMost(500)) / 5
    return score
  }
}
