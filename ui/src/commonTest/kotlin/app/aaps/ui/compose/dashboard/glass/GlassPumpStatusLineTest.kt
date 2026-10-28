package app.aaps.ui.compose.dashboard.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlassPumpStatusLineTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun siteWarnsOnlyAfterThreeDays() {
        assertFalse(GlassPumpStatusRules.siteAgeWarning(null))
        assertFalse(GlassPumpStatusRules.siteAgeWarning(3 * day))
        assertTrue(GlassPumpStatusRules.siteAgeWarning(3 * day + 1))
    }

    @Test
    fun sensorWarnsOnlyAfterEightDays() {
        assertFalse(GlassPumpStatusRules.sensorAgeWarning(null))
        assertFalse(GlassPumpStatusRules.sensorAgeWarning(8 * day))
        assertTrue(GlassPumpStatusRules.sensorAgeWarning(8 * day + 1))
    }

    @Test
    fun pumpIsUnreachableOnlyWithTheAlertOnAndPastTheThreshold() {
        val now = 1_000 * day
        assertTrue(GlassPumpStatusRules.isPumpUnreachable(true, now - 31 * minute, 30, now))
        assertFalse(GlassPumpStatusRules.isPumpUnreachable(true, now - 30 * minute, 30, now))
        assertFalse(GlassPumpStatusRules.isPumpUnreachable(false, now - 5 * hour, 30, now))
    }

    @Test
    fun ageUsesTheTwoLargestUnits() {
        assertEquals(GlassPumpStatusRules.Age.DaysHours(2, 5), GlassPumpStatusRules.age(2 * day + 5 * hour + 59 * minute))
        assertEquals(GlassPumpStatusRules.Age.HoursMinutes(3, 7), GlassPumpStatusRules.age(3 * hour + 7 * minute))
        assertEquals(GlassPumpStatusRules.Age.Minutes(42), GlassPumpStatusRules.age(42 * minute))
        assertEquals(GlassPumpStatusRules.Age.Minutes(0), GlassPumpStatusRules.age(-5 * minute))
    }

    @Test
    fun formatKeepsTheTemplateAndStylesOnlyTheParts() {
        val red = SpanStyle(color = Color.Red)
        val result = formatAnnotated(
            "Pump: Reservoir %1\$s | Site %2\$s | Sensor %3\$s",
            listOf(AnnotatedString("120.00 U"), AnnotatedString("4d1h", red), AnnotatedString("n/a")),
        )
        assertEquals("Pump: Reservoir 120.00 U | Site 4d1h | Sensor n/a", result.text)
        val styles = result.spanStyles
        assertEquals(1, styles.size)
        assertEquals(red, styles[0].item)
        assertEquals("4d1h", result.text.substring(styles[0].start, styles[0].end))
    }

    @Test
    fun formatFollowsTheOrderOfTheTranslation() {
        val result = formatAnnotated("%2\$s / %1\$s %% %s", listOf(AnnotatedString("a"), AnnotatedString("b")))
        assertEquals("b / a % a", result.text)
    }

    @Test
    fun formatKeepsAPlaceholderWithoutPart() {
        assertEquals("x %3\$s", formatAnnotated("x %3\$s", listOf(AnnotatedString("a"))).text)
    }
}
