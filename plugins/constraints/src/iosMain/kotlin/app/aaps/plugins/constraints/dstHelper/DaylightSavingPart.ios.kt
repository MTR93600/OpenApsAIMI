package app.aaps.plugins.constraints.dstHelper

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeZoneWithName

/** The iOS twin of `Calendar.DST_OFFSET`: `NSTimeZone.daylightSavingTimeOffsetForDate`, in seconds there. */
internal actual fun daylightSavingPartMs(zoneId: String, epochMs: Long): Long {
    val zone = NSTimeZone.timeZoneWithName(zoneId) ?: NSTimeZone.localTimeZone
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    return (zone.daylightSavingTimeOffsetForDate(date) * 1000.0).toLong()
}
