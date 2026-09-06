package app.aaps.plugins.aps.openAPSAIMI.ISF

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Specification locks for [ObservedSensitivityMeter].
 *
 * These are **not** a red-before / green-after proof of a bug: the class is new, so every test here
 * simply writes down the rule the class must keep. The one test that documents a real mistake is
 * `a zero temp basal window credits less insulin than the raw iob drop`: without the basal integral
 * the meter would answer 20.0 instead of 31.6 on the same descent.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `eb84e078f5`. Study source set: [ObservedSensitivityMeter]
 * is commonMain and has no mocks, so the tests live in `commonTest` (`kotlin.test`) — same layout as
 * [DynIsfCacheTest], not `androidHostTest` (mockito/Robolectric).
 *
 * ## Why the closed window is 30 minutes and not the whole descent
 *
 * The meter keeps the **shortest** window that passes every filter, and it is fed one tick at a
 * time, so a window closes at the first tick where one is valid. On a straight-line descent that is
 * 30 minutes after the start. Every descent below is a straight line, so the sensitivity is the same
 * for any sub-window of it: the ratio the meter reports is exactly the ratio computed by hand over
 * the whole fall, while the fall and the insulin of the closed window are 6/11 of the whole-descent
 * figures.
 */
class ObservedSensitivityMeterTest {

    private val minute = 60 * 1000L
    private val hour = 60 * minute

    /** A fixed epoch. The hour of day is always passed in, so the real calendar does not matter. */
    private val t0 = 1_700_000_000_000L

    private val profileBasal = 1.0

    /**
     * Feeds one tick.
     *
     * Only what a test needs to say is passed; everything else stays on a neutral default: no carbs,
     * no SMB, temp basal equal to the profile basal, and a small known rate of appearance so the
     * fail-closed filter is satisfied.
     */
    private fun tick(
        meter: ObservedSensitivityMeter,
        timestampMs: Long,
        bgMgdl: Double,
        iobU: Double,
        hourOfDay: Int = 12,
        cobG: Double = 0.0,
        smbU: Double = 0.0,
        deliveredBasalUph: Double = profileBasal,
        raMgdlPerMin: Double? = 0.05,
    ): ObservedSensitivityMeter.Window? = meter.observe(
        ObservedSensitivityMeter.Sample(
            timestampMs = timestampMs,
            localHourOfDay = hourOfDay,
            bgMgdl = bgMgdl,
            iobU = iobU,
            cobG = cobG,
            smbU = smbU,
            deliveredBasalUph = deliveredBasalUph,
            profileBasalUph = profileBasal,
            raMgdlPerMin = raMgdlPerMin,
            lastBolusMs = 0L,
        ),
    )

    /**
     * Feeds a straight-line descent of [ticks] samples, 5 minutes apart, and returns the windows it
     * closed.
     */
    private fun descent(
        meter: ObservedSensitivityMeter,
        startMs: Long = t0,
        ticks: Int = 12,
        bgFrom: Double = 200.0,
        bgTo: Double = 150.0,
        iobFrom: Double = 3.0,
        iobTo: Double = 0.5,
        hourOfDay: Int = 12,
        cobG: Double = 0.0,
        deliveredBasalUph: Double = profileBasal,
        raMgdlPerMin: Double? = 0.05,
    ): List<ObservedSensitivityMeter.Window> {
        val closed = ArrayList<ObservedSensitivityMeter.Window>()
        val last = ticks - 1
        for (i in 0 until ticks) {
            val fraction = i.toDouble() / last
            tick(
                meter = meter,
                timestampMs = startMs + i * 5 * minute,
                bgMgdl = bgFrom + (bgTo - bgFrom) * fraction,
                iobU = iobFrom + (iobTo - iobFrom) * fraction,
                hourOfDay = hourOfDay,
                cobG = cobG,
                deliveredBasalUph = deliveredBasalUph,
                raMgdlPerMin = raMgdlPerMin,
            )?.let { closed.add(it) }
        }
        return closed
    }

