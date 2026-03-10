package app.aaps.activities

import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.commit
import app.aaps.R
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import app.aaps.plugins.main.general.dashboard.DashboardFragment

class DashboardPreviewActivity : DaggerAppCompatActivityWithResult() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(app.aaps.core.ui.R.style.AppTheme)
        setContentView(R.layout.activity_dashboard_preview)
        title = getString(R.string.dashboard_preview_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.dashboard_container, DashboardFragment())
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }

            else               -> super.onOptionsItemSelected(item)
        }
}
