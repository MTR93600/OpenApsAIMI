package app.aaps.activities

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.aaps.R

class NewManualAimiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(app.aaps.core.ui.R.style.AppTheme)
        setContentView(R.layout.activity_manual_aimi)

        title = getString(R.string.manual_aimi_preview_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val manualTextView = findViewById<TextView>(R.id.manualText)

        manualTextView.text = manualMarkdown()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun manualMarkdown(): String {
        return """
# Manuale AIMI (test)
Questo è un esempio di manuale in formato **Markdown**.

## Sezione 1
- Punto A
- Punto B

## Sezione 2
Testo di prova, altre note.
        """.trimIndent()
    }
}
