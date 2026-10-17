package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDateFormatter
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/**
 * [AimiDateFormatter] on `NSDateFormatter`: device locale and timezone for display, fixed US
 * locale for parsing machine-generated text. Same split as `AndroidDateFormatter`.
 */
internal class IosDateFormatter : AimiDateFormatter {

    override fun format(mills: Long, pattern: String): String {
        val formatter = NSDateFormatter().apply {
            dateFormat = pattern
            locale = NSLocale.currentLocale
        }
        return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(mills / 1000.0))
    }

    override fun parse(text: String, pattern: String): Long? {
        val formatter = NSDateFormatter().apply {
            dateFormat = pattern
            locale = NSLocale("en_US")
        }
        val date = formatter.dateFromString(text) ?: return null
        return (date.timeIntervalSince1970 * 1000.0).toLong()
    }
}
