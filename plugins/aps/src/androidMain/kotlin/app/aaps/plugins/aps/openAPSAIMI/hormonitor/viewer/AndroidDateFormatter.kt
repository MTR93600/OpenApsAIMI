package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDateFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [AimiDateFormatter] on `SimpleDateFormat`: device locale and timezone for display,
 * fixed US locale for parsing machine-generated text.
 */
internal class AndroidDateFormatter : AimiDateFormatter {
    override fun format(mills: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(mills))

    override fun parse(text: String, pattern: String): Long? = runCatching {
        SimpleDateFormat(pattern, Locale.US).parse(text)?.time
    }.getOrNull()
}
