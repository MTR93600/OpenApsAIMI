package app.aaps.plugins.constraints.dstHelper

import java.util.Calendar
import java.util.TimeZone

/** `Calendar.DST_OFFSET`, the value the Android plugin compared before it moved to shared code. */
internal actual fun daylightSavingPartMs(zoneId: String, epochMs: Long): Long {
    val calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId))
    calendar.timeInMillis = epochMs
    return calendar[Calendar.DST_OFFSET].toLong()
}
