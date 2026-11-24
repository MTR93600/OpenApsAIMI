package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The night heart rate, and above all the cases where it refuses to give a value: too little data,
 * a walk at night, a second device, too few earlier nights.
 */
class NocturnalHeartRateTest {

    private val zone: TimeZone = TimeZone.of("Europe/Paris")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 11, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    /** One reading every 10 minutes from 00:00, for [slots] slots (36 slots fill the whole window). */
    private fun night(
        day: Int,
        bpm: Double,
        device: String = WATCH,
        slots: Int = 36,
        steps: Int? = 0,
    ): List<NocturnalHeartRate.Sample> =
        (0 until slots).map { index ->
            NocturnalHeartRate.Sample(
                timestampMs = at(day, index / 6, (index % 6) * 10 + 2),
                bpm = bpm,
                device = device,
                stepsLast15m = steps,
            )
        }

    /** Morning of day 24, after the window: the night of day 24 is complete. */
    private val morning = at(24, 9)

    @Test
    fun `a full night gives its value and the date of its morning`() {
        val report = NocturnalHeartRate.report(night(24, 58.0), morning, zone)

        assertEquals(LocalDate(2026, 11, 24), report.night)
        assertEquals(WATCH, report.device)
        assertEquals(360, report.coveredMinutes)
        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `less than three hours covered gives no value`() {
        val report = NocturnalHeartRate.report(night(24, 58.0, slots = 17), morning, zone)

        assertEquals(170, report.coveredMinutes)
        assertNull(report.bpm)
        assertNull(report.baselineBpm)
        assertNull(report.deltaBpm)
    }

    @Test
    fun `exactly three hours covered is enough`() {
        val report = NocturnalHeartRate.report(night(24, 58.0, slots = 18), morning, zone)

        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `no reading at all gives an empty report`() {
        val report = NocturnalHeartRate.report(emptyList(), morning, zone)

        assertEquals(LocalDate(2026, 11, 24), report.night)
        assertNull(report.device)
        assertEquals(0, report.coveredMinutes)
        assertNull(report.bpm)
    }

    @Test
    fun `before six in the morning the report is about the previous night`() {
        val samples = night(23, 55.0) + night(24, 70.0, slots = 20)

        val report = NocturnalHeartRate.report(samples, at(24, 4), zone)

        assertEquals(LocalDate(2026, 11, 23), report.night)
        assertEquals(55.0, report.bpm)
    }

    @Test
    fun `readings outside midnight to six are ignored`() {
        val evening = (0 until 30).map { NocturnalHeartRate.Sample(at(23, 22, it), 90.0, WATCH, 0) }
        val lateMorning = (0 until 30).map { NocturnalHeartRate.Sample(at(24, 6, it), 90.0, WATCH, 0) }

        val report = NocturnalHeartRate.report(night(24, 58.0) + evening + lateMorning, morning, zone)

        assertEquals(58.0, report.bpm)
        assertEquals(360, report.coveredMinutes)
    }

    @Test
    fun `readings taken while walking are dropped`() {
        val walking = night(24, 110.0, slots = 36, steps = 400).map { it.copy(timestampMs = it.timestampMs + 60_000L) }

        val report = NocturnalHeartRate.report(night(24, 58.0) + walking, morning, zone)

        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `a reading with unknown steps is kept`() {
        val report = NocturnalHeartRate.report(night(24, 58.0, steps = null), morning, zone)

        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `implausible readings are dropped`() {
        val broken = listOf(0.0, 10.0, 300.0, Double.NaN).flatMap { bpm ->
            night(24, bpm).map { it.copy(timestampMs = it.timestampMs + 30_000L) }
        }

        val report = NocturnalHeartRate.report(night(24, 58.0) + broken, morning, zone)

        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `a single bad reading in a slot does not move the night`() {
        val spike = NocturnalHeartRate.Sample(at(24, 2, 3), 140.0, WATCH, 0)

        val report = NocturnalHeartRate.report(night(24, 58.0) + spike, morning, zone)

        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `the device that covers the most of the night is used alone`() {
        val copy = night(24, 70.0, device = HEALTH_STORE, slots = 20)

        val report = NocturnalHeartRate.report(night(24, 58.0) + copy, morning, zone)

        assertEquals(WATCH, report.device)
        assertEquals(58.0, report.bpm)
    }

    @Test
    fun `two devices with the same coverage give a stable choice`() {
        val a = night(24, 58.0, device = "B-device")
        val b = night(24, 62.0, device = "A-device")

        assertEquals("A-device", NocturnalHeartRate.report(a + b, morning, zone).device)
        assertEquals("A-device", NocturnalHeartRate.report(b + a, morning, zone).device)
    }

    @Test
    fun `five earlier nights give a baseline and a delta`() {
        val earlier = listOf(19 to 54.0, 20 to 55.0, 21 to 53.0, 22 to 54.0, 23 to 56.0)
            .flatMap { (day, bpm) -> night(day, bpm) }

        val report = NocturnalHeartRate.report(earlier + night(24, 58.0), morning, zone)

        assertEquals(5, report.baselineNights)
        assertEquals(54.0, report.baselineBpm)
        assertEquals(4.0, report.deltaBpm)
    }

    @Test
    fun `four earlier nights give no baseline yet`() {
        val earlier = (20..23).flatMap { night(it, 54.0) }

        val report = NocturnalHeartRate.report(earlier + night(24, 58.0), morning, zone)

        assertEquals(4, report.baselineNights)
        assertNull(report.baselineBpm)
        assertNull(report.deltaBpm)
    }

    @Test
    fun `earlier nights of another device do not count in the baseline`() {
        val ring = (17..23).flatMap { night(it, 50.0, device = "Ring") }

        val report = NocturnalHeartRate.report(ring + night(24, 58.0), morning, zone)

        assertEquals(WATCH, report.device)
        assertEquals(0, report.baselineNights)
        assertNull(report.baselineBpm)
    }

    @Test
    fun `earlier nights with too little coverage do not count in the baseline`() {
        val earlier = (18..22).flatMap { night(it, 54.0) } + night(23, 90.0, slots = 10)

        val report = NocturnalHeartRate.report(earlier + night(24, 58.0), morning, zone)

        assertEquals(5, report.baselineNights)
        assertEquals(54.0, report.baselineBpm)
    }

    @Test
    fun `nights older than the baseline window are ignored`() {
        // Day 24 minus 14 is day 10, the oldest night kept. Days 5 to 9 are too old.
        val old = (5..9).flatMap { night(it, 40.0) }
        val recent = (10..14).flatMap { night(it, 54.0) }

        val report = NocturnalHeartRate.report(old + recent + night(24, 58.0), morning, zone)

        assertEquals(5, report.baselineNights)
        assertEquals(54.0, report.baselineBpm)
    }

    @Test
    fun `no baseline is given for a night without a value`() {
        val earlier = (18..23).flatMap { night(it, 54.0) }

        val report = NocturnalHeartRate.report(earlier + night(24, 58.0, slots = 5), morning, zone)

        assertNull(report.bpm)
        assertNull(report.baselineBpm)
        assertEquals(6, report.baselineNights)
    }

    @Test
    fun `status line before the first measurement`() {
        assertEquals("🌙 Night HR: -- (not measured yet)", NocturnalHeartRate.statusLine(null))
    }

    @Test
    fun `status line without any night reading`() {
        val report = NocturnalHeartRate.report(emptyList(), morning, zone)

        assertEquals("🌙 Night HR: -- (no reading between 00:00 and 06:00)", NocturnalHeartRate.statusLine(report))
    }

    @Test
    fun `status line says how much is covered when it is too little`() {
        val report = NocturnalHeartRate.report(night(24, 58.0, slots = 12), morning, zone)

        assertEquals("🌙 Night HR: -- (2.0 h covered, needs 3 h)", NocturnalHeartRate.statusLine(report))
    }

    @Test
    fun `status line while the usual night is still being learned`() {
        val samples = (22..23).flatMap { night(it, 54.0) } + night(24, 58.0)

        assertEquals(
            "🌙 Night HR: 58.0 bpm | usual night after 5 nights (2/5) | Garmin-Watchface, 6.0 h | no effect on dosing",
            NocturnalHeartRate.statusLine(NocturnalHeartRate.report(samples, morning, zone)),
        )
    }

    @Test
    fun `status line with a usual night shows the signed difference`() {
        val higher = (19..23).flatMap { night(it, 54.0) } + night(24, 58.0)
        val lower = (19..23).flatMap { night(it, 54.0) } + night(24, 51.0)

        assertEquals(
            "🌙 Night HR: 58.0 bpm (+4.0 vs usual 54.0, 2.0 SD, 5 nights) | Garmin-Watchface, 6.0 h | no effect on dosing",
            NocturnalHeartRate.statusLine(NocturnalHeartRate.report(higher, morning, zone)),
        )
        assertEquals(
            "🌙 Night HR: 51.0 bpm (-3.0 vs usual 54.0, -1.5 SD, 5 nights) | Garmin-Watchface, 6.0 h | no effect on dosing",
            NocturnalHeartRate.statusLine(NocturnalHeartRate.report(lower, morning, zone)),
        )
    }

    @Test
    fun `status line says when the night acts on the dose`() {
        val samples = (19..23).flatMap { night(it, 54.0) } + night(24, 62.0)

        assertEquals(
            "🌙 Night HR: 62.0 bpm (+8.0 vs usual 54.0, 4.0 SD, 5 nights) | Garmin-Watchface, 6.0 h | " +
                "ELEVATED: short-term reactivity weighs more until 23:00",
            NocturnalHeartRate.statusLine(NocturnalHeartRate.report(samples, morning, zone), elevated = true),
        )
    }

    // --- spread and alert -------------------------------------------------------------------------

    /** Seven earlier nights, days 17 to 23, with the given values. */
    private fun sevenNights(vararg bpm: Double): List<NocturnalHeartRate.Sample> =
        bpm.toList().mapIndexed { index, value -> night(17 + index, value) }.flatten()

    @Test
    fun `the spread is the robust standard deviation of the earlier nights`() {
        // Median 54. Absolute deviations 2 2 1 0 1 2 0 -> median 1 -> 1.4826 bpm, below the 2 bpm floor.
        val calm = NocturnalHeartRate.report(sevenNights(52.0, 56.0, 53.0, 54.0, 55.0, 52.0, 54.0) + night(24, 58.0), morning, zone)
        // Median 54. Absolute deviations 4 4 3 0 3 4 0 -> median 3 -> 4.4478 bpm.
        val noisy = NocturnalHeartRate.report(sevenNights(50.0, 58.0, 51.0, 54.0, 57.0, 50.0, 54.0) + night(24, 58.0), morning, zone)

        assertEquals(NocturnalHeartRate.MIN_SPREAD_BPM, calm.spreadBpm)
        assertEquals(2.0, calm.zScore)
        assertEquals(4.4478, noisy.spreadBpm!!, 1e-9)
        assertEquals(0.9, NocturnalHeartRate.round1(noisy.zScore!!))
    }

    @Test
    fun `no spread without a baseline`() {
        val report = NocturnalHeartRate.report(night(24, 58.0), morning, zone)

        assertNull(report.spreadBpm)
        assertNull(report.zScore)
    }

    private val calmWeek = sevenNights(54.0, 54.0, 55.0, 53.0, 54.0, 54.0, 55.0)

    @Test
    fun `a night well above a calm usual night is elevated`() {
        val report = NocturnalHeartRate.report(calmWeek + night(24, 59.0), morning, zone)

        assertEquals(5.0, report.deltaBpm)
        assertTrue(NocturnalHeartRate.isElevated(report, morning, zone))
    }

    @Test
    fun `a rise under four beats is not elevated even with a small spread`() {
        val report = NocturnalHeartRate.report(calmWeek + night(24, 57.5), morning, zone)

        assertFalse(NocturnalHeartRate.isElevated(report, morning, zone))
    }

    @Test
    fun `a rise inside a noisy usual night is not elevated`() {
        val noisyWeek = sevenNights(48.0, 60.0, 50.0, 54.0, 58.0, 49.0, 59.0)
        val report = NocturnalHeartRate.report(noisyWeek + night(24, 60.0), morning, zone)

        assertEquals(6.0, report.deltaBpm)
        assertFalse(NocturnalHeartRate.isElevated(report, morning, zone))
    }

    @Test
    fun `a lower night is never elevated`() {
        val report = NocturnalHeartRate.report(calmWeek + night(24, 46.0), morning, zone)

        assertFalse(NocturnalHeartRate.isElevated(report, morning, zone))
    }

    @Test
    fun `six earlier nights are not enough for an alert`() {
        val sixNights = (18..23).flatMap { night(it, 54.0) }
        val report = NocturnalHeartRate.report(sixNights + night(24, 62.0), morning, zone)

        assertEquals(6, report.baselineNights)
        assertFalse(NocturnalHeartRate.isElevated(report, morning, zone))
    }

    @Test
    fun `the alert ends at eleven in the evening`() {
        val samples = calmWeek + night(24, 62.0)

        assertTrue(NocturnalHeartRate.isElevated(NocturnalHeartRate.report(samples, at(24, 22, 59), zone), at(24, 22, 59), zone))
        assertFalse(NocturnalHeartRate.isElevated(NocturnalHeartRate.report(samples, at(24, 23), zone), at(24, 23), zone))
    }

    @Test
    fun `a report about an older night gives no alert`() {
        val report = NocturnalHeartRate.report(calmWeek + night(24, 62.0), morning, zone)

        // The same report read the next day, before a new one was measured.
        assertFalse(NocturnalHeartRate.isElevated(report, at(25, 9), zone))
    }

    @Test
    fun `before six the running night gives no alert`() {
        val samples = calmWeek + night(24, 62.0)

        // Before 06:00 the report is about the night of day 23, which is not today.
        assertFalse(NocturnalHeartRate.isElevated(NocturnalHeartRate.report(samples, at(24, 5), zone), at(24, 5), zone))
    }

    @Test
    fun `no report gives no alert`() {
        assertFalse(NocturnalHeartRate.isElevated(null, morning, zone))
    }

    @Test
    fun `round1 keeps one decimal`() {
        assertEquals(57.3, NocturnalHeartRate.round1(57.34))
        assertEquals(57.4, NocturnalHeartRate.round1(57.36))
    }

    private companion object {
        const val WATCH = "Garmin-Watchface"
        const val HEALTH_STORE = "HealthStore"
    }
}
