package app.aaps.pump.medtrum.session

/**
 * The real clock for a driver. Two small platform functions come in, the day maths is shared.
 *
 * [now] gives the wall clock in millis since epoch. [offsetMinutesAt] gives the phone offset from
 * UTC at a given time, in minutes, with daylight saving. Medtrum basal schedules run on the pump
 * local time, so [minuteOfDay] must be local time, not UTC.
 */
class LocalMedtrumClock(
    private val now: () -> Long,
    private val offsetMinutesAt: (Long) -> Int,
) : MedtrumClock {

    override fun nowMillis(): Long = now()

    override fun utcOffsetMinutes(millis: Long): Int = offsetMinutesAt(millis)

    override fun minuteOfDay(millis: Long): Int {
        val localMinutes = millis.floorDiv(MILLIS_PER_MINUTE) + offsetMinutesAt(millis)
        return localMinutes.mod(MINUTES_PER_DAY).toInt()
    }

    private companion object {

        const val MILLIS_PER_MINUTE = 60_000L
        const val MINUTES_PER_DAY = 1_440L
    }
}
