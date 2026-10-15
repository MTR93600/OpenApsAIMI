package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ratio must move only on evidence, move slowly, and stay inside the domain where the profile
 * still means something. See `docs/adr/0008-isf-decision-architecture.md`.
 */
class SensitivityRatioEstimatorTest {

    private val profileIsf = 30.0
    private val profileBasal = 0.6

    /** A fresh estimator with its own storage, so persistence never leaks between tests. */
    private fun newEstimator(storage: InMemoryAimiStorage = InMemoryAimiStorage()): SensitivityRatioEstimator =
        SensitivityRatioEstimator(storage)

    private fun feed(
        estimator: SensitivityRatioEstimator,
        minutes: Int,
        bgStart: Double,
        bgEnd: Double,
        iobStart: Double,
        iobEnd: Double,
        deliveredBasal: Double = profileBasal,
        smbAt: Set<Int> = emptySet(),
        startMs: Long = 0L,
        cobG: Double = 0.0,
        lastBolusMs: Long = 0L,
    ): Long {
        val steps = minutes / 5
        var last = startMs
        for (i in 0..steps) {
            val f = i.toDouble() / steps
            last = startMs + i * 5L * 60_000L
            estimator.observe(
                SensitivityRatioEstimator.Sample(
                    timestampMs = last,
                    bgMgdl = bgStart + (bgEnd - bgStart) * f,
                    iobU = iobStart + (iobEnd - iobStart) * f,
                    profileBasalUph = profileBasal,
                    deliveredBasalUph = deliveredBasal,
                    smbU = if (i in smbAt) 0.5 else 0.0,
                    profileIsfMgdl = profileIsf,
                    cobG = cobG,
                    lastBolusMs = lastBolusMs,
                ),
            )
        }
        return last
    }

    @Test
    fun `starts neutral and trusts the profile until something is measured`() {
        val estimator = newEstimator()

        assertEquals(1.0, estimator.ratio, 1e-9)
        assertEquals(0, estimator.observationCount)
        assertEquals(profileIsf, estimator.sensitivityMgdl(profileIsf), 1e-9)
    }

    @Test
    fun `a clean falling window with no bolus produces an observation`() {
        val estimator = newEstimator()

        // 30 min of quiet run-up, then a 50 min window: BG 140 -> 110 while IOB drains 2.0 -> 1.0.
        val afterRunUp = feed(estimator, minutes = 30, bgStart = 140.0, bgEnd = 140.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 140.0, bgEnd = 110.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertTrue(estimator.observationCount >= 1)
        val obs = requireNonNull(estimator.lastObservation)
        assertTrue(obs.bgDropMgdl > 0.0)
        assertTrue(obs.insulinActedU > SensitivityRatioEstimator.MIN_ACTED_U)
    }

    @Test
    fun `a bolus inside the window disqualifies it`() {
        val estimator = newEstimator()

        // Flat run-up: no fall, so nothing observable comes out of it on its own.
        val afterRunUp = feed(estimator, minutes = 60, bgStart = 140.0, bgEnd = 140.0, iobStart = 2.4, iobEnd = 2.2)
        // The bolus lands early enough that every window reaching the fall also contains it.
        feed(
            estimator, minutes = 50, bgStart = 140.0, bgEnd = 110.0, iobStart = 2.2, iobEnd = 1.2,
            smbAt = setOf(1), startMs = afterRunUp + 300_000L,
        )

        assertEquals(0, estimator.observationCount)
        assertEquals(1.0, estimator.ratio, 1e-9)
    }

    @Test
    fun `a rising window is rejected because carbs may be arriving`() {
        val estimator = newEstimator()

        val afterRunUp = feed(estimator, minutes = 30, bgStart = 110.0, bgEnd = 110.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 110.0, bgEnd = 150.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertEquals(0, estimator.observationCount)
    }

    @Test
    fun `the basal deficit is not credited with the fall`() {
        val full = newEstimator()
        val zeroBasal = newEstimator()

        for (estimator in listOf(full, zeroBasal)) {
            val delivered = if (estimator === zeroBasal) 0.0 else profileBasal
            val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0, deliveredBasal = delivered)
            feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, deliveredBasal = delivered, startMs = afterRunUp + 300_000L)
        }

        // Same fall, same IOB decay: with the loop at zero basal, less insulin actually acted, so
        // each unit did more, so the measured sensitivity is higher.
        val withDeficit = requireNonNull(zeroBasal.lastObservation).ratio
        val withoutDeficit = requireNonNull(full.lastObservation).ratio
        assertTrue(withDeficit > withoutDeficit)
    }

