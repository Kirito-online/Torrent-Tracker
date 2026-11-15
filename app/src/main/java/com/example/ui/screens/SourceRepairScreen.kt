package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.SourceType
import com.example.domain.model.TorrentSource
import com.example.domain.model.VerifiedReplacement
import com.example.ui.components.CompactLabelBadge
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.SeederGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.SourcesUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceRepairScreen(
  sourceId: String,
  source: TorrentSource?,
  preferHttps: Boolean,
  uiState: SourcesUiState,
  onBack: () -> Unit,
  onRunDiagnostic: (String) -> Unit,
  onTestCandidateUrl: (String, TorrentSource) -> Unit,
  onApplyReplacement: (String, VerifiedReplacement) -> Unit,
  onSaveSource: (TorrentSource) -> Unit,
  onRestorePrevious: (String) -> Unit,
  onDetectFeeds: (String) -> Unit,
  onClearDetectedFeeds: () -> Unit,
  onTestSelectorsPreview: (TorrentSource, String) -> Unit,
  onOpenVerification: (String) -> Unit
) {
  LaunchedEffect(sourceId) {
    onRunDiagnostic(sourceId)
  }

  var manualUrl by remember(source) {
    mutableStateOf(
      source?.let {
        if (it.feedUrl.isNotBlank()) it.feedUrl else it.searchUrlTemplate.ifBlank { it.baseUrl }
      }.orEmpty()
    )
  }
  var showEditSheet by remember { mutableStateOf(false) }
  var pendingManualHttpUrl by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Source Repair Assistant", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("repair_back_button")
          ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = { onRunDiagnostic(sourceId) },
            enabled = !uiState.isRunningDiagnostics,
            modifier = Modifier.minimumInteractiveComponentSize()
          ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Re-run diagnostics")
          }
        }
      )
    }
  ) { innerPadding ->
    if (source == null) {
      Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
        Text("Source not found")
      }
      return@Scaffold
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp)
    ) {
      Text(source.name, style = MaterialTheme.typography.titleLarge)
      Text(source.baseUrl, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.height(12.dp))

      if (uiState.isRunningDiagnostics) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
          CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(12.dp))
          Text("Inspecting domain reachability, redirects, feeds, and page structure…", style = MaterialTheme.typography.bodyMedium)
        }
      }

      val report = uiState.diagnosticReport
      if (report != null && report.source.id == sourceId) {
        val statusIcon = when {
          report.isCloudflareChallenge || report.isSpaPage -> Icons.Filled.Warning
          !report.domainReachable || report.parsingIssueDetected || (report.statusCode != null && report.statusCode >= 400) -> Icons.Filled.Warning
          else -> Icons.Filled.CheckCircle
        }
        val statusColor = when {
          report.isCloudflareChallenge || report.isSpaPage -> WarningOrange
          !report.domainReachable || report.parsingIssueDetected || (report.statusCode != null && report.statusCode >= 400) -> ErrorRed
          else -> SeederGreen
        }

        Column(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(statusColor.copy(alpha = 0.1f))
            .border(0.5.dp, statusColor, RoundedCornerShape(10.dp))
            .padding(12.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Diagnostic Summary", style = MaterialTheme.typography.titleSmall, color = statusColor)
          }
          Spacer(Modifier.height(6.dp))
          Text(report.issueSummary, style = MaterialTheme.typography.bodyMedium)
          Spacer(Modifier.height(6.dp))
          Text(
            "Domain reachable: ${if (report.domainReachable) "Yes" else "No"} · HTTP: ${report.statusCode ?: "N/A"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        if (report.redirectChain.isNotEmpty()) {
          Spacer(Modifier.height(12.dp))
          Text("Detected Redirect Chain", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(4.dp))
          report.redirectChain.forEach { hop ->
            Text(hop, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
          }
        }

        if (report.recommendedActions.isNotEmpty()) {
          Spacer(Modifier.height(12.dp))
          Text("Recommended Actions", style = MaterialTheme.typography.titleSmall)
          Spacer(Modifier.height(4.dp))
          report.recommendedActions.forEach { rec ->
            Text("• $rec", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
          }
        }

        if (report.isCloudflareChallenge) {
          Spacer(Modifier.height(10.dp))
          Button(
            onClick = { onOpenVerification(source.id) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("repair_open_verification_button")
          ) {
            Icon(Icons.Filled.VerifiedUser, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Open Verification")
          }
        } else if (report.isSpaPage) {
          Spacer(Modifier.height(10.dp))
          OutlinedButton(
            onClick = { onOpenVerification(source.id) },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("repair_open_browser_button")
          ) {
            Icon(Icons.Filled.Public, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Inspect Page in Browser Session")
          }
        }

        if (report.parsingIssueDetected || source.sourceType != SourceType.RSS_ATOM) {
          Spacer(Modifier.height(8.dp))
          FilledTonalButton(
            onClick = {
              onClearDetectedFeeds()
              showEditSheet = true
            },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("repair_edit_selectors_button")
          ) {
            Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
              if (source.sourceType == SourceType.JSON_API) "Edit JSON Mapping & Config"
              else "Edit Selectors & Config"
            )
          }
        }

        if (report.verifiedReplacements.isNotEmpty()) {
          val verifiedFixes = report.verifiedReplacements.filter { it.isVerified }
          val suggestedCandidates = report.verifiedReplacements.filter { !it.isVerified }

          if (verifiedFixes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Verified Fixes", style = MaterialTheme.typography.titleSmall, color = SeederGreen)
            Spacer(Modifier.height(6.dp))
            verifiedFixes.forEach { replacement ->
              ReplacementCandidateCard(
                source = source,
                replacement = replacement,
                onTestCandidateUrl = onTestCandidateUrl,
                onApplyReplacement = onApplyReplacement
              )
            }
          }

          if (suggestedCandidates.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
              "Suggested Candidates (Unverified — Test Before Applying)",
              style = MaterialTheme.typography.titleSmall,
              color = WarningOrange
            )
            Spacer(Modifier.height(6.dp))
            suggestedCandidates.forEach { replacement ->
              ReplacementCandidateCard(
                source = source,
                replacement = replacement,
                onTestCandidateUrl = onTestCandidateUrl,
                onApplyReplacement = onApplyReplacement
              )
            }
          }
        }
      }

      Spacer(Modifier.height(16.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(14.dp))

      Text("Manual Endpoint Edit & Test", style = MaterialTheme.typography.titleSmall)
      Spacer(Modifier.height(6.dp))
      OutlinedTextField(
        value = manualUrl,
        onValueChange = { manualUrl = it },
        label = { Text("Endpoint or Feed URL") },
        singleLine = true,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("repair_manual_url_input")
      )
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = { onTestCandidateUrl(manualUrl, source) },
          modifier = Modifier.weight(1f)
        ) {
          Icon(Icons.Filled.Build, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Test Before Apply")
        }
        Button(
          onClick = {
            val trimmed = manualUrl.trim()
            if (trimmed.startsWith("http://", true) && preferHttps && !source.allowCleartextHttp) {
              pendingManualHttpUrl = trimmed
            } else {
              val isHttp = trimmed.startsWith("http://", true)
              val updated = when (source.sourceType) {
                SourceType.RSS_ATOM -> source.copy(feedUrl = trimmed, allowCleartextHttp = isHttp)
                else -> source.copy(searchUrlTemplate = trimmed, allowCleartextHttp = isHttp)
              }
              onSaveSource(updated)
            }
          },
          enabled = manualUrl.startsWith("http://", true) || manualUrl.startsWith("https://", true),
          modifier = Modifier.weight(1f)
        ) {
          Text("Apply URL")
        }
      }

      uiState.candidateTestResult?.let { resText ->
        Spacer(Modifier.height(8.dp))
        Text(resText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      }

      if (!source.previousWorkingConfigJson.isNullOrBlank()) {
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
          onClick = { onRestorePrevious(source.id) },
          modifier = Modifier.fillMaxWidth()
        ) {
          Icon(Icons.Filled.History, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text("Restore Previous Working Configuration")
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }

  if (showEditSheet && source != null) {
    SourceEditorSheet(
      initialSource = source,
      preferHttps = preferHttps,
      uiState = uiState,
      onDetectFeeds = onDetectFeeds,
      onTestPreview = onTestSelectorsPreview,
      onSave = { saved ->
        onSaveSource(saved)
        showEditSheet = false
      },
      onDismiss = { showEditSheet = false }
    )
  }

  pendingManualHttpUrl?.let { httpUrl ->
    if (source != null) {
      AlertDialog(
        onDismissRequest = { pendingManualHttpUrl = null },
        title = { Text("Allow Insecure HTTP?") },
        text = {
          Text("'$httpUrl' uses unencrypted HTTP. Confirm only if you trust this endpoint.")
        },
        confirmButton = {
          TextButton(onClick = {
            val updated = when (source.sourceType) {
              SourceType.RSS_ATOM -> source.copy(feedUrl = httpUrl, allowCleartextHttp = true)
              else -> source.copy(searchUrlTemplate = httpUrl, allowCleartextHttp = true)
            }
            onSaveSource(updated)
            pendingManualHttpUrl = null
          }) { Text("Apply HTTP") }
        },
        dismissButton = {
          TextButton(onClick = { pendingManualHttpUrl = null }) { Text("Cancel") }
        }
      )
    }
  }
}

@Composable
private fun ReplacementCandidateCard(
  source: TorrentSource,
  replacement: VerifiedReplacement,
  onTestCandidateUrl: (String, TorrentSource) -> Unit,
  onApplyReplacement: (String, VerifiedReplacement) -> Unit
) {
  val candidateUrl = replacement.replacementFeedUrl
    ?: replacement.replacementSearchTemplate
    ?: replacement.replacementBaseUrl
  val candidateSource = source.copy(
    baseUrl = replacement.replacementBaseUrl.ifBlank { source.baseUrl },
    searchUrlTemplate = replacement.replacementSearchTemplate ?: source.searchUrlTemplate,
    feedUrl = replacement.replacementFeedUrl ?: source.feedUrl,
    sourceType = replacement.detectedSourceType ?: source.sourceType,
    htmlSelectors = replacement.replacementHtmlSelectors ?: source.htmlSelectors,
    allowCleartextHttp = candidateUrl.startsWith("http://", true) || source.allowCleartextHttp
  )
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .padding(10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        replacement.label,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.weight(1f).padding(end = 8.dp)
      )
      CompactLabelBadge(
        text = if (replacement.isVerified) "Verified" else "Unverified",
        containerColor = (if (replacement.isVerified) SeederGreen else WarningOrange).copy(alpha = 0.16f),
        contentColor = if (replacement.isVerified) SeederGreen else WarningOrange
      )
    }
    Spacer(Modifier.height(2.dp))
    val subtitleText = replacement.replacementHtmlSelectors?.let { cfg ->
      "Container: ${cfg.itemContainer}"
    } ?: candidateUrl
    Text(
      subtitleText,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Text(replacement.verificationSummary, style = MaterialTheme.typography.bodySmall)
    if (replacement.reason.isNotBlank()) {
      Spacer(Modifier.height(2.dp))
      Text(
        replacement.reason,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(
        onClick = { onTestCandidateUrl(candidateUrl, candidateSource) }
      ) {
        Text(if (replacement.replacementHtmlSelectors != null) "Test Selectors" else "Test URL")
      }
      Button(onClick = { onApplyReplacement(source.id, replacement) }) {
        Text(if (replacement.isVerified) "Apply Fix" else "Apply Anyway")
      }
    }
  }
}
