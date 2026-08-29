package app.aaps.plugins.aps.openAPSAIMI

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * `yyyy-MM-dd HH:mm:ss` in the phone's own time zone - the CSV timestamp AIMI has always written.
 *
 * This replaces `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)`, which does not exist outside
 * the JVM. The pattern is all digits, so `Locale.US` only ever pinned the calendar, and this
 * formatter is calendar independent by construction - see the same reasoning in
 * `app.aaps.core.interfaces.aps.RT`, where a Thai phone wrote Buddhist years into wire timestamps.
 */
private val aimiCsvStamp = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char(' ')
    hour(); char(':'); minute(); char(':'); second()
}

/** Wall-clock stamp for AIMI CSV rows. Do not use `SimpleDateFormat` in commonMain. */
internal fun aimiCsvTimestamp(epochMs: Long = aimiWallClockMs()): String =
    aimiCsvStamp.format(Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault()))