    @Test
    fun `one observation moves the state only slightly`() {
        val estimator = newEstimator()

        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0)
        feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, startMs = afterRunUp + 300_000L)

        // Sensitivity is a slow state: a single window must not swing it.
        assertTrue(estimator.ratio >= 0.9)
        assertTrue(estimator.ratio <= 1.1)
    }

    @Test
    fun `the commanded sensitivity stays inside the band around the profile`() {
        val estimator = newEstimator()

        assertTrue(estimator.sensitivityMgdl(profileIsf) >= profileIsf * SensitivityRatioEstimator.MIN_RATIO)
        assertTrue(estimator.sensitivityMgdl(profileIsf) <= profileIsf * SensitivityRatioEstimator.MAX_RATIO)
    }

    @Test
    fun `the BG modulation is neutral at the reference and lowers sensitivity when high`() {
        val atReference = SensitivityRatioEstimator.bgModulation(
            SensitivityRatioEstimator.MODULATION_REFERENCE_MGDL,
        )
        assertEquals(1.0, atReference, 1e-9)
        assertTrue(SensitivityRatioEstimator.bgModulation(200.0) < 1.0)
        assertTrue(SensitivityRatioEstimator.bgModulation(70.0) > 1.0)
    }

    @Test
    fun `carbs on board disqualify the window even when nothing was bolused by the loop`() {
        val estimator = newEstimator()

        // Exactly the window that qualifies above, with digestion running. The absorption offsets
        // part of the fall, so the ratio would read low, so the sensitivity commanded from it would
        // read low, so the loop would give more insulin. That is the direction that hurts.
        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0, cobG = 20.0)
        feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, startMs = afterRunUp + 300_000L, cobG = 20.0)

        assertEquals(0, estimator.observationCount)
        assertEquals(1.0, estimator.ratio, 1e-9)
    }

    @Test
    fun `a bolus of any origin disqualifies the window even with no SMB from the loop`() {
        val estimator = newEstimator()

        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0)
        // A manual meal bolus, given during the run-up. `smbU` never sees it: it only carries what
        // AIMI itself commanded.
        val bolusMs = afterRunUp - 10 * 60_000L
        feed(
            estimator, 50, 140.0, 110.0, 2.0, 1.0,
            startMs = afterRunUp + 300_000L, lastBolusMs = bolusMs,
        )

        assertEquals(0, estimator.observationCount)
    }

    @Test
    fun `an old bolus outside the run-up does not disqualify the window`() {
        val estimator = newEstimator()

        // Far enough from the epoch that "four hours ago" is a real instant.
        val base = 12L * 3_600_000L
        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0, startMs = base)
        // Four hours before the run-up: long gone, and the window is still readable.
        feed(
            estimator, 50, 140.0, 110.0, 2.0, 1.0,
            startMs = afterRunUp + 300_000L, lastBolusMs = base - 4L * 3_600_000L,
        )

        assertTrue(estimator.observationCount >= 1)
    }

    @Test
    fun `overlapping windows from one episode cannot be folded repeatedly`() {
        val estimator = newEstimator()

        // A long steady fall: many consecutive ticks each close a qualifying window over the same
        // episode. Without a minimum spacing the state would take one step per tick.
        val afterRunUp = feed(estimator, 30, 200.0, 200.0, 3.2, 3.0)
        feed(estimator, 120, 200.0, 120.0, 3.0, 1.0, startMs = afterRunUp + 300_000L)

        // 120 minutes of qualifying ticks at a 30-minute minimum spacing.
        assertTrue(estimator.observationCount <= 5)
    }

    @Test
    fun `the ratio survives a process restart`() {
        val sharedStorage = InMemoryAimiStorage()
        val first = newEstimator(sharedStorage)

        val afterRunUp = feed(first, 30, 200.0, 200.0, 3.2, 3.0)
        val lastMs = feed(first, 50, 200.0, 140.0, 3.0, 2.0, startMs = afterRunUp + 300_000L)
        assertTrue(first.observationCount >= 1)
        val saved = first.ratio
        assertTrue(abs(saved - 1.0) > 1e-9)

        // A new instance over the same storage: the phone restarted, the patient did not change.
        val restarted = newEstimator(sharedStorage)
        restarted.observe(
            SensitivityRatioEstimator.Sample(
                timestampMs = lastMs + 300_000L,
                bgMgdl = 140.0, iobU = 2.0,
                profileBasalUph = profileBasal, deliveredBasalUph = profileBasal,
                smbU = 0.0, profileIsfMgdl = profileIsf, cobG = 0.0, lastBolusMs = 0L,
            ),
        )

        assertEquals(saved, restarted.ratio, 1e-9)
        assertEquals(first.observationCount, restarted.observationCount)
    }

    @Test
    fun `state older than the staleness window is discarded`() {
        val staleStorage = InMemoryAimiStorage()
        val nowMs = 40L * 24L * 3_600_000L
        val staleMs = nowMs - (SensitivityRatioEstimator.STALE_AFTER_DAYS + 1L) * 24L * 3_600_000L
        staleStorage.writeText(
            staleStorage.file(SensitivityRatioEstimator.STATE_FILE_NAME),
            """{"ratio":0.62,"observation_count":40,"last_fold_ms":$staleMs,"saved_at_ms":$staleMs}""",
        )

        val estimator = newEstimator(staleStorage)
        estimator.observe(
            SensitivityRatioEstimator.Sample(
                timestampMs = nowMs,
                bgMgdl = 140.0, iobU = 2.0,
                profileBasalUph = profileBasal, deliveredBasalUph = profileBasal,
                smbU = 0.0, profileIsfMgdl = profileIsf, cobG = 0.0, lastBolusMs = 0L,
            ),
        )

        assertEquals(1.0, estimator.ratio, 1e-9)
        assertEquals(0, estimator.observationCount)
    }

    private fun <T : Any> requireNonNull(value: T?): T = requireNotNull(value) { "expected an observation" }
}
