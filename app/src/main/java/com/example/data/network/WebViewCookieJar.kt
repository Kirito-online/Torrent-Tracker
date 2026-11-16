package com.example.data.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

data class CookieSessionStatus(
  val host: String,
  val isHttps: Boolean,
  val hasSessionCookies: Boolean,
  val hasClearanceCookie: Boolean
)

/**
 * Bridges OkHttp cookies with Android WebView CookieManager using strict domain,
 * host-only, path, expiry, and HTTPS-secure scoping so manual Cloudflare verification
 * sessions can be reused safely without leaking cookies across domains or over plain HTTP.
 * Falls back gracefully to an in-memory store in JVM unit test environments.
 */
class WebViewCookieJar : CookieJar {
  private val memoryStore = ConcurrentHashMap<String, Cookie>()

  private fun safeCookieManager(): CookieManager? = runCatching {
    CookieManager.getInstance()
  }.getOrNull()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    val now = System.currentTimeMillis()
    val validCookies = cookies.filter { cookie ->
      if (!cookie.matches(url)) return@filter false
      if (cookie.secure && !url.isHttps) return@filter false
      true
    }
    if (validCookies.isEmpty()) return

    validCookies.forEach { cookie ->
      val key = cookieKey(cookie)
      if (cookie.expiresAt <= now) {
        memoryStore.remove(key)
      } else {
        memoryStore[key] = cookie
      }
    }

    val manager = safeCookieManager() ?: return
    runCatching {
      val urlString = url.toString()
      val existingRaw = manager.getCookie(urlString).orEmpty()
      validCookies.forEach { cookie ->
        if (cookie.expiresAt <= now) {
          if (existingRaw.contains("${cookie.name}=")) {
            manager.setCookie(urlString, "${cookie.name}=; Max-Age=0; path=${cookie.path}")
          }
        } else {
          manager.setCookie(urlString, cookie.toString())
        }
      }
      manager.flush()
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val now = System.currentTimeMillis()
    val result = linkedMapOf<String, Cookie>()

    // 1. Load scoped, non-expired cookies from in-memory store (respects hostOnly/domain, path, and HTTPS-secure)
    val expiredKeys = mutableListOf<String>()
    val blockedNamesOnHost = hashSetOf<String>()
    memoryStore.forEach { (key, cookie) ->
      if (cookie.expiresAt <= now) {
        expiredKeys.add(key)
      } else if (cookie.matches(url) && (!cookie.secure || url.isHttps)) {
        result[cookie.name] = cookie
      } else if (cookie.matchesDomainOnly(url)) {
        blockedNamesOnHost.add(cookie.name.lowercase())
      }
    }
    expiredKeys.forEach { memoryStore.remove(it) }

    // 2. Bridge strictly scoped Cloudflare verification cookies from WebView CookieManager over HTTPS only.
    // Because Android CookieManager.getCookie() does not expose Domain/Path/Expires attributes,
    // we never widen scope (.domain() or broad '/'), never re-expose cookies whose stored path/https scope
    // excludes this URL, and never persist bridged cookies into memoryStore.
    if (url.isHttps) {
      val manager = safeCookieManager()
      if (manager != null) {
        val raw = runCatching { manager.getCookie(url.toString()) }.getOrNull()
        if (!raw.isNullOrBlank()) {
          val scopedPath = url.encodedPath.takeIf { it.startsWith("/") }.orEmpty().ifBlank { "/" }
          raw.split(";").forEach { pair ->
            val trimmed = pair.trim()
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
              val name = trimmed.substring(0, eqIdx).trim()
              val value = trimmed.substring(eqIdx + 1).trim()
              if (name.isNotBlank() &&
                isCloudflareSessionCookie(name) &&
                !result.containsKey(name) &&
                !blockedNamesOnHost.contains(name.lowercase())
              ) {
                result[name] = Cookie.Builder()
                  .name(name)
                  .value(value)
                  .hostOnlyDomain(url.host)
                  .path(scopedPath)
                  .secure()
                  .httpOnly()
                  .build()
              }
            }
          }
        }
      }
    }

    return result.values.toList()
  }

  fun hasCloudflareClearance(url: String): Boolean = getSessionStatus(url).hasClearanceCookie

  fun getSessionStatus(url: String): CookieSessionStatus {
    val httpUrl = url.toHttpUrlOrNull()
      ?: return CookieSessionStatus(host = "", isHttps = false, hasSessionCookies = false, hasClearanceCookie = false)
    val cookies = loadForRequest(httpUrl)
    val hasClearance = cookies.any { it.name.equals("cf_clearance", ignoreCase = true) }
    return CookieSessionStatus(
      host = httpUrl.host,
      isHttps = httpUrl.isHttps,
      hasSessionCookies = cookies.isNotEmpty(),
      hasClearanceCookie = hasClearance
    )
  }

  fun clearAllInMemory() {
    memoryStore.clear()
  }

  fun clearAllCookies() {
    memoryStore.clear()
    safeCookieManager()?.let { manager ->
      runCatching {
        manager.removeAllCookies(null)
        manager.flush()
      }
    }
  }

  private fun cookieKey(cookie: Cookie): String =
    "${cookie.domain.lowercase()}|${if (cookie.hostOnly) "H" else "D"}|${cookie.path}|${cookie.name}"

  private fun Cookie.matchesDomainOnly(url: HttpUrl): Boolean {
    if (secure && !url.isHttps) return false
    val host = url.host.lowercase()
    val cookieDomain = domain.lowercase()
    return if (hostOnly) {
      host == cookieDomain
    } else {
      host == cookieDomain || host.endsWith(".$cookieDomain")
    }
  }

  private fun isCloudflareSessionCookie(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "cf_clearance" ||
      lower == "__cf_bm" ||
      lower == "__cflb" ||
      lower.startsWith("cf_chl_")
  }
}
