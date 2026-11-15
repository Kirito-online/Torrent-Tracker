package com.example.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
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
  private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES
) {
  companion object {
    const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_MAX_RESPONSE_BYTES = 4L * 1024L * 1024L // 4 MB safety limit
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
    acceptHeader: String = "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
    forceNetwork: Boolean = false,
    allowCleartext: Boolean = true
  ): HttpFetchResult = withContext(Dispatchers.IO) {
    val normalizedUrl = url.trim()
    if (!normalizedUrl.startsWith("http://", ignoreCase = true) &&
      !normalizedUrl.startsWith("https://", ignoreCase = true)
    ) {
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

    if (!allowCleartext && normalizedUrl.startsWith("http://", ignoreCase = true)) {
      return@withContext HttpFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = normalizedUrl,
        statusCode = null,
        body = null,
        contentType = null,
        redirectChain = emptyList(),
        isCloudflareChallenge = false,
        errorMessage = "Unencrypted HTTP blocked (HTTPS is preferred). Enable HTTP confirmation in Edit Source if required."
      )
    }

    val clampedTimeout = timeoutSeconds.coerceIn(3, 60).toLong()
    val client = baseClient.newBuilder()
      .connectTimeout(clampedTimeout, TimeUnit.SECONDS)
      .readTimeout(clampedTimeout, TimeUnit.SECONDS)
      .followRedirects(followRedirects)
      .followSslRedirects(followRedirects)
      .build()

    val requestBuilder = Request.Builder()
      .url(normalizedUrl)
      .header("User-Agent", DEFAULT_USER_AGENT)
      .header("Accept", acceptHeader)
      .header("Accept-Language", "en-US,en;q=0.9")
      .get()

    if (forceNetwork) {
      requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
      requestBuilder.header("Pragma", "no-cache")
    }

    val request = requestBuilder.build()

    try {
      executeCallCancellable(client.newCall(request)).use { response ->
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

        val finalUrl = response.request.url.toString()
        if (!allowCleartext && finalUrl.startsWith("http://", ignoreCase = true)) {
          return@withContext HttpFetchResult(
            requestedUrl = normalizedUrl,
            finalUrl = finalUrl,
            statusCode = null,
            body = null,
            contentType = null,
            redirectChain = chain,
            isCloudflareChallenge = false,
            errorMessage = "Redirected to unencrypted HTTP, which is blocked when HTTPS is enforced."
          )
        }

        val bodyRead = readBoundedBody(response.body, maxResponseBytes)
        if (bodyRead.exceededLimit) {
          return@withContext HttpFetchResult(
            requestedUrl = normalizedUrl,
            finalUrl = finalUrl,
            statusCode = response.code,
            body = null,
            contentType = response.header("Content-Type"),
            redirectChain = chain,
            isCloudflareChallenge = false,
            errorMessage = "Response exceeded ${maxResponseBytes / (1024 * 1024)} MB safety limit."
          )
        }

        val bodyText = bodyRead.text
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
            "Redirected (HTTP ${response.code}) to ${directLocation ?: finalUrl}"
          !response.isSuccessful -> "HTTP ${response.code} ${response.message}".trim()
          bodyText.isNullOrBlank() -> "Empty response body"
          else -> null
        }

        HttpFetchResult(
          requestedUrl = normalizedUrl,
          finalUrl = finalUrl,
          statusCode = response.code,
          body = bodyText,
          contentType = contentType,
          redirectChain = chain,
          isCloudflareChallenge = isCf,
          errorMessage = err
        )
      }
    } catch (ce: CancellationException) {
      throw ce
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
