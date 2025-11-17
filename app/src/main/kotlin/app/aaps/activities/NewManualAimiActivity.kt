package app.aaps.activities

import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebSettings
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
        val settings: WebSettings = manualWebView.settings
        settings.javaScriptEnabled = false
        settings.defaultTextEncodingName = "utf-8"
    }

    private fun loadManual() {
        val markdownContent = loadManualFromAssets()
        val htmlContent = convertMarkdownToHtml(markdownContent)
        manualWebView.loadDataWithBaseURL(
            null,
            htmlContent,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun loadManualFromAssets(): String {
        val locale = Locale.getDefault().language
        val fileName = when (locale) {
            "it" -> "AIMI_User_Manual_IT.md"
            "en" -> "AIMI_User_Manual_EN.md"
            else -> "AIMI_User_Manual_FR.md" // fallback inglese
        }

        return try {
            assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Errore nel caricamento del manuale."
        }
    }

    private fun convertMarkdownToHtml(markdown: String): String {
        // versione semplice: trasformiamo solo i principali elementi Markdown in HTML base
        var html = markdown
            .replace(Regex("^# (.*)$", RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("^## (.*)$", RegexOption.MULTILINE), "<h2>$1</h2>")
            .replace(Regex("^### (.*)$", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("\\*(.*?)\\*"), "<i>$1</i>")
            .replace(Regex("(?m)^- (.*)$"), "<li>$1</li>")

        // aggiungiamo <ul> intorno alle liste
        html = html.replace(Regex("(<li>.*?</li>)", RegexOption.DOT_MATCHES_ALL)) { "<ul>${it.value}</ul>" }

        // wrap in body + style minimale
        return """
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    body { font-family: sans-serif; padding: 16px; line-height: 1.5; }
                    h1 { font-size: 24px; }
                    h2 { font-size: 20px; }
                    h3 { font-size: 18px; }
                    ul { padding-left: 20px; }
                    a { color: #1a73e8; text-decoration: none; }
                </style>
            </head>
            <body>
                $html
            </body>
            </html>
        """.trimIndent()
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

