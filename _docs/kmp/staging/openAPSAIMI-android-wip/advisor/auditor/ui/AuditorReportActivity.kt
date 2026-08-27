package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import android.content.Context
import android.os.Bundle
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.core.ui.locale.LocaleHelper
import dev.zacsweers.metro.Inject

/**
 * Transparent entry point for auditor notification taps — shows the report dialog then finishes.
 */
class AuditorReportActivity : MetroAppCompatActivity() {

  @Inject lateinit var auditorNotificationManager: AuditorNotificationManager

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(LocaleHelper.wrap(newBase))
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    auditorNotificationManager.openReport(this) { finish() }
  }
}
