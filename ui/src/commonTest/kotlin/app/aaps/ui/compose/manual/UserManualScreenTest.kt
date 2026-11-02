package app.aaps.ui.compose.manual

import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals

class UserManualScreenTest {

    @Test
    fun `the manual has the 20 sections of dev`() {
        assertEquals(20, MANUAL_SECTIONS.size)
        assertEquals(20, MANUAL_SECTIONS.map { it.title }.toSet().size)
        assertEquals(20, MANUAL_SECTIONS.map { it.content }.toSet().size)
    }

    @Test
    fun `bold marks become bold text and the stars go away`() {
        val text = manualText("Go to **Config Builder** then **APS**.")

        assertEquals("Go to Config Builder then APS.", text.text)
        val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(listOf("Config Builder", "APS"), bold.map { text.text.substring(it.start, it.end) })
    }

    @Test
    fun `text without marks or with a mark that is not closed stays as it is`() {
        assertEquals("plain text", manualText("plain text").text)
        assertEquals("a **b", manualText("a **b").text)
        assertEquals(0, manualText("a **b").spanStyles.size)
    }
}
