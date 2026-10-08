package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState

/**
 * Builds user-visible auditor report text from [AuditorVerdictCache] and tracker status.
 */
object AuditorReportFormatter {

  private const val DEFAULT_CACHE_MAX_AGE_MS = 300_000L

  fun insightCount(maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS): Int {
    val resolved = AuditorVerdictCache.resolveForDisplay(maxAgeMs) ?: return 0
    val evidenceCount = resolved.cached.verdict.evidence.size
    return if (evidenceCount > 0) evidenceCount else 1
  }

  fun hasUnreadVerdict(lastReadTimestampMs: Long, maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS): Boolean {
    val resolved = AuditorVerdictCache.resolveForDisplay(maxAgeMs) ?: return false
    return resolved.cached.timestamp > lastReadTimestampMs
  }

  fun buildNotificationSummary(rh: TextResolver, uiState: AuditorUIState): String {
    val count = uiState.insightCount
    return if (count > 1) {
      rh.gs(ApsStrings.aimi_auditor_notification_summary_multiple, count)
    } else {
      rh.gs(ApsStrings.aimi_auditor_notification_summary_single)
    }
  }

  fun buildNotificationBigText(rh: TextResolver, uiState: AuditorUIState): String {
    val summary = buildNotificationSummary(rh, uiState)
    val reportBody = buildReportBody(rh, includeStatusLine = true)
      ?: uiState.statusMessage
    return buildString {
      append(summary)
      append('\n')
      append('\n')
      append(reportBody)
      append('\n')
      append('\n')
      append(rh.gs(ApsStrings.aimi_auditor_notification_tap_to_view))
    }
  }

  fun buildInAppNotificationText(rh: TextResolver): String {
    val shortSummary = buildShortVerdictSummary(rh)
      ?: rh.gs(ApsStrings.aimi_auditor_notification_summary_single)
    return rh.gs(ApsStrings.aimi_auditor_in_app_notification_summary, shortSummary)
  }

  fun buildFullReportMessage(rh: TextResolver): String {
    return buildReportBody(rh, includeStatusLine = true)
      ?: rh.gs(ApsStrings.aimi_auditor_report_no_cache)
  }

  fun buildFullReportMessageWithFallback(rh: TextResolver): Pair<String, Boolean> {
    val body = buildReportBody(rh, includeStatusLine = true)
    if (body != null) {
      val aligned = AuditorVerdictCache.resolveForDisplay()?.alignedWithCurrentBg != false
      return body to aligned
    }
    val staleBody = buildReportBodyFromRecent(rh, includeStatusLine = true)
    return if (staleBody != null) {
      staleBody to false
    } else {
      rh.gs(ApsStrings.aimi_auditor_report_no_cache) to false
    }
  }

  private fun buildShortVerdictSummary(rh: TextResolver): String? {
    val resolved = AuditorVerdictCache.resolveForDisplay() ?: return null
    return resolved.cached.verdict.verdict.name
  }

  private fun buildReportBody(rh: TextResolver, includeStatusLine: Boolean): String? {
    val resolved = AuditorVerdictCache.resolveForDisplay() ?: return null
    if (!resolved.alignedWithCurrentBg) return null
    return formatVerdict(
      rh,
      resolved.cached.verdict,
      trackerStatusMessageIfNeeded(includeStatusLine),
      staleBgNote = null,
    )
  }

  private fun buildReportBodyFromRecent(rh: TextResolver, includeStatusLine: Boolean): String? {
    val cached = AuditorVerdictCache.get() ?: return null
    val staleNote = rh.gs(ApsStrings.aimi_auditor_report_stale_bg_note)
    return formatVerdict(
      rh,
      cached.verdict,
      trackerStatusMessageIfNeeded(includeStatusLine),
      staleBgNote = staleNote,
    )
  }

  private fun trackerStatusMessageIfNeeded(includeStatusLine: Boolean): String? {
    if (!includeStatusLine) return null
    val (status, _) = AuditorStatusTracker.getStatus()
    return if (status.isActive()) status.message else null
  }

  internal fun formatVerdict(
    rh: TextResolver,
    verdict: AuditorVerdict,
    statusMessage: String?,
    staleBgNote: String? = null,
  ): String {
    val confidencePercent = (verdict.confidence * 100.0).toInt().coerceIn(0, 100)
    return buildString {
      append(
        rh.gs(
          ApsStrings.aimi_auditor_report_verdict_line,
          verdict.verdict.name,
          confidencePercent,
        )
      )
      staleBgNote?.let { note ->
        append('\n')
        append(note)
      }
      statusMessage?.let { status ->
        append('\n')
        append(rh.gs(ApsStrings.aimi_auditor_report_status_line, status))
      }
      if (verdict.degradedMode) {
        append('\n')
        append(rh.gs(ApsStrings.aimi_auditor_report_degraded_mode))
      }
      if (verdict.evidence.isNotEmpty()) {
        append('\n')
        append('\n')
        append(rh.gs(ApsStrings.aimi_auditor_report_evidence_header))
        verdict.evidence.forEach { line ->
          append('\n')
          append("• ")
          append(line)
        }
      }
      if (verdict.riskFlags.isNotEmpty()) {
        append('\n')
        append('\n')
        append(rh.gs(ApsStrings.aimi_auditor_report_risk_flags_header))
        verdict.riskFlags.forEach { flag ->
          append('\n')
          append("• ")
          append(flag)
        }
      }
      append('\n')
      append('\n')
      append(rh.gs(ApsStrings.aimi_auditor_report_adjustments_header))
      append('\n')
      append(
        rh.gs(
          ApsStrings.aimi_auditor_report_smb_factor,
          formatFactor(verdict.boundedAdjustments.smbFactorClamp),
        )
      )
      append('\n')
      append(
        rh.gs(
          ApsStrings.aimi_auditor_report_interval_add,
          verdict.boundedAdjustments.intervalAddMin,
        )
      )
      append('\n')
      append(
        if (verdict.boundedAdjustments.preferTbr) {
          rh.gs(ApsStrings.aimi_auditor_report_prefer_tbr_yes)
        } else {
          rh.gs(ApsStrings.aimi_auditor_report_prefer_tbr_no)
        }
      )
      append('\n')
      append(
        rh.gs(
          ApsStrings.aimi_auditor_report_tbr_factor,
          formatFactor(verdict.boundedAdjustments.tbrFactorClamp),
        )
      )
    }
  }

  /**
   * Two decimals, rounded half up, with a dot separator.
   *
   * This was `String.format(Locale.US, "%.2f", v)`, which rounds a tie away from zero - half up for a
   * positive factor - and always writes a dot. [aimiFmt2] is the shared formatter that already says
   * exactly that, so the numbers here read the same as the ones in the AIMI logs.
   */
  private fun formatFactor(value: Double): String = aimiFmt2(value)
}