    /**
     * T1 — the reference measurement.
     *
     * Glucose 200 to 150 while insulin on board goes 3.00 to 0.50 with the temp basal on the profile
     * rate: 50 mg/dL for 2.50 U absorbed, so 20.0 mg/dL per U. The closed window is the first valid
     * one, 30 minutes long, and it carries 6/11 of both figures — the ratio is unchanged.
     */
    @Test
    fun `a clean descent yields the hand computed sensitivity`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter)

        assertEquals(1, windows.size)
        val window = windows[0]
        assertEquals(20.0, window.isfMgdlPerU, 1e-9)
        assertEquals(30.0, window.minutes, 1e-9)
        assertEquals(50.0 * 6 / 11, window.dropMgdl, 1e-9)
        assertEquals(2.5 * 6 / 11, window.absorbedU, 1e-9)
    }

    /**
     * T2 — the basal integral, its sign and its size.
     *
     * The same descent, but the pump ran a zero temp basal against a profile basal of 1.0 U/h. Over
     * the 30 minutes of the closed window that is 0.5 U **less** than the profile, so the fall in IOB
     * over-states what was really absorbed and the integral must be subtracted, not added.
     *
     * Absorbed becomes 1.3636 - 0.5 = 0.8636 U and the sensitivity 31.6 mg/dL per U. Over the whole
     * 55-minute descent the same arithmetic gives an integral of -0.917 U, 1.583 U absorbed and the
     * same 31.6.
     *
     * Without the integral the meter would answer 20.0; with the sign flipped, 14.6.
     */
    @Test
    fun `a zero temp basal window credits less insulin than the raw iob drop`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, deliveredBasalUph = 0.0)

        assertEquals(1, windows.size)
        val window = windows[0]
        assertEquals(600.0 / 19.0, window.isfMgdlPerU, 1e-9)
        assertEquals(2.5 * 6 / 11 - 0.5, window.absorbedU, 1e-9)
    }

    /**
     * T3 — the half-open interval.
     *
     * An SMB of 0.5 U is decided in the middle of the window. It is not inside the IOB of the tick
     * that decided it, it is inside the IOB of every later tick. So it must be credited once: added
     * as an SMB, and cancelled by the higher IOB at the end of the window.
     *
     * Forgetting it gives 31.6, counting it twice gives 14.6.
     */
    @Test
    fun `an smb inside the window is added to the absorbed insulin`() {
        val meter = ObservedSensitivityMeter()
        val smbAtTick = 3
        var closed: ObservedSensitivityMeter.Window? = null

        for (i in 0 until 12) {
            val fraction = i / 11.0
            val rawIob = 3.0 - 2.5 * fraction
            tick(
                meter = meter,
                timestampMs = t0 + i * 5 * minute,
                bgMgdl = 200.0 - 50.0 * fraction,
                // The SMB decided at tick 3 shows up in the IOB from tick 4 on.
                iobU = if (i > smbAtTick) rawIob + 0.5 else rawIob,
                smbU = if (i == smbAtTick) 0.5 else 0.0,
            )?.let { closed = it }
        }

        assertNotNull(closed)
        assertEquals(20.0, closed!!.isfMgdlPerU, 1e-9)
        assertEquals(2.5 * 6 / 11, closed!!.absorbedU, 1e-9)
    }

    /** T4 — 20 minutes of fall is a slope, not a measurement. */
    @Test
    fun `a window shorter than thirty minutes is rejected`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, ticks = 5)

        assertTrue(windows.isEmpty())
    }

    /** T5 — a 20 mg/dL fall is below the noise the corpus study accepted. */
    @Test
    fun `a fall below twenty five mgdl is rejected`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, bgTo = 180.0)

        assertTrue(windows.isEmpty())
    }

    /** T6 — digestion hides part of the fall, so the sensitivity would read low. */
    @Test
    fun `carbs on board reject the window`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, cobG = 10.0)

        assertTrue(windows.isEmpty())
    }

    /**
     * T7 — a meal that ended just before the window still absorbs inside it.
     *
     * Six ticks with carbs, then a clean 30-minute fall. The control run, identical but with no carbs
     * at all, does close a window, so the rejection is really the run-up rule and not the shape of
     * the data.
     */
    @Test
    fun `carbs in the run up reject the window`() {
        val withRunUpCarbs = ObservedSensitivityMeter()
        val control = ObservedSensitivityMeter()
        val closed = ArrayList<ObservedSensitivityMeter.Window>()
        val controlClosed = ArrayList<ObservedSensitivityMeter.Window>()

        for (i in 0 until 13) {
            val fraction = i / 12.0
            val bg = 260.0 - 120.0 * fraction
            val iob = 6.0 - 5.5 * fraction
            val at = t0 + i * 5 * minute
            tick(withRunUpCarbs, at, bg, iob, cobG = if (i < 6) 20.0 else 0.0)?.let { closed.add(it) }
            tick(control, at, bg, iob)?.let { controlClosed.add(it) }
        }

        assertTrue(closed.isEmpty())
        assertTrue(controlClosed.isNotEmpty())
    }

    /** T8 — a fall that cost almost no insulin measures the liver, not the insulin. */
    @Test
    fun `less than zero point eight units absorbed rejects the window`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, iobFrom = 3.0, iobTo = 2.9)

        assertTrue(windows.isEmpty())
    }

    /** T9 — glucose still appearing means something other than insulin is driving the curve. */
    @Test
    fun `an appearance rate above the threshold rejects the window`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, raMgdlPerMin = 0.5)

        assertTrue(windows.isEmpty())
    }

    /**
     * T10 — fail-closed.
     *
     * With no known rate of appearance the meal filter cannot be checked. An unchecked filter is not
     * a passed filter, so the window is refused. Everything else about this descent is perfect.
     */
    @Test
    fun `a window with no known appearance rate is rejected`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(meter, raMgdlPerMin = null)

        assertTrue(windows.isEmpty())
    }

    /**
     * T11 — no sample may be counted twice.
     *
     * Three hours of steady fall. Two overlapping windows would count the same insulin and the same
     * fall twice, and the median would then be built from copies of one event.
     */
    @Test
    fun `windows never overlap`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(
            meter,
            ticks = 37,
            bgFrom = 320.0,
            bgTo = 140.0,
            iobFrom = 6.0,
            iobTo = 0.5,
        )

        assertTrue(windows.size >= 2)
        for (i in 0 until windows.size - 1) {
            assertTrue(windows[i + 1].startMs >= windows[i].endMs)
        }
    }

    /** T12 — the two strata are told apart, and both are counted. */
    @Test
    fun `a night window and a day window land in different strata`() {
        val meter = ObservedSensitivityMeter()

        val night = descent(meter, startMs = t0 + 2 * hour, ticks = 7, hourOfDay = 2)
        val day = descent(meter, startMs = t0 + 14 * hour, ticks = 7, hourOfDay = 14)

        assertEquals(1, night.size)
        assertEquals(1, day.size)
        assertEquals(ObservedSensitivityMeter.Stratum.NIGHT, night[0].stratum)
        assertEquals(ObservedSensitivityMeter.Stratum.DAY, day[0].stratum)

        val reading = meter.read(nowMs = t0 + 15 * hour)
        assertEquals(2, reading.windowCount)
        assertEquals(1, reading.nightCount)
        assertEquals(1, reading.dayCount)
    }

    /**
     * T13 — the middle decides, not the start.
     *
     * The window runs from 07:50 to 08:20, so it starts in the night and ends in the day. Its middle
     * is 08:05, so it is a day window. Classifying by the start would put it in the night.
     */
    @Test
    fun `a window straddling eight in the morning is classified by its midpoint`() {
        val meter = ObservedSensitivityMeter()
        val start = t0 + 7 * hour + 50 * minute
        var closed: ObservedSensitivityMeter.Window? = null

        for (i in 0 until 7) {
            val fraction = i / 6.0
            val at = start + i * 5 * minute
            // 07:50 and 07:55 are hour 7; 08:00 onwards is hour 8.
            val hourOfDay = if (i < 2) 7 else 8
            tick(
                meter = meter,
                timestampMs = at,
                bgMgdl = 200.0 - 50.0 * fraction,
                iobU = 3.0 - 2.5 * fraction,
                hourOfDay = hourOfDay,
            )?.let { closed = it }
        }

        assertNotNull(closed)
        assertEquals(30.0, closed!!.minutes, 1e-9)
        assertEquals(ObservedSensitivityMeter.Stratum.DAY, closed!!.stratum)
    }

    /** T14 — nothing measured must read as nothing, never as a sensitivity of zero. */
    @Test
    fun `no window yields a null median and a zero count`() {
        val meter = ObservedSensitivityMeter()

        val reading = meter.read(nowMs = t0)

        assertNull(reading.medianMgdlPerU)
        assertNull(reading.nightMedianMgdlPerU)
        assertNull(reading.dayMedianMgdlPerU)
        assertEquals(0, reading.windowCount)
        assertEquals(0, reading.nightCount)
        assertEquals(0, reading.dayCount)
        assertNull(reading.lastWindow)
    }

    /**
     * T15 — a median of two windows is not a median.
     *
     * The count is still reported, so a reader can see how far the instrument is from being able to
     * answer.
     */
    @Test
    fun `fewer than three windows yields a null median but a real count`() {
        val meter = ObservedSensitivityMeter()

        val windows = descent(
            meter,
            ticks = 13,
            bgFrom = 320.0,
            bgTo = 260.0,
            iobFrom = 6.0,
            iobTo = 4.0,
        )
        val reading = meter.read(nowMs = t0 + 2 * hour)

        assertEquals(2, windows.size)
        assertEquals(2, reading.windowCount)
        assertEquals(2, reading.dayCount)
        assertNull(reading.medianMgdlPerU)
        assertNull(reading.dayMedianMgdlPerU)
    }

    /**
     * T16 — a hole makes the basal integral unknowable.
     *
     * The same seven ticks close a window when they are evenly spaced. Move the last one 20 minutes
     * out and the buffer is dropped, because nobody knows what the pump did during the hole.
     */
    @Test
    fun `a gap longer than twelve minutes prevents a window across it`() {
        val withGap = ObservedSensitivityMeter()
        val control = ObservedSensitivityMeter()
        var gapClosed: ObservedSensitivityMeter.Window? = null
        var controlClosed: ObservedSensitivityMeter.Window? = null

        for (i in 0 until 7) {
            val fraction = i / 6.0
            val bg = 200.0 - 50.0 * fraction
            val iob = 3.0 - 2.5 * fraction
            val evenly = t0 + i * 5 * minute
            val delayed = if (i == 6) t0 + 45 * minute else evenly
            tick(withGap, delayed, bg, iob)?.let { gapClosed = it }
            tick(control, evenly, bg, iob)?.let { controlClosed = it }
        }

        assertNull(gapClosed)
        assertNotNull(controlClosed)
    }

    /** T17 — a replay or a clock that steps back must not enter the buffer. */
    @Test
    fun `an out of order sample is ignored`() {
        val meter = ObservedSensitivityMeter()

        for (i in 0 until 6) {
            val fraction = i / 6.0
            tick(meter, t0 + i * 5 * minute, 200.0 - 50.0 * fraction, 3.0 - 2.5 * fraction)
        }
        // A sample from the past, with values that would wreck any window it entered.
        val replayed = tick(meter, t0 + 15 * minute, 500.0, 30.0)
        val closed = tick(meter, t0 + 30 * minute, 150.0, 0.5)

        assertNull(replayed)
        assertNotNull(closed)
        assertEquals(20.0, closed!!.isfMgdlPerU, 1e-9)
        assertEquals(30.0, closed!!.minutes, 1e-9)
    }

    /** T18 — a window that fell out of the retention window is gone, not merely hidden. */
    @Test
    fun `windows older than the retention are dropped`() {
        val meter = ObservedSensitivityMeter(windowRetentionMs = hour)

        val first = descent(meter, startMs = t0, ticks = 7)
        val second = descent(meter, startMs = t0 + 5 * hour, ticks = 7)
        val reading = meter.read(nowMs = t0 + 6 * hour, lookbackMs = 7 * 24 * hour)

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertEquals(1, reading.windowCount)
        assertEquals(second[0].endMs, reading.lastWindow?.endMs)
    }
}
