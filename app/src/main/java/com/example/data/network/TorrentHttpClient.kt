package com.example.data.network

import com.example.data.logging.ActivityCategory
import com.example.data.logging.ActivityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
  val cookieJar: WebViewCookieJar = WebViewCookieJar(),
  private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
  private val clientCustomizer: ((OkHttpClient.Builder) -> Unit)? = null,
  private val logger: ActivityLogger? = null
) {
  companion object {
    const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_MAX_RESPONSE_BYTES = 4L * 1024L * 1024L // 4 MB safety limit
    const val MAX_REDIRECT_HOPS = 5
  }

  private val baseClient: OkHttpClient = OkHttpClient.Builder()
    .apply { clientCustomizer?.invoke(this) }
    .cookieJar(cookieJar)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(true)
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .writeTimeout(12, TimeUnit.SECONDS)
    .build()

  suspend fun fetch(
    url: String,
    timeoutSeconds: Int = 12,
    followRedirects: Boolean = true,
    acceptHeader: String = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
    forceNetwork: Boolean = false,
    allowCleartext: Boolean = false
  ): HttpFetchResult = withContext(Dispatchers.IO) {
    val normalizedUrl = url.trim()
    if (!normalizedUrl.startsWith("http://", ignoreCase = true) &&
      !normalizedUrl.startsWith("https://", ignoreCase = true)
    ) {
      logger?.warn(ActivityCategory.NETWORK, "Rejected invalid URL scheme", normalizedUrl)
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

    val initialHttpUrl = normalizedUrl.toHttpUrlOrNull()
      ?: run {
        logger?.warn(ActivityCategory.NETWORK, "Rejected malformed URL", normalizedUrl)
        return@withContext HttpFetchResult(
          requestedUrl = url,
          finalUrl = url,
          statusCode = null,
          body = null,
          contentType = null,
          redirectChain = emptyList(),
          isCloudflareChallenge = false,
          errorMessage = "Invalid URL syntax."
        )
      }

    if (!allowCleartext && !initialHttpUrl.isHttps) {
      logger?.warn(
        ActivityCategory.NETWORK,
        "Blocked unencrypted HTTP request",
        "Host: ${initialHttpUrl.host}; per-source cleartext HTTP permission is disabled."
      )
      return@withContext HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = normalizedUrl,
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = emptyList(),
        isCloudflareChallenge = false,
        errorMessage = "Unencrypted HTTP blocked (HTTPS-only by default). Confirm HTTP permission in Edit Source if required."
      )
    }

    val clampedTimeout = timeoutSeconds.coerceIn(3, 60).toLong()
    val client = baseClient.newBuilder()
      .connectTimeout(clampedTimeout, TimeUnit.SECONDS)
      .readTimeout(clampedTimeout, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .build()

    val chain = mutableListOf<String>()
    val visitedUrls = linkedSetOf(initialHttpUrl.toString())
    var currentHttpUrl: HttpUrl = initialHttpUrl

    try {
      var hop = 0
      while (true) {
        val requestBuilder = Request.Builder()
          .url(currentHttpUrl)
          .header("User-Agent", DEFAULT_USER_AGENT)
          .header("Accept", acceptHeader)
          .header("Accept-Language", "en-US,en;q=0.9")
          .get()

        if (forceNetwork) {
          requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
          requestBuilder.header("Pragma", "no-cache")
        }

        val request = requestBuilder.build()
        val response = executeCallCancellable(client.newCall(request))

        var shouldContinueRedirect = false
        var nextHopUrl: HttpUrl? = null
        var earlyReturn: HttpFetchResult? = null

        response.use { res ->
          val finalUrlStr = res.request.url.toString()
          val locationHeader = res.header("Location")?.trim()

          if (res.code in 300..399 && !locationHeader.isNullOrBlank()) {
            chain.add("${res.code} -> $locationHeader")
            val resolvedNext = res.request.url.resolve(locationHeader)

            if (resolvedNext == null) {
              logger?.error(ActivityCategory.NETWORK, "Blocked invalid redirect scheme", "$finalUrlStr -> $locationHeader")
              earlyReturn = HttpFetchResult(
                requestedUrl = normalizedUrl,
                finalUrl = finalUrlStr,
                statusCode = null,
                body = null,
                contentType = null,
                redirectChain = chain,
                isCloudflareChallenge = false,
                errorMessage = "Blocked redirect to invalid or unsupported URL scheme."
              )
            } else if (!allowCleartext && !resolvedNext.isHttps) {
              val downgradeLabel = if (res.request.url.isHttps) {
                "Blocked HTTPS-to-HTTP downgrade redirect to $resolvedNext."
              } else {
                "Blocked redirect to unencrypted HTTP ($resolvedNext)."
              }
              logger?.error(ActivityCategory.NETWORK, "Blocked insecure redirect", "$finalUrlStr -> $resolvedNext")
              earlyReturn = HttpFetchResult(
                requestedUrl = normalizedUrl,
                finalUrl = resolvedNext.toString(),
                statusCode = null,
                body = null,
                contentType = null,
                redirectChain = chain,
                isCloudflareChallenge = false,
                errorMessage = downgradeLabel
              )
            } else if (followRedirects) {
              if (!visitedUrls.add(resolvedNext.toString())) {
                logger?.error(ActivityCategory.NETWORK, "Redirect loop detected", chain.joinToString(" | "))
                earlyReturn = HttpFetchResult(
                  requestedUrl = normalizedUrl,
                  finalUrl = resolvedNext.toString(),
                  statusCode = null,
                  body = null,
                  contentType = null,
                  redirectChain = chain,
                  isCloudflareChallenge = false,
                  errorMessage = "Redirect loop detected."
                )
              } else if (hop >= MAX_REDIRECT_HOPS) {
                logger?.error(ActivityCategory.NETWORK, "Redirect hop limit exceeded", "${chain.size} hops from $normalizedUrl")
                earlyReturn = HttpFetchResult(
                  requestedUrl = normalizedUrl,
                  finalUrl = resolvedNext.toString(),
                  statusCode = null,
                  body = null,
                  contentType = null,
                  redirectChain = chain,
                  isCloudflareChallenge = false,
                  errorMessage = "Too many redirects (limit $MAX_REDIRECT_HOPS)."
                )
              } else {
                shouldContinueRedirect = true
                nextHopUrl = resolvedNext
              }
            }
          }

          if (earlyReturn != null) {
            return@withContext earlyReturn!!
          }
          if (!shouldContinueRedirect) {
            val bodyRead = readBoundedBody(res.body, maxResponseBytes)
            if (bodyRead.exceededLimit) {
              logger?.error(
                ActivityCategory.NETWORK,
                "Response size limit exceeded",
                "$finalUrlStr (HTTP ${res.code}) exceeded ${maxResponseBytes / (1024 * 1024)} MB"
              )
              return@withContext HttpFetchResult(
                requestedUrl = normalizedUrl,
                finalUrl = finalUrlStr,
                statusCode = res.code,
                body = null,
                contentType = res.header("Content-Type"),
                redirectChain = chain,
                isCloudflareChallenge = false,
                errorMessage = "Response exceeded ${maxResponseBytes / (1024 * 1024)} MB safety limit."
              )
            }

            val bodyText = bodyRead.text
            val contentType = res.header("Content-Type")
            val cfRay = res.header("cf-ray")
            val cfMitigated = res.header("cf-mitigated")
            val server = res.header("Server").orEmpty()
            val isCf = isCloudflareResponse(
              statusCode = res.code,
              cfMitigated = cfMitigated,
              cfRay = cfRay,
              server = server,
              body = bodyText
            )

            val err = when {
              isCf -> "Cloudflare browser verification required (HTTP ${res.code})"
              !res.isSuccessful && res.code in 300..399 ->
                "Redirected (HTTP ${res.code}) to ${locationHeader ?: finalUrlStr}"
              !res.isSuccessful -> "HTTP ${res.code} ${res.message}".trim()
              bodyText.isNullOrBlank() -> "Empty response body"
              else -> null
            }

            when {
              isCf -> logger?.warn(ActivityCategory.VERIFICATION, "Cloudflare challenge encountered", "HTTP ${res.code} at $finalUrlStr")
              err != null -> logger?.error(ActivityCategory.NETWORK, "HTTP request failed", "$finalUrlStr -> $err")
              else -> logger?.info(
                ActivityCategory.NETWORK,
                "HTTP ${res.code} ${res.request.url.host}",
                "URL: $finalUrlStr${if (chain.isNotEmpty()) " (redirects: ${chain.size})" else ""}"
              )
            }

            return@withContext HttpFetchResult(
              requestedUrl = normalizedUrl,
              finalUrl = finalUrlStr,
              statusCode = res.code,
              body = bodyText,
              contentType = contentType,
              redirectChain = chain,
              isCloudflareChallenge = isCf,
              errorMessage = err
            )
          }
        }

        currentHttpUrl = nextHopUrl!!
        hop++
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: IOException) {
      logger?.error(
        ActivityCategory.NETWORK,
        "Network I/O error: ${currentHttpUrl.host}",
        "URL: $currentHttpUrl — ${e.localizedMessage ?: "Connection failed"}",
        e
      )
      return@withContext HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = currentHttpUrl.toString(),
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = chain,
        isCloudflareChallenge = false,
        errorMessage = e.localizedMessage ?: "Network connection failed"
      )
    } catch (e: Exception) {
      logger?.error(
        ActivityCategory.NETWORK,
        "Unexpected HTTP client exception",
        "URL: $currentHttpUrl — ${e.localizedMessage ?: "Unexpected error"}",
        e
      )
      return@withContext HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = currentHttpUrl.toString(),
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = chain,
        isCloudflareChallenge = false,
        errorMessage = e.localizedMessage ?: "Unexpected request error"
      )
    }
    @Suppress("UNREACHABLE_CODE")
    HttpFetchResult(
      requestedUrl = normalizedUrl,
      finalUrl = currentHttpUrl.toString(),
      statusCode = null,
      body = null,
      contentType = null,
      redirectChain = chain,
      isCloudflareChallenge = false,
      errorMessage = "Request terminated unexpectedly"
    )
  }

  private suspend fun executeCallCancellable(call: Call): Response =
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { call.cancel() }
      call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
          if (cont.isActive) {
            cont.resume(response)
          } else {
            response.close()
          }
        }
      })
    }

  private data class BoundedBodyResult(
    val text: String?,
    val exceededLimit: Boolean
  )

  private fun readBoundedBody(body: ResponseBody?, limitBytes: Long): BoundedBodyResult {
    if (body == null) return BoundedBodyResult(null, false)
    val contentLength = body.contentLength()
    if (contentLength > limitBytes) {
      return BoundedBodyResult(null, true)
    }
    val charset: Charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val source = body.source()
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var totalRead = 0L
    source.inputStream().use { input ->
      while (true) {
        val read = input.read(chunk)
        if (read == -1) break
        totalRead += read
        if (totalRead > limitBytes) {
          return BoundedBodyResult(null, true)
        }
        buffer.write(chunk, 0, read)
      }
    }
    return BoundedBodyResult(buffer.toString(charset.name()), false)
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
