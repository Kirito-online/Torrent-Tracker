package com.example.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.network.TorrentHttpClient
import com.example.domain.model.TorrentSource
import com.example.ui.components.TorrentIntents
import com.example.ui.theme.SeederGreen

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudflareVerificationScreen(
  source: TorrentSource?,
  onBack: () -> Unit,
  onVerifyAndRetrySource: (String) -> Unit,
  onFeedback: (String) -> Unit,
  onLogVerificationEvent: (isError: Boolean, action: String, detail: String) -> Unit = { _, _, _ -> }
) {
  val context = LocalContext.current
  val initialUrl = source?.baseUrl ?: "https://example.com"
  var currentUrl by remember { mutableStateOf(initialUrl) }
  var progress by remember { mutableIntStateOf(0) }
  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      runCatching { CookieManager.getInstance().flush() }
      webViewRef?.destroy()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Open Verification", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
              val isHttps = currentUrl.startsWith("https://", ignoreCase = true)
              Icon(
                Icons.Filled.Lock,
                contentDescription = if (isHttps) "HTTPS encrypted" else "Unencrypted HTTP",
                tint = if (isHttps) SeederGreen else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(12.dp)
              )
              Spacer(Modifier.width(4.dp))
              Text(
                currentUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("verification_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = { webViewRef?.reload() },
            modifier = Modifier.minimumInteractiveComponentSize()
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reload page")
          }
        }
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.surface)
          .padding(horizontal = 12.dp, vertical = 10.dp)
      ) {
        Text(
          text = "Manual browser verification: complete any challenge here so this domain's temporary session cookie can be reused by search requests until it expires. No cookies are logged or exported. If verification keeps failing, open the site in your external browser.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedButton(
            onClick = { TorrentIntents.openExternalUrl(context, currentUrl, onFeedback) },
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Filled.OpenInBrowser, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("External Browser")
          }
          Button(
            onClick = {
              runCatching { CookieManager.getInstance().flush() }
              source?.let {
                onLogVerificationEvent(false, "Manual WebView verification retried", "Source: ${it.name} ($currentUrl)")
                onVerifyAndRetrySource(it.id)
              }
              onBack()
            },
            enabled = source != null,
            modifier = Modifier
              .weight(1f)
              .testTag("retry_after_verification_button")
          ) {
            Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Done & Retry")
          }
        }
      }
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      if (progress in 1..99) {
        LinearProgressIndicator(
          progress = { progress / 100f },
          modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
        )
      }

      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = TorrentHttpClient.DEFAULT_USER_AGENT
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true) // Required by Turnstile/challenge iframes within WebView session

            webChromeClient = object : WebChromeClient() {
              override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
              }
            }
            webViewClient = object : WebViewClient() {
              override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let { currentUrl = it }
                return false
              }

              override fun onPageFinished(view: WebView?, url: String?) {
                url?.let { currentUrl = it }
                runCatching { CookieManager.getInstance().flush() }
              }

              override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                  onLogVerificationEvent(
                    true,
                    "WebView page load error",
                    "URL: ${request.url} | Code=${error?.errorCode} | ${error?.description}"
                  )
                }
              }

              override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
              ) {
                if (request?.isForMainFrame == true) {
                  onLogVerificationEvent(
                    true,
                    "WebView HTTP error ${errorResponse?.statusCode}",
                    "URL: ${request.url} | ${errorResponse?.reasonPhrase}"
                  )
                }
              }
            }
            loadUrl(initialUrl)
            webViewRef = this
          }
        },
        modifier = Modifier
          .fillMaxSize()
          .testTag("cloudflare_webview")
      )
    }
  }
}
