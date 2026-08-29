package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pins `aimiCsvTimestamp` against the `SimpleDateFormat` call it replaces.
 *
 * The string is the first column of `oapsaimi_wcycle.csv`, which the offline retrainer reads back,
 * so a changed format is silent corruption of the training corpus rather than a cosmetic change.
 * The old code was `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())` in the
 * phone's own time zone, and the new one must render exactly the same characters.
 */
class AimiCsvTimestampParityTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @BeforeEach fun save() {
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
    }

    @AfterEach fun restore() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
    }

    /** A literal copy of the original body, so the comparison is against real old behaviour. */
    private fun reference(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    /**
     * Fixed instants, chosen in UTC. The middle three all have a single digit month, day, hour,
     * minute and second, which is where a formatter that forgot zero padding would differ.
     */
    private val samples = listOf(
        0L,                  // 1970-01-01 00:00:00
        1_767_323_045_000L,  // 2026-01-02 03:04:05
        1_772_683_629_000L,  // 2026-03-05 04:07:09
        1_788_858_487_000L,  // 2026-09-08 09:08:07
        1_798_761_599_000L   // 2026-12-31 23:59:59
    )

    @Test fun `matches SimpleDateFormat in UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        samples.forEach { t ->
            assertWithMessage("t=%s", t).that(aimiCsvTimestamp(t)).isEqualTo(reference(t))
        }
    }

    @Test fun `zero pads every single digit field`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        assertThat(aimiCsvTimestamp(1_767_323_045_000L)).isEqualTo("2026-01-02 03:04:05")
        assertThat(aimiCsvTimestamp(1_772_683_629_000L)).isEqualTo("2026-03-05 04:07:09")
        assertThat(aimiCsvTimestamp(1_788_858_487_000L)).isEqualTo("2026-09-08 09:08:07")
    }

    @Test fun `matches SimpleDateFormat in other time zones`() {
        Locale.setDefault(Locale.US)
        listOf("Europe/Paris", "America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham").forEach { zone ->
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            samples.forEach { t ->
                assertWithMessage("zone %s t=%s", zone, t)
                    .that(aimiCsvTimestamp(t)).isEqualTo(reference(t))
            }
        }
    }

    /** The reason for the change beyond portability: the old formatter took its calendar from the locale. */
    @Test fun `the new version is locale independent`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val expected = aimiCsvTimestamp(1_772_683_629_000L)
        listOf(
            Locale.forLanguageTag("th-TH-u-ca-buddhist"),
            Locale.forLanguageTag("ar-EG-u-nu-arab"),
            Locale.forLanguageTag("ja-JP-u-ca-japanese")
        ).forEach { locale ->
            Locale.setDefault(locale)
            assertWithMessage("locale %s", locale)
                .that(aimiCsvTimestamp(1_772_683_629_000L)).isEqualTo(expected)
        }
    }
}
