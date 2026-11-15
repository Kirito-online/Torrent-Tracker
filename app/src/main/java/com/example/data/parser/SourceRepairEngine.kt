package com.example.data.parser

import com.example.data.network.TorrentHttpClient
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

    // Step 1: Probe endpoint without following redirects to detect 301/302/307/308 explicitly
    val endpointNoRedirect = httpClient.fetch(
      url = testEndpoint,
      timeoutSeconds = timeoutSeconds,
      followRedirects = false
    )

    // Step 2: Probe base domain reachability
    val baseReachableProbe = httpClient.fetch(
      url = source.baseUrl,
      timeoutSeconds = timeoutSeconds,
      followRedirects = true
    )

    val redirectChain = mutableListOf<String>()
    redirectChain.addAll(endpointNoRedirect.redirectChain)

    val isCloudflare = endpointNoRedirect.isCloudflareChallenge || baseReachableProbe.isCloudflareChallenge
    val domainReachable = baseReachableProbe.statusCode != null || endpointNoRedirect.statusCode != null

    // Step 3: Follow redirects on test endpoint to check final destination and parser output
    val fullOutcome = adapter.fetchPage(source, "ubuntu", 1, timeoutSeconds)
    if (fullOutcome.redirectChain.isNotEmpty()) {
      redirectChain.addAll(fullOutcome.redirectChain)
    }

    val verifiedReplacements = mutableListOf<VerifiedReplacement>()

    // Check if an HTTP 301/302 redirect points to a new authoritative URL on same host or HTTPS upgrade
    val redirectLocation = endpointNoRedirect.redirectChain.firstOrNull()?.substringAfter("-> ")?.trim()
    if (endpointNoRedirect.statusCode in 300..399 && !redirectLocation.isNullOrBlank()) {
      val verified = verifyCandidateReplacement(
        source = source,
        candidateUrl = redirectLocation,
        label = "Verified Redirect Target (HTTP ${endpointNoRedirect.statusCode})",
        timeoutSeconds = timeoutSeconds
      )
      if (verified != null) verifiedReplacements.add(verified)
    }

    // Check HTTPS upgrade if currently using HTTP
    if (!source.isHttps) {
      val httpsBase = source.baseUrl.replaceFirst("http://", "https://", ignoreCase = true)
      val httpsFeed = source.feedUrl.replaceFirst("http://", "https://", ignoreCase = true)
      val httpsSearch = source.searchUrlTemplate.replaceFirst("http://", "https://", ignoreCase = true)
      val candidateTest = httpsSearch.ifBlank { httpsFeed.ifBlank { httpsBase } }
      val probe = httpClient.fetch(candidateTest.replace("{query}", "ubuntu").replace("{page}", "1"), timeoutSeconds)
      if (probe.isSuccessful) {
        verifiedReplacements.add(
          VerifiedReplacement(
            label = "Encrypted HTTPS Upgrade",
            replacementBaseUrl = httpsBase,
            replacementSearchTemplate = httpsSearch.ifBlank { null },
            replacementFeedUrl = httpsFeed.ifBlank { null },
            detectedSourceType = source.sourceType,
            verificationSummary = "Verified HTTPS endpoint responded with HTTP ${probe.statusCode}."
          )
        )
      }
    }

    // Check whether the website moved its RSS/Atom feed or search form on the base page
    if (baseReachableProbe.isSuccessful && !baseReachableProbe.body.isNullOrBlank()) {
      val detectedFeeds = adapter.detectFeedsFromWebsite(baseReachableProbe.finalUrl, timeoutSeconds)
      for (feed in detectedFeeds) {
        if (feed.url != source.feedUrl && isSameOrParentDomain(source.baseUrl, feed.url)) {
          val testFeedSource = source.copy(feedUrl = feed.url, sourceType = feed.type)
          val feedTest = adapter.fetchPage(testFeedSource, "", 1, timeoutSeconds)
          if (feedTest.errorMessage == null && feedTest.items.isNotEmpty()) {
            verifiedReplacements.add(
              VerifiedReplacement(
                label = "Discovered Feed: ${feed.title}",
                replacementBaseUrl = baseReachableProbe.finalUrl,
                replacementSearchTemplate = null,
                replacementFeedUrl = feed.url,
                detectedSourceType = feed.type,
                verificationSummary = "Verified feed returned ${feedTest.items.size} items."
              )
            )
          }
        }
      }

      // Check HTML <form> search endpoint on base page if search failed
      if (fullOutcome.errorMessage != null) {
        val doc = Jsoup.parse(baseReachableProbe.body, baseReachableProbe.finalUrl)
        val searchForm = doc.selectFirst("form[action*=search], form:has(input[type=search]), form:has(input[name=q]), form:has(input[name=s])")
        val action = searchForm?.attr("abs:action")?.ifBlank { baseReachableProbe.finalUrl }
        val inputName = searchForm?.selectFirst("input[type=search], input[name=q], input[name=s], input[name=search]")?.attr("name")
        if (!action.isNullOrBlank() && !inputName.isNullOrBlank() && isSameOrParentDomain(source.baseUrl, action)) {
          val separator = if (action.contains("?")) "&" else "?"
          val candidateTemplate = "$action${separator}$inputName={query}"
          verifiedReplacements.add(
            VerifiedReplacement(
              label = "Detected On-Site Search Endpoint",
              replacementBaseUrl = baseReachableProbe.finalUrl,
              replacementSearchTemplate = candidateTemplate,
              replacementFeedUrl = null,
              detectedSourceType = SourceType.HTML_SELECTOR,
              verificationSummary = "Found <form> search input '$inputName' at ${URI(action).path}."
            )
          )
        }
      }
    }

    val parsingIssue = fullOutcome.statusCode == 200 && fullOutcome.errorMessage != null
    var isSpaPage = false

    // If HTML selector parsing failed on an HTTP 200 page, auto-analyze the DOM for repeating containers/selectors
    if (parsingIssue && source.sourceType == SourceType.HTML_SELECTOR && baseReachableProbe.isSuccessful && !baseReachableProbe.body.isNullOrBlank()) {
      val endpointBody = httpClient.fetch(testEndpoint, timeoutSeconds).body ?: baseReachableProbe.body
      val analysis = adapter.analyzeHtmlStructure(
        html = endpointBody,
        finalUrl = fullOutcome.finalUrl ?: baseReachableProbe.finalUrl,
        sourceId = source.id,
        sourceName = source.name,
        baseUrl = source.baseUrl
      )
      isSpaPage = analysis.isLikelySpa
      val suggested = analysis.suggestedConfig
      if (suggested != null && analysis.extractedCount > 0) {
        verifiedReplacements.add(
          VerifiedReplacement(
            label = "Auto-Detected HTML Selectors",
            replacementBaseUrl = source.baseUrl,
            replacementSearchTemplate = null,
            replacementFeedUrl = null,
            detectedSourceType = SourceType.HTML_SELECTOR,
            replacementHtmlSelectors = suggested,
            verificationSummary = "Matched container '${suggested.itemContainer}' and extracted ${analysis.extractedCount} entries."
          )
        )
      }
    }

    val issueSummary = when {
      isCloudflare -> "Website is protected by a Cloudflare browser verification challenge (HTTP ${endpointNoRedirect.statusCode ?: fullOutcome.statusCode})."
      !domainReachable -> "Domain is unreachable: ${baseReachableProbe.errorMessage ?: endpointNoRedirect.errorMessage ?: "Connection timed out"}."
      endpointNoRedirect.statusCode in 300..399 -> "Endpoint returned redirect HTTP ${endpointNoRedirect.statusCode} to ${redirectLocation ?: "another URL"}."
      isSpaPage -> "Website responded HTTP 200, but its listings are rendered dynamically via JavaScript (SPA) with no static HTML items or RSS feed."
      parsingIssue -> "Website responded HTTP 200, but parsing failed: ${fullOutcome.errorMessage}"
      fullOutcome.errorMessage != null -> "Source returned error: ${fullOutcome.errorMessage}"
      else -> "Source is reachable and returned ${fullOutcome.items.size} items cleanly."
    }

    val recommendations = buildList {
      if (isCloudflare) {
        add("Tap 'Open Verification' to complete the Cloudflare challenge manually in an isolated browser session, then retry.")
      }
      if (!domainReachable) {
        add("Check your network connection or verify whether the website changed its domain.")
        add("Edit the source URL manually if the project migrated to a new official domain.")
      }
      if (endpointNoRedirect.statusCode in 300..399) {
        add("Review and test the verified redirect URL below before applying it.")
      }
      if (isSpaPage) {
        add("Static HTML scraping cannot read JavaScript-rendered listings. Check if the site offers a JSON API or RSS feed URL, or open it in your browser.")
      } else if (parsingIssue) {
        add("The page layout or selectors do not match. Apply the auto-detected selectors below (if found) or tap 'Edit Selectors' to customize them.")
      }
      if (!source.previousWorkingConfigJson.isNullOrBlank()) {
        add("You can restore your previous working configuration at any time.")
      }
    }

    return SourceDiagnosticReport(
      source = source,
      checkedAt = checkedAt,
      domainReachable = domainReachable,
      statusCode = fullOutcome.statusCode ?: endpointNoRedirect.statusCode,
      finalUrl = fullOutcome.finalUrl ?: baseReachableProbe.finalUrl,
      redirectChain = redirectChain.distinct(),
      isCloudflareChallenge = isCloudflare,
      parsingIssueDetected = parsingIssue,
      isSpaPage = isSpaPage,
      issueSummary = issueSummary,
      recommendedActions = recommendations,
      verifiedReplacements = verifiedReplacements.distinctBy {
        "${it.replacementBaseUrl}|${it.replacementSearchTemplate}|${it.replacementFeedUrl}|${it.replacementHtmlSelectors?.itemContainer}"
      }
    )
  }

  suspend fun testCandidateUrl(url: String, source: TorrentSource, timeoutSeconds: Int = 10): String {
    val probe = httpClient.fetch(url.replace("{query}", "ubuntu").replace("{page}", "1"), timeoutSeconds)
    return when {
      probe.isCloudflareChallenge -> "Cloudflare challenge encountered (HTTP ${probe.statusCode}). Verification needed."
      probe.isSuccessful -> {
        val candidateSource = when (source.sourceType) {
          SourceType.RSS_ATOM -> source.copy(feedUrl = url)
          else -> source.copy(searchUrlTemplate = url)
        }
        val parsed = when (candidateSource.sourceType) {
          SourceType.RSS_ATOM -> adapter.parseRssOrAtom(candidateSource, probe.body.orEmpty(), "")
          SourceType.JSON_API -> adapter.parseJsonApi(candidateSource, probe.body.orEmpty(), "", 1)
          SourceType.HTML_SELECTOR -> adapter.parseHtmlWithSelectors(candidateSource, probe.body.orEmpty(), probe.finalUrl, "")
        }
        if (parsed.parseError != null) {
          "HTTP ${probe.statusCode} OK, but parser reported: ${parsed.parseError}"
        } else {
          "Verified OK (HTTP ${probe.statusCode}) — extracted ${parsed.items.size} entries."
        }
      }
      else -> "Failed: ${probe.errorMessage ?: "HTTP ${probe.statusCode}"}"
    }
  }

  private suspend fun verifyCandidateReplacement(
    source: TorrentSource,
    candidateUrl: String,
    label: String,
    timeoutSeconds: Int
  ): VerifiedReplacement? {
    val resolved = runCatching { URI(source.baseUrl).resolve(candidateUrl).toString() }.getOrNull() ?: return null
    if (!isSameOrParentDomain(source.baseUrl, resolved)) return null
    val probe = httpClient.fetch(resolved, timeoutSeconds = timeoutSeconds)
    if (!probe.isSuccessful) return null
    val base = runCatching {
      val u = URI(probe.finalUrl)
      "${u.scheme}://${u.host}"
    }.getOrDefault(source.baseUrl)
    return VerifiedReplacement(
      label = label,
      replacementBaseUrl = base,
      replacementSearchTemplate = if (source.sourceType != SourceType.RSS_ATOM) resolved else null,
      replacementFeedUrl = if (source.sourceType == SourceType.RSS_ATOM) resolved else null,
      detectedSourceType = source.sourceType,
      verificationSummary = "Verified HTTP ${probe.statusCode} response from ${URI(probe.finalUrl).host}."
    )
  }

  private fun isSameOrParentDomain(originalUrl: String, candidateUrl: String): Boolean {
    return runCatching {
      val origHost = URI(originalUrl).host?.lowercase()?.removePrefix("www.") ?: return false
      val candHost = URI(candidateUrl).host?.lowercase()?.removePrefix("www.") ?: return false
      candHost == origHost || candHost.endsWith(".$origHost") || origHost.endsWith(".$candHost")
    }.getOrDefault(false)
  }
}
