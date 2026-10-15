package app.aaps.pump.medtrum.ios

import app.aaps.pump.medtrum.session.LocalMedtrumClock
import app.aaps.pump.medtrum.session.MedtrumClock
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.resetSystemTimeZone
import platform.Foundation.systemTimeZone
import platform.Foundation.timeIntervalSince1970

/**
 * The production [MedtrumClock] for iOS: the wall clock and the phone time zone.
 *
 * The system time zone is cached by iOS. We reset it before each read, so a trip to another time
 * zone or a daylight saving change is seen. The read is rare (basal rate, connect), so the cost is fine.
 */
fun systemMedtrumClock(): MedtrumClock = LocalMedtrumClock(
    now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    offsetMinutesAt = { millis ->
        NSTimeZone.resetSystemTimeZone()
        val date = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)
        (NSTimeZone.systemTimeZone.secondsFromGMTForDate(date) / 60).toInt()
    },
)
