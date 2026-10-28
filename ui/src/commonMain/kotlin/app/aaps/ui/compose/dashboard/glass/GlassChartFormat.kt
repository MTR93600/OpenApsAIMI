package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/*
 * Small text helpers for the Glass charts. They give the same text as the `String.format(Locale.US, ...)`
 * calls of the `dev_OAPSAIMI` version, without `java.*`, so the charts can live in commonMain.
 */

private fun Int.twoDigits(): String = toString().padStart(2, '0')

/** Hour of the day, two digits ("07"), for a point that is [minutesAgo] minutes before [nowEpochMs]. */
internal fun glassHourLabel(nowEpochMs: Long, minutesAgo: Long, timeZone: TimeZone): String =
    Instant.fromEpochMilliseconds(nowEpochMs - minutesAgo * 60_000L).toLocalDateTime(timeZone).hour.twoDigits()

/** Clock time "HH:mm" for a point that is [minutesAgo] minutes before [nowEpochMs]. */
internal fun glassClockLabel(nowEpochMs: Long, minutesAgo: Long, timeZone: TimeZone): String =
    glassClockLabel(nowEpochMs - minutesAgo * 60_000L, timeZone)

/** Clock time "HH:mm" of [epochMs]. */
internal fun glassClockLabel(epochMs: Long, timeZone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${time.hour.twoDigits()}:${time.minute.twoDigits()}"
}

/** Date "dd/MM/yyyy" of [epochMs], same fixed pattern as on `dev_OAPSAIMI`. */
internal fun glassDateLabel(epochMs: Long, timeZone: TimeZone): String {
    val date = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone)
    return "${date.day.twoDigits()}/${date.month.number.twoDigits()}/${date.year.toString().padStart(4, '0')}"
}

/** Fixed decimals with a dot and ties away from zero, like `String.format(Locale.US, "%.Nf", value)`. */
internal fun glassFixedDecimals(value: Float, decimals: Int): String =
    NumberFormat.withDecimalsHalfUp(decimals).format(value.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)
