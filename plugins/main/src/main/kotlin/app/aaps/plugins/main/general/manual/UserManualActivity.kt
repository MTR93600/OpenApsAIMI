package app.aaps.plugins.main.general.manual

import android.os.Bundle
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.aaps.plugins.main.R

class UserManualActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_manual)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupSection(R.id.section1, R.string.manual_section_1_title, R.string.manual_section_1_content)
        setupSection(R.id.section2, R.string.manual_section_2_title, R.string.manual_section_2_content)
        setupSection(R.id.section3, R.string.manual_section_3_title, R.string.manual_section_3_content)
        setupSection(R.id.section4, R.string.manual_section_4_title, R.string.manual_section_4_content)
        setupSection(R.id.section5, R.string.manual_section_5_title, R.string.manual_section_5_content)
        setupSection(R.id.section6, R.string.manual_section_6_title, R.string.manual_section_6_content)
        setupSection(R.id.section7, R.string.manual_section_7_title, R.string.manual_section_7_content)
        setupSection(R.id.section8, R.string.manual_section_8_title, R.string.manual_section_8_content)
        setupSection(R.id.section9, R.string.manual_section_9_title, R.string.manual_section_9_content)
        setupSection(R.id.section10, R.string.manual_section_10_title, R.string.manual_section_10_content)
        setupSection(R.id.section11, R.string.manual_section_11_title, R.string.manual_section_11_content)
        setupSection(R.id.section12, R.string.manual_section_12_title, R.string.manual_section_12_content)
    }

    private fun setupSection(viewId: Int, titleRes: Int, contentRes: Int) {
        val sectionView = findViewById<android.view.View>(viewId) ?: return
        sectionView.findViewById<TextView>(R.id.section_title)?.setText(titleRes)
        sectionView.findViewById<TextView>(R.id.section_content)?.setText(contentRes)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
