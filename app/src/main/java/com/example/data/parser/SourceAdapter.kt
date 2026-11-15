package com.example.data.parser

import com.example.data.network.TorrentHttpClient
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentItem
import com.example.domain.model.TorrentSource
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder

data class SourceParseOutcome(
  val items: List<TorrentItem>,
  val hasNextPage: Boolean,
  val nextPageUrl: String? = null,
  val parseError: String? = null
)

data class DetectedFeed(
  val url: String,
  val title: String,
  val type: SourceType
)

data class DiscoveredSourceCandidate(
  val source: TorrentSource?,
  val summary: String,
  val statusCode: Int?,
  val isCloudflare: Boolean
)

interface SourceAdapter {
  suspend fun fetchPage(
    source: TorrentSource,
    query: String,
    page: Int,
    timeoutSeconds: Int
  ): AdapterFetchResult
}

data class AdapterFetchResult(
  val items: List<TorrentItem>,
  val hasNextPage: Boolean,
  val statusCode: Int?,
  val finalUrl: String?,
  val redirectChain: List<String>,
  val isCloudflareChallenge: Boolean,
  val errorMessage: String?
)

class UnifiedSourceAdapter(
  private val httpClient: TorrentHttpClient
) : SourceAdapter {

  override suspend fun fetchPage(
    source: TorrentSource,
    query: String,
    page: Int,
    timeoutSeconds: Int
  ): AdapterFetchResult {
    val targetUrl = buildRequestUrl(source, query, page)
    val accept = when (source.sourceType) {
      SourceType.RSS_ATOM -> "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"
      SourceType.JSON_API -> "application/json, text/plain, */*"
      SourceType.HTML_SELECTOR -> "text/html,application/xhtml+xml,*/*"
    }

    val response = httpClient.fetch(
      url = targetUrl,
      timeoutSeconds = timeoutSeconds,
      followRedirects = true,
      acceptHeader = accept
    )

    if (!response.isSuccessful || response.body == null) {
      return AdapterFetchResult(
        items = emptyList(),
        hasNextPage = false,
        statusCode = response.statusCode,
        finalUrl = response.finalUrl,
        redirectChain = response.redirectChain,
        isCloudflareChallenge = response.isCloudflareChallenge,
        errorMessage = response.errorMessage ?: "Failed to fetch source"
      )
    }

    val outcome = when (source.sourceType) {
      SourceType.RSS_ATOM -> parseRssOrAtom(source, response.body, query)
      SourceType.JSON_API -> parseJsonApi(source, response.body, query, page)
      SourceType.HTML_SELECTOR -> parseHtmlWithSelectors(source, response.body, response.finalUrl, query)
    }

    return AdapterFetchResult(
      items = outcome.items,
      hasNextPage = outcome.hasNextPage,
      statusCode = response.statusCode,
      finalUrl = response.finalUrl,
      redirectChain = response.redirectChain,
      isCloudflareChallenge = false,
      errorMessage = outcome.parseError
    )
  }

  fun buildRequestUrl(source: TorrentSource, query: String, page: Int): String {
    val trimmedQuery = query.trim()
    val encodedQuery = URLEncoder.encode(trimmedQuery, "UTF-8")
    if (trimmedQuery.isBlank() && source.feedUrl.isNotBlank()) {
      return source.feedUrl
        .replace("{query}", "")
        .replace("{page}", page.toString())
    }
    val template = source.searchUrlTemplate.ifBlank {
      source.feedUrl.ifBlank { source.baseUrl }
    }
    return template
      .replace("{query}", encodedQuery)
      .replace("{page}", page.toString())
  }

  fun parseRssOrAtom(source: TorrentSource, xml: String, query: String): SourceParseOutcome {
    val items = mutableListOf<TorrentItem>()
    try {
      val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
      val parser = factory.newPullParser()
      parser.setInput(StringReader(xml.trim()))

      var eventType = parser.eventType
      var inItem = false
      var inEntry = false

      var title = ""
      var link = ""
      var guid = ""
      var description = ""
      var pubDate = ""
      var author = ""
      var categoryRaw = ""
      var magnet = ""
      var torrentUrl = ""
      var imageUrl = ""
      var sizeRaw = ""
      var seedersRaw = ""
      var leechersRaw = ""
      var infoHashRaw = ""

      while (eventType != XmlPullParser.END_DOCUMENT) {
        val tagName = parser.name?.lowercase().orEmpty()
        when (eventType) {
          XmlPullParser.START_TAG -> {
            if (tagName == "item") {
              inItem = true
              title = ""; link = ""; guid = ""; description = ""; pubDate = ""
              author = ""; categoryRaw = ""; magnet = ""; torrentUrl = ""
              imageUrl = ""; sizeRaw = ""; seedersRaw = ""; leechersRaw = ""; infoHashRaw = ""
            } else if (tagName == "entry") {
              inEntry = true
              title = ""; link = ""; guid = ""; description = ""; pubDate = ""
              author = ""; categoryRaw = ""; magnet = ""; torrentUrl = ""
              imageUrl = ""; sizeRaw = ""; seedersRaw = ""; leechersRaw = ""; infoHashRaw = ""
            } else if (inItem || inEntry) {
              when {
                tagName == "enclosure" -> {
                  val encUrl = parser.getAttributeValue(null, "url").orEmpty()
                  val encLen = parser.getAttributeValue(null, "length").orEmpty()
                  val encType = parser.getAttributeValue(null, "type").orEmpty()
                  if (encUrl.startsWith("magnet:", ignoreCase = true)) {
                    magnet = encUrl
                  } else if (encType.contains("bittorrent") || encUrl.endsWith(".torrent", true)) {
                    torrentUrl = encUrl
                  } else if (encType.startsWith("image/") && imageUrl.isBlank()) {
                    imageUrl = encUrl
                  }
                  if (sizeRaw.isBlank() && encLen.isNotBlank()) sizeRaw = encLen
                }
                tagName == "link" && inEntry -> {
                  val rel = parser.getAttributeValue(null, "rel").orEmpty()
                  val href = parser.getAttributeValue(null, "href").orEmpty()
                  if (href.startsWith("magnet:", ignoreCase = true)) {
                    magnet = href
                  } else if (rel == "enclosure" || href.endsWith(".torrent", true)) {
                    torrentUrl = href
                  } else if (link.isBlank() && href.isNotBlank()) {
                    link = href
                  }
                }
                tagName == "media:thumbnail" || tagName == "media:content" -> {
                  val mUrl = parser.getAttributeValue(null, "url").orEmpty()
                  if (mUrl.isNotBlank() && imageUrl.isBlank()) imageUrl = mUrl
                }
                tagName == "torznab:attr" || tagName == "newznab:attr" -> {
                  val attrName = parser.getAttributeValue(null, "name").orEmpty().lowercase()
                  val attrVal = parser.getAttributeValue(null, "value").orEmpty()
                  when (attrName) {
                    "seeders" -> seedersRaw = attrVal
                    "leechers", "peers" -> leechersRaw = attrVal
                    "size" -> sizeRaw = attrVal
                    "magneturl" -> magnet = attrVal
                    "infohash" -> infoHashRaw = attrVal
                  }
                }
                tagName == "title" -> title = safeNextText(parser)
                tagName == "link" -> if (link.isBlank()) link = safeNextText(parser)
                tagName == "guid" || tagName == "id" -> guid = safeNextText(parser)
                tagName == "description" || tagName == "summary" || tagName == "content" ->
                  description = safeNextText(parser)
                tagName == "pubdate" || tagName == "updated" || tagName == "published" ->
                  pubDate = safeNextText(parser)
                tagName == "author" || tagName == "dc:creator" -> author = safeNextText(parser)
                tagName == "category" -> {
                  val catAttr = parser.getAttributeValue(null, "term").orEmpty()
                  val catText = safeNextText(parser)
                  if (categoryRaw.isBlank()) categoryRaw = catAttr.ifBlank { catText }
                }
                tagName == "torrent:magneturi" || tagName == "magneturi" -> magnet = safeNextText(parser)
                tagName == "torrent:contentlength" || tagName == "size" || tagName == "nyaa:size" -> sizeRaw = safeNextText(parser)
                tagName == "torrent:seeds" || tagName == "seeders" || tagName == "nyaa:seeders" -> seedersRaw = safeNextText(parser)
                tagName == "torrent:peers" || tagName == "leechers" || tagName == "nyaa:leechers" -> leechersRaw = safeNextText(parser)
                tagName == "torrent:infohash" || tagName == "infohash" || tagName == "nyaa:infohash" -> infoHashRaw = safeNextText(parser)
                tagName == "nyaa:category" -> if (categoryRaw.isBlank()) categoryRaw = safeNextText(parser)
              }
            }
          }
          XmlPullParser.END_TAG -> {
            if ((tagName == "item" && inItem) || (tagName == "entry" && inEntry)) {
              inItem = false
              inEntry = false
              val cleanTitle = Jsoup.parse(title).text().trim()
              if (cleanTitle.isNotBlank()) {
                val plainDesc = Jsoup.parse(description).text().trim()
                if (link.endsWith(".torrent", ignoreCase = true) && torrentUrl.isBlank()) {
                  torrentUrl = link
                  if (guid.startsWith("http://", true) || guid.startsWith("https://", true)) {
                    link = guid
                  }
                }
                if (magnet.isBlank()) {
                  Regex("""magnet:\?xt=urn:btih:[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                    .find("$description $link")?.value?.let { magnet = it }
                }
                if (imageUrl.isBlank() && description.contains("<img", ignoreCase = true)) {
                  imageUrl = Jsoup.parse(description).selectFirst("img")?.attr("abs:src")
                    ?.ifBlank { Jsoup.parse(description).selectFirst("img")?.attr("src").orEmpty() }
                    .orEmpty()
                }
                val infoHash = TorrentMetadataParser.extractInfoHash(magnet.ifBlank { infoHashRaw })
                if (magnet.isBlank() && infoHash != null) {
                  magnet = TorrentMetadataParser.buildMagnetFromHash(infoHash, cleanTitle)
                }
                val sizeBytes = TorrentMetadataParser.parseFileSizeBytes(sizeRaw)
                  ?: TorrentMetadataParser.parseFileSizeBytes(plainDesc)
                val sizeText = TorrentMetadataParser.formatBytes(sizeBytes)
                  ?: sizeRaw.ifBlank { null }
                val seeders = seedersRaw.toIntOrNull()
                  ?: Regex("""seed(?:er)?s?\s*[:\-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(plainDesc)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val leechers = leechersRaw.toIntOrNull()
                  ?: Regex("""leech(?:er)?s?\s*[:\-]?\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(plainDesc)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val meta = TorrentMetadataParser.extractTitleMetadata(cleanTitle, plainDesc)
                val ts = TorrentMetadataParser.parseDateToTimestamp(pubDate)
                val cat = TorrentMetadataParser.inferCategory(categoryRaw, cleanTitle, meta.season)
                val resolvedLink = resolveUrl(source.baseUrl, link.ifBlank { guid })
                val resolvedCover = resolveUrl(source.baseUrl, imageUrl)
                val resolvedTorrent = resolveUrl(source.baseUrl, torrentUrl)
                val id = TorrentMetadataParser.computeDedupeKey(infoHash, resolvedLink, cleanTitle)

                val matchesQuery = query.isBlank() ||
                  cleanTitle.contains(query, ignoreCase = true) ||
                  plainDesc.contains(query, ignoreCase = true) ||
                  query.split(Regex("\\s+")).all { tok ->
                    cleanTitle.contains(tok, ignoreCase = true) || plainDesc.contains(tok, ignoreCase = true)
                  }

                if (matchesQuery) {
                  items.add(
                    TorrentItem(
                      id = id,
                      title = cleanTitle,
                      category = cat,
                      releaseYear = meta.releaseYear,
                      season = meta.season,
                      episode = meta.episode,
                      resolution = meta.resolution,
                      videoQuality = meta.videoQuality,
                      encoding = meta.encoding,
                      fileSizeText = sizeText,
                      fileSizeBytes = sizeBytes,
                      seeders = seeders,
                      leechers = leechers,
                      uploadDateText = TorrentMetadataParser.formatRelativeDate(ts, pubDate),
                      uploadTimestamp = ts,
                      uploader = author.ifBlank { null },
                      sourceId = source.id,
                      sourceName = source.name,
                      sourceUrl = resolvedLink,
                      magnetLink = magnet.ifBlank { null },
                      torrentFileUrl = resolvedTorrent,
                      infoHash = infoHash,
                      coverImageUrl = resolvedCover,
                      language = meta.language,
                      subtitles = meta.subtitles,
                      tags = meta.tags,
                      description = plainDesc.take(600).ifBlank { null },
                      relevanceScore = TorrentMetadataParser.computeRelevanceScore(cleanTitle, query, seeders)
                    )
                  )
                }
              }
            }
          }
        }
        eventType = parser.next()
      }

      if (items.isEmpty() && !xml.contains("<item", true) && !xml.contains("<entry", true)) {
        return SourceParseOutcome(
          items = emptyList(),
          hasNextPage = false,
          parseError = "Response is not a valid RSS/Atom feed (no <item> or <entry> nodes found)."
        )
      }

      return SourceParseOutcome(items = items, hasNextPage = false)
    } catch (e: Exception) {
      return SourceParseOutcome(
        items = emptyList(),
        hasNextPage = false,
        parseError = "RSS/Atom parse error: ${e.localizedMessage ?: "Malformed XML"}"
      )
    }
  }

  private fun safeNextText(parser: XmlPullParser): String {
    return try {
      parser.nextText().orEmpty().trim()
    } catch (_: Exception) {
      ""
    }
  }

  fun parseJsonApi(source: TorrentSource, rawJson: String, query: String, page: Int): SourceParseOutcome {
    val trimmed = rawJson.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
      return SourceParseOutcome(
        items = emptyList(),
        hasNextPage = false,
        parseError = "Expected JSON response, received ${trimmed.take(40)}..."
      )
    }

    return try {
      val cfg = source.jsonMapping
      val array: JSONArray = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
      } else {
        val root = JSONObject(trimmed)
        extractJsonArray(root, cfg.itemsPath)
          ?: extractJsonArray(root, "results")
          ?: extractJsonArray(root, "torrents")
          ?: extractJsonArray(root, "data.movies")
          ?: extractJsonArray(root, "items")
          ?: return SourceParseOutcome(
            items = emptyList(),
            hasNextPage = false,
            parseError = "JSON array not found at path '${cfg.itemsPath}'."
          )
      }

      val items = mutableListOf<TorrentItem>()
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val baseTitle = readJsonString(obj, cfg.titleField)
          ?: readJsonString(obj, "title_long")
          ?: readJsonString(obj, "title")
          ?: readJsonString(obj, "name")
          ?: continue
        val identifier = readJsonString(obj, cfg.identifierField)
          ?: readJsonString(obj, "id")
          ?: readJsonString(obj, "_id")
          ?: ""
        if (identifier == "0" && baseTitle.equals("No results returned", ignoreCase = true)) {
          continue
        }

        // Handle YTS-style nested `torrents` array inside a movie object
        val nestedTorrents = obj.optJSONArray("torrents")
        var bestNested: JSONObject? = null
        if (nestedTorrents != null && nestedTorrents.length() > 0) {
          var bestScore = -1
          for (j in 0 until nestedTorrents.length()) {
            val tObj = nestedTorrents.optJSONObject(j) ?: continue
            val q = tObj.optString("quality")
            val qBonus = when {
              q.contains("1080") -> 10_000
              q.contains("2160") || q.contains("4k", true) -> 9_000
              q.contains("720") -> 8_000
              else -> 1_000
            }
            val s = tObj.optInt("seeds", 0)
            if (qBonus + s > bestScore) {
              bestScore = qBonus + s
              bestNested = tObj
            }
          }
        }

        val nestedQuality = bestNested?.optString("quality")?.takeIf { it.isNotBlank() }
        val nestedType = bestNested?.optString("type")?.takeIf { it.isNotBlank() }
        val title = if (nestedQuality != null && !baseTitle.contains(nestedQuality, ignoreCase = true)) {
          "$baseTitle [$nestedQuality${nestedType?.let { " $it" }.orEmpty()}]"
        } else {
          baseTitle
        }

        val rawMagnet = readJsonString(obj, cfg.magnetField)
          ?: readJsonString(obj, "magnet")
          ?: readJsonString(obj, "magnet_url")
          ?: readJsonString(obj, "magnetUrl")
        val rawHash = readJsonString(obj, cfg.infoHashField)
          ?: bestNested?.optString("hash")?.takeIf { it.isNotBlank() }
          ?: readJsonString(obj, "hash")
          ?: readJsonString(obj, "info_hash")
          ?: readJsonString(obj, "infohash")
        val infoHash = TorrentMetadataParser.extractInfoHash(rawMagnet ?: rawHash)
        val magnet = rawMagnet ?: infoHash?.let { TorrentMetadataParser.buildMagnetFromHash(it, title) }

        val detailsUrl = when {
          cfg.detailsUrlTemplate.isNotBlank() && identifier.isNotBlank() ->
            cfg.detailsUrlTemplate.replace("{id}", identifier)
          else -> resolveUrl(source.baseUrl, readJsonString(obj, "url") ?: readJsonString(obj, "link"))
        }

        val torrentUrl = when {
          cfg.torrentUrlTemplate.isNotBlank() && identifier.isNotBlank() ->
            cfg.torrentUrlTemplate.replace("{id}", identifier)
          else -> resolveUrl(
            source.baseUrl,
            bestNested?.optString("url")?.takeIf { it.isNotBlank() }
              ?: readJsonString(obj, "torrent_url")
              ?: readJsonString(obj, "download_url")
          )
        }

        val coverUrl = when {
          cfg.coverImageTemplate.isNotBlank() && identifier.isNotBlank() ->
            cfg.coverImageTemplate.replace("{id}", identifier)
          else -> resolveUrl(
            source.baseUrl,
            readJsonString(obj, cfg.imageField)
              ?: readJsonString(obj, "medium_cover_image")
              ?: readJsonString(obj, "large_cover_image")
              ?: readJsonString(obj, "large_screenshot")
          )
        }

        val sizeRaw = readJsonString(obj, cfg.sizeField)
          ?: bestNested?.optLong("size_bytes", 0L)?.takeIf { it > 0L }?.toString()
          ?: bestNested?.optString("size")?.takeIf { it.isNotBlank() }
          ?: readJsonString(obj, "size")
        val sizeBytes = TorrentMetadataParser.parseFileSizeBytes(sizeRaw)
        val sizeText = TorrentMetadataParser.formatBytes(sizeBytes) ?: sizeRaw
        val seeders = readJsonInt(obj, cfg.seedersField)
          ?: bestNested?.optInt("seeds", -1)?.takeIf { it >= 0 }
          ?: readJsonInt(obj, "seeds")
          ?: readJsonInt(obj, "seeders")
        val leechers = readJsonInt(obj, cfg.leechersField)
          ?: bestNested?.optInt("peers", -1)?.takeIf { it >= 0 }
          ?: readJsonInt(obj, "peers")
          ?: readJsonInt(obj, "leechers")
        val dateRaw = readJsonString(obj, cfg.dateField)
          ?: bestNested?.optString("date_uploaded_unix")?.takeIf { it.isNotBlank() && it != "0" }
          ?: readJsonString(obj, "date_uploaded")
          ?: readJsonString(obj, "added")
          ?: readJsonString(obj, "imported")
        val ts = TorrentMetadataParser.parseDateToTimestamp(dateRaw)
        val uploader = readJsonString(obj, cfg.uploaderField)
          ?: readJsonString(obj, "username")
        val catRaw = readJsonString(obj, cfg.categoryField)
          ?: readJsonString(obj, "category")
          ?: if (nestedTorrents != null) "movies" else null
        val yearFromJson = readJsonInt(obj, cfg.yearField)
        val langFromJson = readJsonString(obj, cfg.languageField)
        val descFromJson = readJsonString(obj, cfg.descriptionField)

        val meta = TorrentMetadataParser.extractTitleMetadata(title, descFromJson)
        val cat = TorrentMetadataParser.inferCategory(catRaw, title, meta.season)
        val id = TorrentMetadataParser.computeDedupeKey(infoHash, detailsUrl ?: identifier, title)

        items.add(
          TorrentItem(
            id = id,
            title = title,
            category = cat,
            releaseYear = yearFromJson ?: meta.releaseYear,
            season = meta.season,
            episode = meta.episode,
            resolution = meta.resolution ?: nestedQuality,
            videoQuality = meta.videoQuality ?: nestedType,
            encoding = meta.encoding,
            fileSizeText = sizeText,
            fileSizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            uploadDateText = TorrentMetadataParser.formatRelativeDate(ts, dateRaw),
            uploadTimestamp = ts,
            uploader = uploader?.take(60),
            sourceId = source.id,
            sourceName = source.name,
            sourceUrl = detailsUrl,
            magnetLink = magnet,
            torrentFileUrl = torrentUrl,
            infoHash = infoHash,
            coverImageUrl = coverUrl,
            language = langFromJson ?: meta.language,
            subtitles = meta.subtitles,
            tags = meta.tags,
            description = descFromJson?.take(600),
            relevanceScore = TorrentMetadataParser.computeRelevanceScore(title, query, seeders)
          )
        )
      }

      val hasMore = items.size >= 15 && page < 10
      SourceParseOutcome(items = items, hasNextPage = hasMore)
    } catch (e: Exception) {
      SourceParseOutcome(
        items = emptyList(),
        hasNextPage = false,
        parseError = "JSON parse error: ${e.localizedMessage ?: "Invalid JSON structure"}"
      )
    }
  }

  fun parseHtmlWithSelectors(
    source: TorrentSource,
    html: String,
    finalUrl: String,
    query: String
  ): SourceParseOutcome {
    return try {
      val doc = Jsoup.parse(html, finalUrl.ifBlank { source.baseUrl })
      val cfg = source.htmlSelectors
      val containers = doc.select(cfg.itemContainer)
      if (containers.isEmpty()) {
        return SourceParseOutcome(
          items = emptyList(),
          hasNextPage = false,
          parseError = "No elements matched container selector '${cfg.itemContainer}'. Page structure may have changed."
        )
      }

      val items = mutableListOf<TorrentItem>()
      for (row in containers) {
        val titleEl = selectFirstSafe(row, cfg.title)
        val rawTitle = titleEl?.text()?.trim().orEmpty()
          .ifBlank { titleEl?.attr("title")?.trim().orEmpty() }
          .ifBlank { titleEl?.attr("aria-label")?.trim().orEmpty() }
          .ifBlank { titleEl?.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
          .ifBlank { row.selectFirst("h1, h2, h3, h4, .title, .name, img[alt]")?.let { el -> el.text().ifBlank { el.attr("alt") } }?.trim().orEmpty() }
        val title = rawTitle
        if (title.isBlank()) continue

        val detailsEl = selectFirstSafe(row, cfg.detailsLink) ?: titleEl ?: row.selectFirst("a[href]")
        val detailsHref = detailsEl?.attr("abs:href")?.ifBlank { detailsEl.attr("href") }
        val detailsUrl = resolveUrl(source.baseUrl, detailsHref)

        val magnetEl = selectFirstSafe(row, cfg.magnetLink) ?: row.selectFirst("a[href^=magnet:]")
        val magnetHref = magnetEl?.attr("href")?.takeIf { it.startsWith("magnet:", true) }
        val torrentEl = selectFirstSafe(row, cfg.torrentFileLink) ?: row.selectFirst("a[href*=.torrent]")
        val torrentUrl = resolveUrl(
          source.baseUrl,
          torrentEl?.attr("abs:href")?.ifBlank { torrentEl.attr("href") }
        )

        val imgEl = selectFirstSafe(row, cfg.coverImage) ?: row.selectFirst("img")
        val imgSrc = imgEl?.let { el ->
          el.attr("abs:src")
            .ifBlank { el.attr("data-src") }
            .ifBlank { el.attr("data-lazy-src") }
            .ifBlank { el.attr("data-original") }
            .ifBlank { el.attr("src") }
        }
        val coverUrl = resolveUrl(source.baseUrl, imgSrc)

        val rowText = row.text()
        val sizeTextRaw = selectFirstSafe(row, cfg.fileSize)?.text()?.trim()
          ?: Regex("""\b\d+(?:[.,]\d+)?\s*(?:TB|TiB|GB|GiB|MB|MiB)\b""", RegexOption.IGNORE_CASE).find(rowText)?.value
        val sizeBytes = TorrentMetadataParser.parseFileSizeBytes(sizeTextRaw)
        val sizeText = TorrentMetadataParser.formatBytes(sizeBytes) ?: sizeTextRaw?.ifBlank { null }

        val seeders = selectFirstSafe(row, cfg.seeders)?.text()?.replace(",", "")?.trim()?.toIntOrNull()
        val leechers = selectFirstSafe(row, cfg.leechers)?.text()?.replace(",", "")?.trim()?.toIntOrNull()
        val dateRaw = selectFirstSafe(row, cfg.uploadDate)?.let {
          it.attr("datetime").ifBlank { it.text() }
        }?.trim()
        val ts = TorrentMetadataParser.parseDateToTimestamp(dateRaw)
        val uploader = selectFirstSafe(row, cfg.uploader)?.text()?.trim()?.ifBlank { null }
        val catRaw = selectFirstSafe(row, cfg.category)?.text()?.trim()

        val infoHash = TorrentMetadataParser.extractInfoHash(magnetHref)
        val meta = TorrentMetadataParser.extractTitleMetadata(title, row.text().take(200))
        val cat = TorrentMetadataParser.inferCategory(catRaw, title, meta.season)
        val id = TorrentMetadataParser.computeDedupeKey(infoHash, detailsUrl, title)

        items.add(
          TorrentItem(
            id = id,
            title = title,
            category = cat,
            releaseYear = meta.releaseYear,
            season = meta.season,
            episode = meta.episode,
            resolution = meta.resolution,
            videoQuality = meta.videoQuality,
            encoding = meta.encoding,
            fileSizeText = sizeText,
            fileSizeBytes = sizeBytes,
            seeders = seeders,
            leechers = leechers,
            uploadDateText = TorrentMetadataParser.formatRelativeDate(ts, dateRaw),
            uploadTimestamp = ts,
            uploader = uploader,
            sourceId = source.id,
            sourceName = source.name,
            sourceUrl = detailsUrl,
            magnetLink = magnetHref,
            torrentFileUrl = torrentUrl,
            infoHash = infoHash,
            coverImageUrl = coverUrl,
            language = meta.language,
            subtitles = meta.subtitles,
            tags = meta.tags,
            relevanceScore = TorrentMetadataParser.computeRelevanceScore(title, query, seeders)
          )
        )
      }

      val nextEl = if (cfg.nextPageLink.isNotBlank()) doc.selectFirst(cfg.nextPageLink) else null
      val nextHref = nextEl?.attr("abs:href")?.ifBlank { nextEl.attr("href") }

      val parseErr = if (items.isEmpty()) {
        "Container matched ${containers.size} rows, but title selector '${cfg.title}' extracted 0 titles."
      } else null

      SourceParseOutcome(
        items = items,
        hasNextPage = !nextHref.isNullOrBlank(),
        nextPageUrl = nextHref,
        parseError = parseErr
      )
    } catch (e: Exception) {
      SourceParseOutcome(
        items = emptyList(),
        hasNextPage = false,
        parseError = "HTML selector parse error: ${e.localizedMessage ?: "Invalid CSS selector"}"
      )
    }
  }

  suspend fun detectFeedsFromWebsite(websiteUrl: String, timeoutSeconds: Int = 10): List<DetectedFeed> {
    val cleanUrl = websiteUrl.trim().let {
      if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
    }
    val detected = mutableListOf<DetectedFeed>()
    val res = httpClient.fetch(cleanUrl, timeoutSeconds = timeoutSeconds)
    if (res.isSuccessful && !res.body.isNullOrBlank()) {
      val trimmed = res.body.trim()
      if (trimmed.startsWith("<rss", true) || trimmed.startsWith("<feed", true) || trimmed.contains("<rss", true)) {
        detected.add(DetectedFeed(res.finalUrl, "Direct RSS/Atom Feed", SourceType.RSS_ATOM))
        return detected
      }
      val doc = Jsoup.parse(res.body, res.finalUrl)
      doc.select("link[rel=alternate]").forEach { link ->
        val type = link.attr("type").lowercase()
        val href = link.attr("abs:href").ifBlank { resolveUrl(res.finalUrl, link.attr("href")).orEmpty() }
        val title = link.attr("title").ifBlank { "Detected Feed" }
        if (href.isNotBlank() && (type.contains("rss") || type.contains("atom") || type.contains("xml"))) {
          detected.add(DetectedFeed(href, title, SourceType.RSS_ATOM))
        } else if (href.isNotBlank() && type.contains("json")) {
          detected.add(DetectedFeed(href, title, SourceType.JSON_API))
        }
      }
    }
    if (detected.isEmpty()) {
      val base = runCatching {
        val uri = URI(cleanUrl)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
      }.getOrDefault(cleanUrl.trimEnd('/'))
      val candidates = listOf("/rss.xml", "/feed/", "/rss", "/atom.xml", "/rss.php")
      for (path in candidates) {
        val probeUrl = "$base$path"
        val probe = httpClient.fetch(probeUrl, timeoutSeconds = 5)
        val b = probe.body.orEmpty()
        if (probe.isSuccessful && (b.contains("<rss", true) || b.contains("<feed", true))) {
          detected.add(DetectedFeed(probe.finalUrl, "Discovered feed ($path)", SourceType.RSS_ATOM))
          break
        }
      }
    }
    return detected.distinctBy { it.url }
  }

  suspend fun discoverSourceFromUrl(rawInput: String, timeoutSeconds: Int = 10): DiscoveredSourceCandidate {
    val normalizedUrl = rawInput.trim().let {
      if (!it.startsWith("http://", true) && !it.startsWith("https://", true)) "https://$it" else it
    }
    val res = httpClient.fetch(normalizedUrl, timeoutSeconds = timeoutSeconds)
    if (!res.isSuccessful && !res.isCloudflareChallenge) {
      return DiscoveredSourceCandidate(
        source = null,
        summary = res.errorMessage ?: "Unable to reach $normalizedUrl",
        statusCode = res.statusCode,
        isCloudflare = false
      )
    }

    val finalUri = runCatching { URI(res.finalUrl) }.getOrNull()
    val baseUrl = finalUri?.let { "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}" }
      ?: normalizedUrl.trimEnd('/')
    val hostLabel = finalUri?.host?.removePrefix("www.")?.replaceFirstChar { it.uppercaseChar() }
      ?: "Custom Source"

    if (res.isCloudflareChallenge) {
      val candidate = TorrentSource(
        id = "custom_${System.currentTimeMillis()}",
        name = hostLabel,
        baseUrl = baseUrl,
        searchUrlTemplate = normalizedUrl,
        feedUrl = "",
        sourceType = SourceType.HTML_SELECTOR,
        requiresCloudflare = true
      )
      return DiscoveredSourceCandidate(
        source = candidate,
        summary = "Reachable, but protected by Cloudflare verification. Add it and use Open Verification to unlock.",
        statusCode = res.statusCode,
        isCloudflare = true
      )
    }

    val body = res.body.orEmpty()
    val trimmed = body.trim()
    val id = "custom_${System.currentTimeMillis()}"

    // 1. Direct RSS/Atom XML feed
    if (trimmed.startsWith("<rss", true) || trimmed.startsWith("<feed", true) ||
      (trimmed.startsWith("<?xml", true) && (trimmed.contains("<rss", true) || trimmed.contains("<feed", true)))
    ) {
      val docTitle = runCatching { Jsoup.parse(body).selectFirst("channel > title, feed > title")?.text() }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: hostLabel
      val candidate = TorrentSource(
        id = id,
        name = docTitle,
        baseUrl = baseUrl,
        searchUrlTemplate = res.finalUrl,
        feedUrl = res.finalUrl,
        sourceType = SourceType.RSS_ATOM
      )
      return DiscoveredSourceCandidate(
        source = candidate,
        summary = "Detected direct RSS/Atom feed (${res.statusCode ?: 200} OK).",
        statusCode = res.statusCode,
        isCloudflare = false
      )
    }

    // 2. Direct JSON API endpoint
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      val candidate = TorrentSource(
        id = id,
        name = "$hostLabel JSON",
        baseUrl = baseUrl,
        searchUrlTemplate = res.finalUrl,
        feedUrl = res.finalUrl,
        sourceType = SourceType.JSON_API
      )
      return DiscoveredSourceCandidate(
        source = candidate,
        summary = "Detected JSON endpoint (${res.statusCode ?: 200} OK).",
        statusCode = res.statusCode,
        isCloudflare = false
      )
    }

    // 3. HTML Page: check for embedded RSS/Atom feeds and on-site search forms
    val doc = Jsoup.parse(body, res.finalUrl)
    val pageTitle = doc.title().substringBefore(" - ").substringBefore(" | ").trim()
      .takeIf { it.isNotBlank() }?.take(36) ?: hostLabel
    val feeds = detectFeedsFromWebsite(res.finalUrl, timeoutSeconds)
    val primaryFeed = feeds.firstOrNull()

    val searchForm = doc.selectFirst("form[action*=search], form:has(input[type=search]), form:has(input[name=q]), form:has(input[name=s])")
    val formAction = searchForm?.attr("abs:action")?.ifBlank { res.finalUrl }
    val inputName = searchForm?.selectFirst("input[type=search], input[name=q], input[name=s], input[name=search]")?.attr("name")
    val searchTemplate = if (!formAction.isNullOrBlank() && !inputName.isNullOrBlank()) {
      val sep = if (formAction.contains("?")) "&" else "?"
      "$formAction${sep}$inputName={query}"
    } else {
      res.finalUrl
    }

    val magnetCount = doc.select("a[href^=magnet:]").size
    val torrentCount = doc.select("a[href*=.torrent]").size
    val analyzed = analyzeHtmlStructure(body, res.finalUrl, sourceId = id, sourceName = pageTitle, baseUrl = baseUrl)

    return if (primaryFeed != null) {
      val candidate = TorrentSource(
        id = id,
        name = pageTitle,
        baseUrl = baseUrl,
        searchUrlTemplate = searchTemplate,
        feedUrl = primaryFeed.url,
        sourceType = primaryFeed.type
      )
      DiscoveredSourceCandidate(
        source = candidate,
        summary = "Discovered ${primaryFeed.type.label} feed (${primaryFeed.title}) on $hostLabel.",
        statusCode = res.statusCode,
        isCloudflare = false
      )
    } else {
      val candidate = TorrentSource(
        id = id,
        name = pageTitle,
        baseUrl = baseUrl,
        searchUrlTemplate = searchTemplate,
        feedUrl = "",
        sourceType = SourceType.HTML_SELECTOR,
        htmlSelectors = analyzed.suggestedConfig ?: com.example.domain.model.HtmlSelectorConfig()
      )
      val linkHint = when {
        analyzed.extractedCount > 0 -> "Auto-detected '${analyzed.suggestedConfig?.itemContainer}' (${analyzed.extractedCount} items)."
        analyzed.isLikelySpa -> "Page appears JavaScript-rendered (SPA); static HTML extraction found 0 entries."
        magnetCount > 0 -> "Found $magnetCount magnet links on page."
        torrentCount > 0 -> "Found $torrentCount .torrent links on page."
        else -> "Configured HTML selector adapter (customize selectors if needed)."
      }
      DiscoveredSourceCandidate(
        source = candidate,
        summary = "Reachable HTML site (${res.statusCode ?: 200} OK). $linkHint",
        statusCode = res.statusCode,
        isCloudflare = false
      )
    }
  }

  data class HtmlAnalysisResult(
    val suggestedConfig: com.example.domain.model.HtmlSelectorConfig?,
    val extractedCount: Int,
    val isLikelySpa: Boolean
  )

  fun analyzeHtmlStructure(
    html: String,
    finalUrl: String,
    sourceId: String = "probe",
    sourceName: String = "Probe",
    baseUrl: String = finalUrl
  ): HtmlAnalysisResult {
    val doc = Jsoup.parse(html, finalUrl.ifBlank { baseUrl })
    val bodyTextLen = doc.body()?.text()?.trim()?.length ?: 0
    val contentLinks = doc.select("a[href]")
      .filter { a ->
        val h = a.attr("href")
        h.isNotBlank() && !h.startsWith("#") && !h.startsWith("javascript:", true)
      }
    val hasSpaRoot = doc.selectFirst("#root, #app, #__next, app-root") != null
    val isLikelySpa = (hasSpaRoot && contentLinks.size < 8) || (bodyTextLen < 180 && doc.select("script[src]").size >= 2 && contentLinks.size < 6)

    // Candidate container selectors ranked by realistic torrent/media cards
    val candidates = linkedSetOf<String>()

    // 1. Containers wrapping magnet or .torrent links
    doc.select("a[href^=magnet:], a[href*=.torrent]").forEach { link ->
      val parent = link.parents().firstOrNull { p ->
        val tag = p.tagName().lowercase()
        tag in listOf("tr", "li", "article") || (tag == "div" && p.classNames().isNotEmpty())
      }
      if (parent != null) {
        val cls = parent.classNames().firstOrNull { c -> !c.contains(":") && c.length in 2..40 }
        if (cls != null) candidates.add("${parent.tagName()}.$cls") else candidates.add(parent.tagName())
      }
    }

    // 2. Repeating classed cards/rows containing links + images/titles (outside nav/header/footer)
    val classBuckets = linkedMapOf<String, Int>()
    doc.select("div[class], li[class], article[class], section[class]").forEach { el ->
      if (el.parents().any { it.tagName().lowercase() in listOf("nav", "header", "footer") }) return@forEach
      val hasLink = el.selectFirst("a[href]") != null
      val hasVisualOrHeading = el.selectFirst("img, h1, h2, h3, h4, .title, .name") != null
      if (!hasLink || !hasVisualOrHeading) return@forEach
      val textLen = el.text().length
      if (textLen > 600) return@forEach
      el.classNames().firstOrNull { c ->
        c.length in 2..36 && !c.startsWith("col-") && !c.startsWith("row") && !c.startsWith("flex") && !c.startsWith("grid") && !c.contains(":")
      }?.let { cls ->
        val sel = "${el.tagName()}.$cls"
        classBuckets[sel] = (classBuckets[sel] ?: 0) + 1
      }
    }
    classBuckets.entries
      .filter { it.value >= 3 }
      .sortedByDescending { it.value }
      .take(6)
      .forEach { candidates.add(it.key) }

    // 3. Fallback common media/torrent patterns
    candidates.addAll(
      listOf(
        "div[class*=movie]",
        "div[class*=torrent]",
        "div[class*=card]",
        "div[class*=box]",
        "div[class*=post]",
        "div[class*=result]",
        "ul li:has(a[href])"
      )
    )

    var bestCfg: com.example.domain.model.HtmlSelectorConfig? = null
    var bestCount = 0

    for (containerSel in candidates) {
      val matched = runCatching { doc.select(containerSel) }.getOrNull() ?: continue
      if (matched.size < 2) continue
      val hasNestedContainers = matched.any { parent ->
        matched.any { child -> child !== parent && child.parents().contains(parent) }
      }
      if (hasNestedContainers) continue

      val sampleCfg = com.example.domain.model.HtmlSelectorConfig(
        itemContainer = containerSel,
        title = "h1, h2, h3, h4, .title, .name, a[title], a",
        detailsLink = "a[href]",
        magnetLink = "a[href^=magnet:]",
        torrentFileLink = "a[href*=.torrent]",
        coverImage = "img",
        fileSize = ".size, [class*=size], td:contains(GB), td:contains(MB)",
        seeders = ".seeders, .seeds, [class*=seed]",
        leechers = ".leechers, .leeches, [class*=leech]",
        uploadDate = "time, .date, [class*=date]",
        uploader = ".uploader, .author"
      )
      val testSource = TorrentSource(
        id = sourceId,
        name = sourceName,
        baseUrl = baseUrl,
        searchUrlTemplate = finalUrl,
        sourceType = SourceType.HTML_SELECTOR,
        htmlSelectors = sampleCfg
      )
      val outcome = parseHtmlWithSelectors(testSource, html, finalUrl, "")
      val uniqueCount = outcome.items.distinctBy { it.id }.size
      if (outcome.parseError == null && uniqueCount > bestCount) {
        bestCount = uniqueCount
        bestCfg = sampleCfg
      }
    }

    return HtmlAnalysisResult(
      suggestedConfig = bestCfg,
      extractedCount = bestCount,
      isLikelySpa = isLikelySpa && bestCount == 0
    )
  }

  private fun selectFirstSafe(element: Element, selector: String): Element? {
    if (selector.isBlank()) return null
    return runCatching { element.selectFirst(selector) }.getOrNull()
  }

  private fun extractJsonArray(root: JSONObject, dotPath: String): JSONArray? {
    if (dotPath.isBlank()) return null
    val parts = dotPath.split(".").filter { it.isNotBlank() }
    var current: Any? = root
    for (i in parts.indices) {
      val key = parts[i]
      if (current is JSONObject && current.has(key)) {
        current = current.get(key)
      } else {
        return null
      }
    }
    return current as? JSONArray
  }

  private fun resolveJsonValue(obj: JSONObject, field: String): Any? {
    if (field.isBlank()) return null
    if (!field.contains(".")) {
      return if (obj.has(field) && !obj.isNull(field)) obj.opt(field) else null
    }
    val parts = field.split(".").filter { it.isNotBlank() }
    var current: Any? = obj
    for (part in parts) {
      current = if (current is JSONObject && current.has(part) && !current.isNull(part)) {
        current.opt(part)
      } else {
        return null
      }
    }
    return current
  }

  private fun readJsonString(obj: JSONObject, field: String): String? {
    val raw = resolveJsonValue(obj, field) ?: return null
    return when (raw) {
      is JSONArray -> if (raw.length() > 0) raw.optString(0).takeIf { it.isNotBlank() } else null
      else -> raw.toString().takeIf { it.isNotBlank() && it != "null" }
    }
  }

  private fun readJsonInt(obj: JSONObject, field: String): Int? {
    val raw = resolveJsonValue(obj, field) ?: return null
    return raw.toString().replace(",", "").toDoubleOrNull()?.toInt()
  }

  private fun resolveUrl(base: String, raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) || trimmed.startsWith("magnet:", true)) {
      return trimmed
    }
    return runCatching {
      URI(base).resolve(trimmed).toString()
    }.getOrNull()
  }
}
