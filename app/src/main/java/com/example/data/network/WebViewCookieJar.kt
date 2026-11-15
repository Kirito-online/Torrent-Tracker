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
 * path, expiry, and HTTPS-secure scoping so manual Cloudflare verification sessions
 * can be reused safely without leaking cookies across domains or over plain HTTP.
 * Falls back gracefully to an in-memory store in JVM unit test environments.
 */
class WebViewCookieJar : CookieJar {
  // Keyed by normalized cookie key "${domain}|${path}|${name}"
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

    val nonExpiredCookies = mutableListOf<Cookie>()
    validCookies.forEach { cookie ->
      val key = cookieKey(cookie)
      if (cookie.expiresAt <= now) {
        memoryStore.remove(key)
      } else {
        memoryStore[key] = cookie
        nonExpiredCookies.add(cookie)
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

    // 1. Load scoped, non-expired cookies from in-memory store (respects domain, path, and secure-over-HTTPS)
    val expiredKeys = mutableListOf<String>()
    memoryStore.forEach { (key, cookie) ->
      if (cookie.expiresAt <= now) {
        expiredKeys.add(key)
      } else if (cookie.matches(url) && (!cookie.secure || url.isHttps)) {
        result[cookie.name] = cookie
      }
    }
    expiredKeys.forEach { memoryStore.remove(it) }

    // 2. Merge scoped cookies from Android WebView CookieManager
    val manager = safeCookieManager()
    if (manager != null) {
      val raw = runCatching { manager.getCookie(url.toString()) }.getOrNull()
      if (!raw.isNullOrBlank()) {
        raw.split(";").forEach { pair ->
          val trimmed = pair.trim()
          val eqIdx = trimmed.indexOf('=')
          if (eqIdx > 0) {
            val name = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (name.isNotBlank() && !result.containsKey(name)) {
              // Enforce HTTPS for Cloudflare clearance/session cookies
              val isSensitiveSessionCookie = isSensitiveSessionName(name)
              if (isSensitiveSessionCookie && !url.isHttps) {
                return@forEach
              }
              val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(url.host)
                .path("/")
              if (url.isHttps && isSensitiveSessionCookie) {
                builder.secure()
              }
              result[name] = builder.build()
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

  private fun cookieKey(cookie: Cookie): String =
    "${cookie.domain.lowercase()}|${cookie.path}|${cookie.name}"

  private fun isSensitiveSessionName(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "cf_clearance" || lower.startsWith("__cf") || lower.startsWith("__host-") || lower.startsWith("__secure-")
  }
}
