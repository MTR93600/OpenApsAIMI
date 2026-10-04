package app.aaps.plugins.aps.openAPSAIMI

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Local civil time for the AIMI tick. `calendarDayOfWeek` uses `java.util.Calendar`
 * numbering (Sunday = 1 … Saturday = 7). Hour, minute and second match
 * `Calendar.HOUR_OF_DAY` / `MINUTE` / `SECOND` in the same time zone, including on a
 * Thai Buddhist calendar: that calendar changes the year, not these fields.
 */
internal data class AimiCivilClock(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val calendarDayOfWeek: Int,
)

internal fun aimiCivilClock(
    epochMs: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): AimiCivilClock {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val iso = local.dayOfWeek.isoDayNumber
    val calendarDayOfWeek = if (iso == 7) 1 else iso + 1
    return AimiCivilClock(local.hour, local.minute, local.second, calendarDayOfWeek)
}

internal fun aimiLocalHour(
    epochMs: Long = aimiWallClockMs(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Int = aimiCivilClock(epochMs, zone).hour

/**
 * `LocalTime.now().isAfter(start) && LocalTime.now().isBefore(end)`, exclusive on both ends.
 * Nanoseconds count: a time that shares the start's second but has a later fraction is inside.
 */
internal fun aimiStrictlyInsideLocalWindow(
    epochMs: Long,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val seconds = local.hour * 3_600 + local.minute * 60 + local.second
    val start = startHour * 3_600 + startMinute * 60
    val end = endHour * 3_600 + endMinute * 60
    val afterStart = seconds > start || (seconds == start && local.nanosecond > 0)
    return afterStart && seconds < end
}

/** Yesterday at 12:00 in [zone], the same instant `LocalDate.now().minusDays(1).atTime(12, 0)` names. */
internal fun aimiYesterdayMiddayEpochMs(
    epochMs: Long = aimiWallClockMs(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    val yesterday = local.date.minus(DatePeriod(days = 1))
    return LocalDateTime(yesterday, LocalTime(12, 0)).toInstant(zone).toEpochMilliseconds()
}
