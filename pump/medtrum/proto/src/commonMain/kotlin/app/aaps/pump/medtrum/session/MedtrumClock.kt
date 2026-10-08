package app.aaps.pump.medtrum.session

/**
 * Clock seam for the Medtrum session.
 *
 * The Android driver uses `System.currentTimeMillis()` / `DateUtil.now()`.
 * This interface keeps the session portable; M3 supplies the platform clock,
 * tests pin it.
 */
fun interface MedtrumClock {

    /** Current wall-clock time in millis since epoch. */
    fun nowMillis(): Long

    /**
     * Minutes since local midnight for [millis].
     * Default 0 — tests pin it; M3 supplies the platform calendar.
     */
    fun minuteOfDay(millis: Long): Int = 0

    companion object {

        /** Fixed clock for tests. */
        fun fixed(millis: Long, minuteOfDay: Int = 0): MedtrumClock = object : MedtrumClock {
            override fun nowMillis(): Long = millis
            override fun minuteOfDay(millis: Long): Int = minuteOfDay
        }
    }
}
