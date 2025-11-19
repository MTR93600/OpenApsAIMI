package app.aaps.activities

import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import app.aaps.R
import java.util.Locale

class NewManualAimiActivity : AppCompatActivity() {

    private lateinit var manualWebView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(app.aaps.core.ui.R.style.AppTheme)
        setContentView(R.layout.activity_manual_aimi)

        title = getString(R.string.manual_aimi_preview_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        manualWebView = findViewById(R.id.manualWebView)

        setupWebView()
        loadManual()
    }

    private fun setupWebView() {
        // ABILITA JAVASCRIPT per il menu hamburger/TOC
        manualWebView.settings.javaScriptEnabled = true
        manualWebView.settings.defaultTextEncodingName = "utf-8"
    }

    private fun loadManual() {
        val locale = Locale.getDefault().language

        val fileName = when (locale) {
            "it" -> "AIMI_France_User_Manual.html"
            "en" -> "AIMI_France_User_Manual.html"
            "fr" -> "AIMI_France_User_Manual.html"
            else -> "AIMI_France_User_Manual.html" // fallback finale
        }

        manualWebView.loadUrl("file:///android_asset/$fileName")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
}

