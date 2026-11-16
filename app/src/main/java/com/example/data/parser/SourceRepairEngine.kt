package com.example.data.parser

import com.example.data.network.HttpFetchResult
import com.example.data.network.TorrentHttpClient
import com.example.domain.model.RepairVerificationStage
import com.example.domain.model.SourceDiagnosticReport
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentSource
import com.example.domain.model.VerifiedReplacement
import org.jsoup.Jsoup
import java.net.URI

class SourceRepairEngine(
  private val httpClient: TorrentHttpClient,
  private val adapter: UnifiedSourceAdapter
) {

  suspend fun diagnoseSource(source: TorrentSource, timeoutSeconds: Int = 10): SourceDiagnosticReport {
    val checkedAt = System.currentTimeMillis()
    val testEndpoint = adapter.buildRequestUrl(source, "ubuntu", 1)
    val allowHttp = source.allowCleartextHttp

    // Step 1: Probe endpoint without following redirects to detect 301/302/307/308 explicitly
    val endpointNoRedirect = httpClient.fetch(
      url = testEndpoint,
      timeoutSeconds = timeoutSeconds,
      followRedirects = false,
      allowCleartext = allowHttp
    )

    // Step 2: Probe base domain reachability
    val baseReachableProbe = httpClient.fetch(
      url = source.baseUrl,
      timeoutSeconds = timeoutSeconds,
      followRedirects = true,
      allowCleartext = allowHttp
    )

    val redirectChain = mutableListOf<String>()
    redirectChain.addAll(endpointNoRedirect.redirectChain)

    val isCloudflare = endpointNoRedirect.isCloudflareChallenge || baseReachableProbe.isCloudflareChallenge
    val domainReachable = baseReachableProbe.statusCode != null || endpointNoRedirect.statusCode != null

    // Step 3: Follow redirects on test endpoint to check final destination and parser output
    val fullFetch = httpClient.fetch(
      url = testEndpoint,
      timeoutSeconds = timeoutSeconds,
      followRedirects = true,
      allowCleartext = allowHttp
    )
    if (fullFetch.redirectChain.isNotEmpty()) {
      redirectChain.addAll(fullFetch.redirectChain)
    }

    val fullParsed = if (fullFetch.isSuccessful && fullFetch.body != null) {
      parseForType(source, fullFetch.body, fullFetch.finalUrl, "ubuntu", 1)
    } else null
    val fullStage = classifyStage(fullFetch, fullParsed, source.sourceType)

    val replacementCandidates = mutableListOf<VerifiedReplacement>()

    // Check if an HTTP 301/302 redirect points to a new authoritative URL on same/parent host (preferring HTTPS)
    val redirectLocation = endpointNoRedirect.redirectChain.firstOrNull()?.substringAfter("-> ")?.trim()
    if (endpointNoRedirect.statusCode in 300..399 && !redirectLocation.isNullOrBlank()) {
      val resolvedRedirect = adapter.resolveUrl(testEndpoint, redirectLocation)
      if (resolvedRedirect != null) {
        val preferredRedirect = if (resolvedRedirect.startsWith("http://", true)) {
          val httpsRedirect = resolvedRedirect.replaceFirst("http://", "https://", ignoreCase = true)
          verifyCandidateReplacement(source, httpsRedirect, "Redirect Target (HTTPS Upgrade)", timeoutSeconds)
            ?: verifyCandidateReplacement(source, resolvedRedirect, "Redirect Target (HTTP ${endpointNoRedirect.statusCode})", timeoutSeconds)
        } else {
          verifyCandidateReplacement(source, resolvedRedirect, "Redirect Target (HTTP ${endpointNoRedirect.statusCode})", timeoutSeconds)
        }
        if (preferredRedirect != null) replacementCandidates.add(preferredRedirect)
      }
    }

    // Check HTTPS upgrade if currently using HTTP — actually parse & extract items before marking verified
    if (!source.isHttps) {
      val httpsBase = source.baseUrl.replaceFirst("http://", "https://", ignoreCase = true)
      val httpsFeed = source.feedUrl.replaceFirst("http://", "https://", ignoreCase = true)
      val httpsSearch = source.searchUrlTemplate.replaceFirst("http://", "https://", ignoreCase = true)
      val httpsSource = source.copy(
        baseUrl = httpsBase,
        searchUrlTemplate = httpsSearch,
        feedUrl = httpsFeed,
        allowCleartextHttp = false
      )
      val httpsEndpoint = adapter.buildRequestUrl(httpsSource, "ubuntu", 1)
      val httpsProbe = httpClient.fetch(httpsEndpoint, timeoutSeconds = timeoutSeconds, forceNetwork = true, allowCleartext = false)
      val httpsParsed = if (httpsProbe.isSuccessful && httpsProbe.body != null) {
        parseForType(httpsSource, httpsProbe.body, httpsProbe.finalUrl, "ubuntu", 1)
      } else null
      val httpsStage = classifyStage(httpsProbe, httpsParsed, httpsSource.sourceType)

      if (httpsProbe.statusCode != null && httpsProbe.statusCode in 200..299) {
        val verified = httpsStage == RepairVerificationStage.USABLE
        val count = httpsParsed?.items?.size ?: 0
        replacementCandidates.add(
          VerifiedReplacement(
            label = if (verified) "Verified HTTPS Upgrade" else "Suggested HTTPS Upgrade (${httpsStage.label})",
            replacementBaseUrl = httpsBase,
            replacementSearchTemplate = httpsSearch.ifBlank { null },
            replacementFeedUrl = httpsFeed.ifBlank { null },
            detectedSourceType = source.sourceType,
            verificationSummary = if (verified) {
              "Verified HTTPS endpoint returned HTTP ${httpsProbe.statusCode} and extracted $count entries."
            } else {
              "HTTPS responded HTTP ${httpsProbe.statusCode} (${httpsStage.label}), but extraction reported: ${httpsParsed?.parseError ?: "0 usable items extracted"}."
            },
            isVerified = verified,
            stage = httpsStage,
            reason = if (verified) {
              "Fetched over TLS and extracted $count usable items."
            } else {
              "Reachable over HTTPS, but not yet usable (${httpsStage.label})."
            }
          )
        )
      }
    }

    // Check whether the website moved its RSS/Atom feed or search form on the base page
    if (baseReachableProbe.isSuccessful && !baseReachableProbe.body.isNullOrBlank()) {
      val detectedFeeds = adapter.detectFeedsFromWebsite(
        baseReachableProbe.finalUrl,
        timeoutSeconds,
        allowCleartext = allowHttp
      )
      for (feed in detectedFeeds) {
        if (feed.url != source.feedUrl && isSameOrParentDomain(source.baseUrl, feed.url)) {
          val testFeedSource = source.copy(feedUrl = feed.url, sourceType = feed.type)
          val feedProbe = httpClient.fetch(feed.url, timeoutSeconds = timeoutSeconds, allowCleartext = allowHttp)
          val feedParsed = if (feedProbe.isSuccessful && feedProbe.body != null) {
            parseForType(testFeedSource, feedProbe.body, feedProbe.finalUrl, "", 1)
          } else null
          val stage = classifyStage(feedProbe, feedParsed, feed.type)
          val verified = stage == RepairVerificationStage.USABLE
          val count = feedParsed?.items?.size ?: 0
          if (stage >= RepairVerificationStage.PARSED) {
            replacementCandidates.add(
              VerifiedReplacement(
                label = if (verified) "Verified Feed: ${feed.title}" else "Suggested Feed: ${feed.title} (Empty)",
                replacementBaseUrl = baseReachableProbe.finalUrl,
                replacementSearchTemplate = null,
                replacementFeedUrl = feed.url,
                detectedSourceType = feed.type,
                verificationSummary = if (verified) {
                  "Verified feed (${stage.label}) returned $count parsed items."
                } else {
                  "Feed parsed without errors at ${feed.url}, but contained 0 entries (Not Usable)."
                },
                isVerified = verified,
                stage = stage,
                reason = if (verified) "Fetched and parsed $count feed entries." else "Feed parsed cleanly but returned 0 entries."
              )
            )
          }
        }
      }

      // Check HTML <form> search endpoint on base page if search failed — probe & parse before marking verified
      if (fullStage != RepairVerificationStage.USABLE) {
        val doc = Jsoup.parse(baseReachableProbe.body, baseReachableProbe.finalUrl)
        val searchForm = doc.selectFirst("form[action*=search], form:has(input[type=search]), form:has(input[name=q]), form:has(input[name=s])")
        val action = searchForm?.attr("abs:action")?.ifBlank { baseReachableProbe.finalUrl }
        val inputName = searchForm?.selectFirst("input[type=search], input[name=q], input[name=s], input[name=search]")?.attr("name")
        if (!action.isNullOrBlank() && !inputName.isNullOrBlank() && isSameOrParentDomain(source.baseUrl, action)) {
          val separator = if (action.contains("?")) "&" else "?"
          val candidateTemplate = "$action${separator}$inputName={query}"
          val candidateSource = source.copy(
            baseUrl = baseReachableProbe.finalUrl,
            searchUrlTemplate = candidateTemplate,
            sourceType = SourceType.HTML_SELECTOR
          )
          val formEndpoint = adapter.buildRequestUrl(candidateSource, "ubuntu", 1)
          val formProbe = httpClient.fetch(formEndpoint, timeoutSeconds = timeoutSeconds, allowCleartext = allowHttp)
          val formParsed = if (formProbe.isSuccessful && formProbe.body != null) {
            parseForType(candidateSource, formProbe.body, formProbe.finalUrl, "ubuntu", 1)
          } else null
          val stage = classifyStage(formProbe, formParsed, SourceType.HTML_SELECTOR)
          val verified = stage == RepairVerificationStage.USABLE
          val count = formParsed?.items?.size ?: 0
          val pathLabel = runCatching { URI(action).path.ifBlank { "/" } }.getOrDefault(action)
          replacementCandidates.add(
            VerifiedReplacement(
              label = if (verified) "Verified Search Endpoint" else "Suggested Search Form (${stage.label})",
              replacementBaseUrl = baseReachableProbe.finalUrl,
              replacementSearchTemplate = candidateTemplate,
              replacementFeedUrl = null,
              detectedSourceType = SourceType.HTML_SELECTOR,
              verificationSummary = if (verified) {
                "Verified search form '$inputName' at $pathLabel extracted $count items."
              } else {
                "Found <form> input '$inputName' at $pathLabel (${stage.label}), but test returned: ${formParsed?.parseError ?: formProbe.errorMessage ?: "0 items extracted"}."
              },
              isVerified = verified,
              stage = stage,
              reason = if (verified) {
                "Form endpoint was fetched and extracted usable items."
              } else {
                "Discovered from HTML <form> (${stage.label}); verify selectors and HTTP policy before applying."
              }
            )
          )
        }
      }
    }

    val parsingIssue = fullFetch.statusCode == 200 && fullStage != RepairVerificationStage.USABLE
    var isSpaPage = false

    // If HTML selector parsing failed on an HTTP 200 page, auto-analyze the DOM for repeating containers/selectors
    if (parsingIssue && source.sourceType == SourceType.HTML_SELECTOR && (fullFetch.body != null || baseReachableProbe.body != null)) {
      val endpointBody = fullFetch.body ?: baseReachableProbe.body.orEmpty()
      val analysis = adapter.analyzeHtmlStructure(
        html = endpointBody,
        finalUrl = fullFetch.finalUrl.ifBlank { baseReachableProbe.finalUrl },
        sourceId = source.id,
        sourceName = source.name,
        baseUrl = source.baseUrl
      )
      isSpaPage = analysis.isLikelySpa
      val suggested = analysis.suggestedConfig
      if (suggested != null && analysis.extractedCount > 0) {
        replacementCandidates.add(
          VerifiedReplacement(
            label = "Verified Auto-Detected HTML Selectors",
            replacementBaseUrl = source.baseUrl,
            replacementSearchTemplate = null,
            replacementFeedUrl = null,
            detectedSourceType = SourceType.HTML_SELECTOR,
            replacementHtmlSelectors = suggested,
            verificationSummary = "Matched container '${suggested.itemContainer}' and extracted ${analysis.extractedCount} entries.",
            isVerified = true,
            stage = RepairVerificationStage.USABLE,
            reason = "Tested CSS selectors against live page DOM and parsed ${analysis.extractedCount} distinct items."
          )
        )
      }
    }

    val extractedCount = fullParsed?.items?.size ?: 0
    val issueSummary = when {
      isCloudflare -> "Website is protected by a Cloudflare browser verification challenge (HTTP ${endpointNoRedirect.statusCode ?: fullFetch.statusCode})."
      !domainReachable -> "Domain is unreachable: ${baseReachableProbe.errorMessage ?: endpointNoRedirect.errorMessage ?: fullFetch.errorMessage ?: "Connection timed out"}."
      endpointNoRedirect.statusCode in 300..399 -> "Endpoint returned redirect HTTP ${endpointNoRedirect.statusCode} to ${redirectLocation ?: "another URL"}."
      isSpaPage -> "Website responded HTTP 200 (${fullStage.label}), but listings are rendered via JavaScript (SPA) with 0 static items."
      parsingIssue -> "Stage '${fullStage.label}' (HTTP 200): ${fullParsed?.parseError ?: "0 usable entries extracted"}."
      fullFetch.errorMessage != null -> "Source returned error (${fullStage.label}): ${fullFetch.errorMessage}"
      else -> "Source is Usable (HTTP ${fullFetch.statusCode}) and extracted $extractedCount entries."
    }

    val recommendations = buildList {
      if (!allowHttp && !source.isHttps) {
        add("Unencrypted HTTP is blocked for this source. Prefer HTTPS upgrade below, or enable per-source HTTP in Edit Source if trusted.")
      }
      if (isCloudflare) {
        add("Tap 'Open Verification' to complete the Cloudflare challenge manually in an isolated browser session, then retry.")
      }
      if (!domainReachable) {
        add("Check your network connection or verify whether the website changed its domain.")
        add("Edit the source URL manually if the project migrated to a new official domain.")
      }
      if (endpointNoRedirect.statusCode in 300..399) {
        add("Review and test the redirect target below before applying it.")
      }
      if (isSpaPage) {
        add("Static HTML scraping cannot read JavaScript-rendered listings. Check if the site offers a JSON API or RSS feed URL, or open it in your browser.")
      } else if (parsingIssue) {
        add("Website is reachable, but selector/parser extraction produced 0 usable items. Apply verified selectors below or edit selectors manually.")
      }
      if (!source.previousWorkingConfigJson.isNullOrBlank()) {
        add("You can restore your previous working configuration at any time.")
      }
    }

    return SourceDiagnosticReport(
      source = source,
      checkedAt = checkedAt,
      domainReachable = domainReachable,
      statusCode = fullFetch.statusCode ?: endpointNoRedirect.statusCode,
      finalUrl = fullFetch.finalUrl.ifBlank { baseReachableProbe.finalUrl },
      redirectChain = redirectChain.distinct(),
      isCloudflareChallenge = isCloudflare,
      parsingIssueDetected = parsingIssue,
      isSpaPage = isSpaPage,
      issueSummary = issueSummary,
      recommendedActions = recommendations,
      verifiedReplacements = replacementCandidates
        .distinctBy {
          "${it.replacementBaseUrl}|${it.replacementSearchTemplate}|${it.replacementFeedUrl}|${it.replacementHtmlSelectors?.itemContainer}"
        }
        .sortedByDescending { it.stage.ordinal },
      verificationStage = fullStage,
      testedUrl = testEndpoint,
      extractedCount = extractedCount
    )
  }

  suspend fun testCandidateUrl(url: String, source: TorrentSource, timeoutSeconds: Int = 10): String {
    val testedUrl = url.trim().replace("{query}", "ubuntu").replace("{page}", "1")
    val allowHttp = source.allowCleartextHttp
    val probe = httpClient.fetch(
      url = testedUrl,
      timeoutSeconds = timeoutSeconds,
      forceNetwork = true,
      allowCleartext = allowHttp
    )
    if (probe.isCloudflareChallenge) {
      return "[Reachable · Verification Required] Tested $testedUrl -> HTTP ${probe.statusCode}: Cloudflare challenge active."
    }
    if (!probe.isSuccessful || probe.body == null) {
      val reason = probe.errorMessage ?: "HTTP ${probe.statusCode ?: "N/A"}"
      val stageLabel = if (probe.statusCode != null) RepairVerificationStage.REACHABLE.label else RepairVerificationStage.UNREACHABLE.label
      return "[$stageLabel] Tested $testedUrl -> $reason"
    }

    val candidateSource = when (source.sourceType) {
      SourceType.RSS_ATOM -> source.copy(feedUrl = url.trim())
      else -> source.copy(searchUrlTemplate = url.trim())
    }
    val parsed = parseForType(candidateSource, probe.body, probe.finalUrl, "", 1)
    val stage = classifyStage(probe, parsed, candidateSource.sourceType)
    val finalLabel = probe.finalUrl.ifBlank { testedUrl }

    return when (stage) {
      RepairVerificationStage.USABLE ->
        "[Usable · Verified] Tested $testedUrl -> HTTP ${probe.statusCode} (final: $finalLabel): extracted ${parsed.items.size} entries."
      RepairVerificationStage.PARSED ->
        "[Parsed · Not Usable] Tested $testedUrl -> HTTP ${probe.statusCode} (final: $finalLabel): parser succeeded but extracted 0 entries."
      RepairVerificationStage.RECOGNIZED ->
        "[Recognized · Parse Failed] Tested $testedUrl -> HTTP ${probe.statusCode} (final: $finalLabel): ${parsed.parseError ?: "format recognized, parser failed"}."
      else ->
        "[Reachable · Unrecognized] Tested $testedUrl -> HTTP ${probe.statusCode} (final: $finalLabel): response does not match ${source.sourceType.label} format."
    }
  }

  private suspend fun verifyCandidateReplacement(
    source: TorrentSource,
    candidateUrl: String,
    label: String,
    timeoutSeconds: Int
  ): VerifiedReplacement? {
    val resolved = adapter.resolveUrl(source.baseUrl, candidateUrl) ?: return null
    if (!isSameOrParentDomain(source.baseUrl, resolved)) return null
    val isHttpsCandidate = resolved.startsWith("https://", true)
    val allowHttp = if (isHttpsCandidate) false else source.allowCleartextHttp
    val probe = httpClient.fetch(resolved, timeoutSeconds = timeoutSeconds, allowCleartext = allowHttp)
    if (!probe.isSuccessful || probe.body == null) return null

    val base = runCatching {
      val u = URI(probe.finalUrl)
      "${u.scheme}://${u.host}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrDefault(source.baseUrl)

    val candidateSource = source.copy(
      baseUrl = base,
      searchUrlTemplate = if (source.sourceType != SourceType.RSS_ATOM) resolved else source.searchUrlTemplate,
      feedUrl = if (source.sourceType == SourceType.RSS_ATOM) resolved else source.feedUrl
    )
    val parsed = parseForType(candidateSource, probe.body, probe.finalUrl, "", 1)
    val stage = classifyStage(probe, parsed, candidateSource.sourceType)
    val verified = stage == RepairVerificationStage.USABLE && (isHttpsCandidate || source.allowCleartextHttp)
    val hostLabel = runCatching { URI(probe.finalUrl).host }.getOrNull() ?: base

    return VerifiedReplacement(
      label = if (verified) "Verified $label" else "Suggested $label (${stage.label})",
      replacementBaseUrl = base,
      replacementSearchTemplate = if (source.sourceType != SourceType.RSS_ATOM) resolved else null,
      replacementFeedUrl = if (source.sourceType == SourceType.RSS_ATOM) resolved else null,
      detectedSourceType = source.sourceType,
      verificationSummary = if (verified) {
        "Verified HTTP ${probe.statusCode} from $hostLabel — extracted ${parsed.items.size} items."
      } else {
        "Target $hostLabel responded HTTP ${probe.statusCode} (${stage.label}), but extraction reported: ${parsed.parseError ?: "0 items extracted"}."
      },
      isVerified = verified,
      stage = stage,
      reason = if (verified) {
        "Fetched target URL and confirmed usable torrent item extraction."
      } else {
        "Endpoint reached stage '${stage.label}', but item extraction was not usable."
      }
    )
  }

  private fun parseForType(
    source: TorrentSource,
    body: String,
    finalUrl: String,
    query: String,
    page: Int
  ): SourceParseOutcome = when (source.sourceType) {
    SourceType.RSS_ATOM -> adapter.parseRssOrAtom(source, body, query, page, finalUrl)
    SourceType.JSON_API -> adapter.parseJsonApi(source, body, query, page, finalUrl)
    SourceType.HTML_SELECTOR -> adapter.parseHtmlWithSelectors(source, body, finalUrl, query, page)
  }

  internal fun classifyStage(
    probe: HttpFetchResult,
    parsed: SourceParseOutcome?,
    expectedType: SourceType
  ): RepairVerificationStage {
    if (probe.statusCode == null || !probe.isSuccessful || probe.body.isNullOrBlank()) {
      return if (probe.statusCode != null) RepairVerificationStage.REACHABLE else RepairVerificationStage.UNREACHABLE
    }
    val trimmed = probe.body.trim()
    val looksLikeExpectedFormat = when (expectedType) {
      SourceType.RSS_ATOM -> trimmed.contains("<rss", true) || trimmed.contains("<feed", true) || trimmed.contains("<channel", true)
      SourceType.JSON_API -> trimmed.startsWith("{") || trimmed.startsWith("[")
      SourceType.HTML_SELECTOR -> trimmed.contains("<html", true) || trimmed.contains("<body", true) || trimmed.contains("<a ", true) || trimmed.contains("<tr", true) || trimmed.contains("<div", true)
    }
    if (!looksLikeExpectedFormat) return RepairVerificationStage.REACHABLE
    if (parsed == null || parsed.parseError != null) return RepairVerificationStage.RECOGNIZED
    return if (parsed.items.isNotEmpty()) RepairVerificationStage.USABLE else RepairVerificationStage.PARSED
  }

  private fun isSameOrParentDomain(originalUrl: String, candidateUrl: String): Boolean {
    return runCatching {
      val origHost = URI(originalUrl).host?.lowercase()?.removePrefix("www.") ?: return false
      val candHost = URI(candidateUrl).host?.lowercase()?.removePrefix("www.") ?: return false
      candHost == origHost || candHost.endsWith(".$origHost") || origHost.endsWith(".$candHost")
    }.getOrDefault(false)
  }
}
