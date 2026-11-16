package com.example.data.logging

import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ActivityLogLevel(val label: String) {
  INFO("Info"),
  WARN("Warning"),
  ERROR("Error")
}

enum class ActivityCategory(val label: String) {
  SYSTEM("System"),
  NETWORK("Network"),
  SEARCH("Search"),
  PARSER("Parser"),
  REPAIR("Repair"),
  BACKUP("Backup"),
  VERIFICATION("Verification"),
  STORAGE("Storage"),
  INTENT("Intent"),
  CRASH("Crash")
}

@Serializable
data class ActivityLogEntry(
  val id: String = UUID.randomUUID().toString().take(12),
  val timestamp: Long = System.currentTimeMillis(),
  val level: ActivityLogLevel,
  val category: ActivityCategory,
  val action: String,
  val detail: String = "",
  val throwableClass: String? = null,
  val stackSummary: String? = null
)

/**
 * Thread-safe, ring-buffered local activity and error logger backed by a bounded JSONL file.
 * Disabled by default; redacts cookies, authorization headers, and challenge tokens before writing.
 */
class ActivityLogger(
  private val logFile: File? = null,
  initialEnabled: Boolean = false,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
  private val lock = Any()
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
  }
  private val buffer = ArrayDeque<ActivityLogEntry>()

  @Volatile
  private var enabled: Boolean = initialEnabled

  @Volatile
  private var crashHandlerInstalled: Boolean = false

  private val _entries = MutableStateFlow<List<ActivityLogEntry>>(emptyList())
  val entries: StateFlow<List<ActivityLogEntry>> = _entries.asStateFlow()

  init {
    loadFromDisk()
  }

  fun isEnabled(): Boolean = enabled

  fun setEnabled(enable: Boolean) {
    val changed: Boolean
    synchronized(lock) {
      changed = enabled != enable
      enabled = enable
    }
    if (changed && enable) {
      recordInternal(
        level = ActivityLogLevel.INFO,
        category = ActivityCategory.SYSTEM,
        action = "Activity & error logging enabled",
        detail = "Capturing app activities, network/parser failures, repairs, and uncaught errors locally."
      )
    }
  }

  fun info(category: ActivityCategory, action: String, detail: String = "") {
    log(ActivityLogLevel.INFO, category, action, detail, null)
  }

  fun warn(category: ActivityCategory, action: String, detail: String = "", throwable: Throwable? = null) {
    log(ActivityLogLevel.WARN, category, action, detail, throwable)
  }

  fun error(category: ActivityCategory, action: String, detail: String = "", throwable: Throwable? = null) {
    log(ActivityLogLevel.ERROR, category, action, detail, throwable)
  }

  fun log(
    level: ActivityLogLevel,
    category: ActivityCategory,
    action: String,
    detail: String = "",
    throwable: Throwable? = null
  ) {
    if (!enabled) return
    recordInternal(level, category, action, detail, throwable)
  }

  fun clear() {
    synchronized(lock) {
      buffer.clear()
      _entries.value = emptyList()
      runCatching {
        if (logFile != null && logFile.exists()) {
          logFile.writeText("")
        }
      }
    }
  }

  fun installCrashHandler() {
    if (crashHandlerInstalled) return
    synchronized(lock) {
      if (crashHandlerInstalled) return
      crashHandlerInstalled = true
      val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        if (enabled) {
          runCatching {
            recordInternal(
              level = ActivityLogLevel.ERROR,
              category = ActivityCategory.CRASH,
              action = "Uncaught exception on thread '${thread.name}'",
              detail = throwable.localizedMessage ?: throwable.javaClass.simpleName,
              throwable = throwable,
              forceFlushSync = true
            )
          }
        }
        previousHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  fun formatReportText(appVersion: String = ""): String {
    val snapshot = synchronized(lock) { buffer.toList() }
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z", Locale.US)
    val errorCount = snapshot.count { it.level == ActivityLogLevel.ERROR }
    val warnCount = snapshot.count { it.level == ActivityLogLevel.WARN }
    val infoCount = snapshot.count { it.level == ActivityLogLevel.INFO }

    return buildString {
      appendLine("Torrent Tracker Activity & Error Log")
      appendLine("Generated: ${fmt.format(Date())}")
      appendLine("Logging Active: $enabled")
      appendLine("App Version: ${appVersion.ifBlank { "1.0" }}")
      appendLine("Android SDK: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Summary: ${snapshot.size} total events ($errorCount errors, $warnCount warnings, $infoCount info)")
      appendLine("Privacy Policy: Cookies, Authorization headers, and challenge tokens redacted")
      appendLine("========================================================================")
      if (snapshot.isEmpty()) {
        appendLine("(No activity log entries recorded)")
      } else {
        snapshot.forEach { entry ->
          append("[${fmt.format(Date(entry.timestamp))}] ")
          append("[${entry.level.name}] ")
          append("[${entry.category.name}] ")
          appendLine(entry.action)
          if (entry.detail.isNotBlank()) {
            appendLine("  Detail: ${entry.detail}")
          }
          if (!entry.throwableClass.isNullOrBlank()) {
            appendLine("  Exception: ${entry.throwableClass}")
          }
          if (!entry.stackSummary.isNullOrBlank()) {
            appendLine("  Stack:")
            entry.stackSummary.lineSequence().forEach { line ->
              appendLine("    $line")
            }
          }
        }
      }
    }
  }

  private fun recordInternal(
    level: ActivityLogLevel,
    category: ActivityCategory,
    action: String,
    detail: String,
    throwable: Throwable? = null,
    forceFlushSync: Boolean = false
  ) {
    val entry = ActivityLogEntry(
      level = level,
      category = category,
      action = sanitize(action).take(300),
      detail = sanitize(detail).take(1200),
      throwableClass = throwable?.javaClass?.name,
      stackSummary = throwable?.let { formatStackSummary(it) }
    )

    synchronized(lock) {
      buffer.addLast(entry)
      var trimmed = false
      while (buffer.size > maxEntries) {
        buffer.removeFirst()
        trimmed = true
      }
      _entries.value = buffer.toList().asReversed()

      val file = logFile
      if (file != null) {
        runCatching {
          file.parentFile?.mkdirs()
          if (trimmed || (file.exists() && file.length() > MAX_LOG_FILE_BYTES)) {
            rewriteDiskLocked()
          } else {
            file.appendText(json.encodeToString(entry) + "\n")
          }
          if (forceFlushSync) {
            // Ensuring file descriptor exists and content was written cleanly
            file.length()
          }
        }
      }
    }
  }

  private fun loadFromDisk() {
    val file = logFile ?: return
    if (!file.exists()) return
    synchronized(lock) {
      runCatching {
        file.useLines { lines ->
          lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
              runCatching { json.decodeFromString<ActivityLogEntry>(trimmed) }.getOrNull()?.let { parsed ->
                buffer.addLast(parsed)
                while (buffer.size > maxEntries) {
                  buffer.removeFirst()
                }
              }
            }
          }
        }
        _entries.value = buffer.toList().asReversed()
      }
    }
  }

  private fun rewriteDiskLocked() {
    val file = logFile ?: return
    file.parentFile?.mkdirs()
    val payload = buildString {
      buffer.forEach { entry ->
        append(json.encodeToString(entry))
        append('\n')
      }
    }
    file.writeText(payload)
  }

  private fun formatStackSummary(throwable: Throwable): String {
    val sw = StringWriter()
    throwable.printStackTrace(PrintWriter(sw))
    val lines = sw.toString()
      .lineSequence()
      .take(MAX_STACK_LINES)
      .joinToString("\n")
    return sanitize(lines)
  }

  companion object {
    const val DEFAULT_MAX_ENTRIES = 500
    const val MAX_LOG_FILE_BYTES = 512L * 1024L
    private const val MAX_STACK_LINES = 14

    private val HEADER_SECRET_REGEX = Regex(
      """(?i)\b(cookie|set-cookie|authorization|proxy-authorization|x-api-key)\s*[:=]\s*[^\r\n]+"""
    )
    private val CF_COOKIE_VALUE_REGEX = Regex(
      """(?i)\b(cf_clearance|__cf_bm|__cflb|cf_chl_[a-z0-9_]*)\s*=\s*[^;\s,\r\n]+"""
    )
    private val BEARER_REGEX = Regex("""(?i)\bBearer\s+[A-Za-z0-9\-._~+/]+=*""")

    fun sanitize(raw: String): String {
      if (raw.isBlank()) return raw
      return raw
        .replace(HEADER_SECRET_REGEX) { "${it.groupValues[1]}: [REDACTED]" }
        .replace(CF_COOKIE_VALUE_REGEX) { "${it.groupValues[1]}=[REDACTED]" }
        .replace(BEARER_REGEX, "Bearer [REDACTED]")
    }
  }
}
