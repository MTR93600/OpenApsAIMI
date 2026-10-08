package app.aaps.plugins.aps.openAPSAIMI.ports

/**
 * Platform date/time text for display.
 *
 * Android: `SimpleDateFormat` with the device locale and timezone.
 * iOS: `DateFormatter` with the current locale and timezone.
 *
 * `format` renders epoch millis for display (device locale); `parse` reads
 * machine-generated text and always uses a fixed US locale — mirroring the Android
 * call sites this replaces — returning null when the text is unparseable.
 */
interface AimiDateFormatter {
    fun format(mills: Long, pattern: String): String
    fun parse(text: String, pattern: String): Long?
}
