package app.aaps.plugins.aps.openAPSAIMI

import kotlinx.datetime.TimeZone
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The common clock must print the same civil fields as the JDK the tick uses today.
 * Thai locale changes the year, not the hour or the weekday. This test pins the fields
 * the tick actually reads.
 */
class AimiCivilClockJvmParityTest {

    @Test
    fun civilFieldsMatchCalendarAndJavaTimeAcrossZones() {
        val zones = listOf("UTC", "Europe/Prague", "America/New_York", "Asia/Bangkok", "Pacific/Auckland")
        val epochs = listOf(
            1_791_117_296_000L, // 2026-10-04 12:34:56Z
            Instant.parse("2026-03-29T00:30:00Z").toEpochMilli(),
            Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(),
            Instant.parse("2026-03-08T06:30:00Z").toEpochMilli(),
            Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
        )
        val mismatches = ArrayList<String>()
        for (zoneId in zones) {
            val zone = TimeZone.of(zoneId)
            val javaZone = ZoneId.of(zoneId)
            val tz = java.util.TimeZone.getTimeZone(zoneId)
            for (epoch in epochs) {
                val civil = aimiCivilClock(epoch, zone)
                val calendar = Calendar.getInstance(tz, Locale.US)
                calendar.timeInMillis = epoch
                val thai = Calendar.getInstance(tz, Locale("th", "TH"))
                thai.timeInMillis = epoch
                val zoned = Instant.ofEpochMilli(epoch).atZone(javaZone)
                val mappedDow = if (zoned.dayOfWeek.value == 7) 1 else zoned.dayOfWeek.value + 1
                check(civil.hour, calendar.get(Calendar.HOUR_OF_DAY), "hour $zoneId $epoch", mismatches)
                check(civil.minute, calendar.get(Calendar.MINUTE), "minute $zoneId $epoch", mismatches)
                check(civil.second, calendar.get(Calendar.SECOND), "second $zoneId $epoch", mismatches)
                check(civil.calendarDayOfWeek, calendar.get(Calendar.DAY_OF_WEEK), "dow $zoneId $epoch", mismatches)
                check(civil.hour, zoned.hour, "jhour $zoneId $epoch", mismatches)
                check(civil.calendarDayOfWeek, mappedDow, "jdow $zoneId $epoch", mismatches)
                check(civil.hour, thai.get(Calendar.HOUR_OF_DAY), "thaiHour $zoneId $epoch", mismatches)
                check(civil.calendarDayOfWeek, thai.get(Calendar.DAY_OF_WEEK), "thaiDow $zoneId $epoch", mismatches)
                val seconds = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = tz }.format(Date(epoch))
                val minutes = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = tz }.format(Date(epoch))
                if (aimiCsvTimestamp(epoch, zone) != seconds) mismatches.add("csv $zoneId $epoch")
                if (aimiCsvTimestampMinute(epoch, zone) != minutes) mismatches.add("min $zoneId $epoch")
                val yesterday = zoned.toLocalDate().minusDays(1).atTime(12, 0).atZone(javaZone).toInstant().toEpochMilli()
                if (aimiYesterdayMiddayEpochMs(epoch, zone) != yesterday) mismatches.add("ymid $zoneId $epoch")
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun check(actual: Int, expected: Int, label: String, mismatches: MutableList<String>) {
        if (actual != expected) mismatches.add("$label actual=$actual expected=$expected")
    }
}
