package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.os.Bundle
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import javax.inject.Inject

/**
 * Transparent entry point for auditor notification taps — shows the report dialog then finishes.
 */
class AuditorReportActivity : TranslatedDaggerAppCompatActivity() {

  @Inject lateinit var auditorNotificationManager: AuditorNotificationManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    auditorNotificationManager.openReport(this) { finish() }
  }
}
