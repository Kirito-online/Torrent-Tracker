package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpFetchResult(
  val requestedUrl: String,
  val finalUrl: String,
  val statusCode: Int?,
  val body: String?,
  val contentType: String?,
  val redirectChain: List<String>,
  val isCloudflareChallenge: Boolean,
  val errorMessage: String?
) {
  val isSuccessful: Boolean
    get() = statusCode != null && statusCode in 200..299 && body != null && !isCloudflareChallenge
}

class TorrentHttpClient(
  val cookieJar: WebViewCookieJar = WebViewCookieJar()
) {
  companion object {
    const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
  }

  private val baseClient: OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .writeTimeout(12, TimeUnit.SECONDS)
    .build()

  suspend fun fetch(
    url: String,
    timeoutSeconds: Int = 12,
    followRedirects: Boolean = true,
    acceptHeader: String = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8"
  ): HttpFetchResult = withContext(Dispatchers.IO) {
    val normalizedUrl = url.trim()
    if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
      return@withContext HttpFetchResult(
        requestedUrl = url,
        finalUrl = url,
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = emptyList(),
        isCloudflareChallenge = false,
        errorMessage = "Invalid URL scheme. Must begin with https:// or http://"
      )
    }

    val client = baseClient.newBuilder()
      .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
      .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
      .followRedirects(followRedirects)
      .followSslRedirects(followRedirects)
      .build()

    val request = Request.Builder()
      .url(normalizedUrl)
      .header("User-Agent", DEFAULT_USER_AGENT)
      .header("Accept", acceptHeader)
      .header("Accept-Language", "en-US,en;q=0.9")
      .get()
      .build()

    try {
      client.newCall(request).execute().use { response ->
        val chain = mutableListOf<String>()
        var prior = response.priorResponse
        while (prior != null) {
          val location = prior.header("Location")
          if (!location.isNullOrBlank()) {
            chain.add(0, "${prior.code} -> $location")
          }
          prior = prior.priorResponse
        }
        val directLocation = response.header("Location")
        if (response.code in 300..399 && !directLocation.isNullOrBlank()) {
          chain.add("${response.code} -> $directLocation")
        }

        val bodyText = response.body?.string()
        val contentType = response.header("Content-Type")
        val cfRay = response.header("cf-ray")
        val cfMitigated = response.header("cf-mitigated")
        val server = response.header("Server").orEmpty()
        val isCf = isCloudflareResponse(
          statusCode = response.code,
          cfMitigated = cfMitigated,
          cfRay = cfRay,
          server = server,
          body = bodyText
        )

        val err = when {
          isCf -> "Cloudflare browser verification required (HTTP ${response.code})"
          !response.isSuccessful && response.code in 300..399 ->
            "Redirected (HTTP ${response.code}) to ${directLocation ?: response.request.url}"
          !response.isSuccessful -> "HTTP ${response.code} ${response.message}".trim()
          bodyText.isNullOrBlank() -> "Empty response body"
          else -> null
        }

        HttpFetchResult(
          requestedUrl = normalizedUrl,
          finalUrl = response.request.url.toString(),
          statusCode = response.code,
          body = bodyText,
          contentType = contentType,
          redirectChain = chain,
          isCloudflareChallenge = isCf,
          errorMessage = err
        )
      }
    } catch (e: IOException) {
      HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = normalizedUrl,
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = emptyList(),
        isCloudflareChallenge = false,
        errorMessage = e.localizedMessage ?: "Network connection failed"
      )
    } catch (e: Exception) {
      HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = normalizedUrl,
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = emptyList(),
        isCloudflareChallenge = false,
        errorMessage = e.localizedMessage ?: "Unexpected request error"
      )
    }
  }

  private fun isCloudflareResponse(
    statusCode: Int,
    cfMitigated: String?,
    cfRay: String?,
    server: String,
    body: String?
  ): Boolean {
    if (cfMitigated?.equals("challenge", ignoreCase = true) == true) return true
    if (statusCode in listOf(403, 429, 503) && (!cfRay.isNullOrBlank() || server.contains("cloudflare", ignoreCase = true))) {
      val snippet = body.orEmpty().take(4096)
      if (snippet.contains("Just a moment", ignoreCase = true) ||
        snippet.contains("cf-browser-verification", ignoreCase = true) ||
        snippet.contains("challenge-platform", ignoreCase = true) ||
        snippet.contains("Attention Required", ignoreCase = true) ||
        snippet.contains("Enable JavaScript and cookies to continue", ignoreCase = true)
      ) {
        return true
      }
    }
    return false
  }
}
