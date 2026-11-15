package com.example.data.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges OkHttp cookies with Android WebView CookieManager so manual Cloudflare
 * verification sessions can be reused by background OkHttp source requests.
 * Falls back gracefully to an in-memory map in JVM unit test environments.
 */
class WebViewCookieJar : CookieJar {
  private val memoryStore = ConcurrentHashMap<String, List<Cookie>>()

  private fun safeCookieManager(): CookieManager? = runCatching {
    CookieManager.getInstance()
  }.getOrNull()

  override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    if (cookies.isEmpty()) return
    memoryStore[url.host] = cookies
    val manager = safeCookieManager() ?: return
    runCatching {
      val urlString = url.toString()
      cookies.forEach { cookie ->
        manager.setCookie(urlString, cookie.toString())
      }
      manager.flush()
    }
  }

  override fun loadForRequest(url: HttpUrl): List<Cookie> {
    val result = mutableMapOf<String, Cookie>()
    memoryStore[url.host]?.forEach { cookie ->
      if (cookie.expiresAt > System.currentTimeMillis()) {
        result[cookie.name] = cookie
      }
    }
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
            val parsed = Cookie.Builder()
              .name(name)
              .value(value)
              .domain(url.host)
              .path("/")
              .build()
            result[name] = parsed
          }
        }
      }
    }
    return result.values.toList()
  }

  fun hasCloudflareClearance(url: String): Boolean {
    val manager = safeCookieManager() ?: return false
    val raw = runCatching { manager.getCookie(url) }.getOrNull().orEmpty()
    return raw.contains("cf_clearance=")
  }
}
