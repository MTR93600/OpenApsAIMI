package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The ratio must move only on evidence, move slowly, and stay inside the domain where the profile
 * still means something. See `docs/adr/0008-isf-decision-architecture.md`.
 */
class SensitivityRatioEstimatorTest {

    private val profileIsf = 30.0
    private val profileBasal = 0.6

    @TempDir
    lateinit var stateDir: File

    /** A fresh estimator with its own state file, so persistence never leaks between tests. */
    private fun newEstimator(directory: File = File(stateDir, "s${counter++}").apply { mkdirs() }): SensitivityRatioEstimator {
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any<String>()) } answers { File(directory, firstArg<String>()) }
        every { storage.saveFileSafe(any(), any()) } answers {
            firstArg<File>().writeText(secondArg())
            true
        }
        return SensitivityRatioEstimator(storage)
    }

    private var counter = 0

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

        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
        assertThat(estimator.observationCount).isEqualTo(0)
        assertThat(estimator.sensitivityMgdl(profileIsf)).isWithin(1e-9).of(profileIsf)
    }

    @Test
    fun `a clean falling window with no bolus produces an observation`() {
        val estimator = newEstimator()

        // 30 min of quiet run-up, then a 50 min window: BG 140 -> 110 while IOB drains 2.0 -> 1.0.
        val afterRunUp = feed(estimator, minutes = 30, bgStart = 140.0, bgEnd = 140.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 140.0, bgEnd = 110.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertThat(estimator.observationCount).isAtLeast(1)
        val obs = requireNonNull(estimator.lastObservation)
        assertThat(obs.bgDropMgdl).isGreaterThan(0.0)
        assertThat(obs.insulinActedU).isGreaterThan(SensitivityRatioEstimator.MIN_ACTED_U)
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

        assertThat(estimator.observationCount).isEqualTo(0)
        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `a rising window is rejected because carbs may be arriving`() {
        val estimator = newEstimator()

        val afterRunUp = feed(estimator, minutes = 30, bgStart = 110.0, bgEnd = 110.0, iobStart = 2.2, iobEnd = 2.0)
        feed(estimator, minutes = 50, bgStart = 110.0, bgEnd = 150.0, iobStart = 2.0, iobEnd = 1.0, startMs = afterRunUp + 300_000L)

        assertThat(estimator.observationCount).isEqualTo(0)
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
        assertThat(withDeficit).isGreaterThan(withoutDeficit)
    }

    @Test
    fun `one observation moves the state only slightly`() {
        val estimator = newEstimator()

        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0)
        feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, startMs = afterRunUp + 300_000L)

        // Sensitivity is a slow state: a single window must not swing it.
        assertThat(estimator.ratio).isAtLeast(0.9)
        assertThat(estimator.ratio).isAtMost(1.1)
    }

    @Test
    fun `the commanded sensitivity stays inside the band around the profile`() {
        val estimator = newEstimator()

        assertThat(estimator.sensitivityMgdl(profileIsf))
            .isAtLeast(profileIsf * SensitivityRatioEstimator.MIN_RATIO)
        assertThat(estimator.sensitivityMgdl(profileIsf))
            .isAtMost(profileIsf * SensitivityRatioEstimator.MAX_RATIO)
    }

    @Test
    fun `the BG modulation is neutral at the reference and lowers sensitivity when high`() {
        val atReference = SensitivityRatioEstimator.bgModulation(
            SensitivityRatioEstimator.MODULATION_REFERENCE_MGDL,
        )
        assertThat(atReference).isWithin(1e-9).of(1.0)
        assertThat(SensitivityRatioEstimator.bgModulation(200.0)).isLessThan(1.0)
        assertThat(SensitivityRatioEstimator.bgModulation(70.0)).isGreaterThan(1.0)
    }

    @Test
    fun `carbs on board disqualify the window even when nothing was bolused by the loop`() {
        val estimator = newEstimator()

        // Exactly the window that qualifies above, with digestion running. The absorption offsets
        // part of the fall, so the ratio would read low, so the sensitivity commanded from it would
        // read low, so the loop would give more insulin. That is the direction that hurts.
        val afterRunUp = feed(estimator, 30, 140.0, 140.0, 2.2, 2.0, cobG = 20.0)
        feed(estimator, 50, 140.0, 110.0, 2.0, 1.0, startMs = afterRunUp + 300_000L, cobG = 20.0)

        assertThat(estimator.observationCount).isEqualTo(0)
        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
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

        assertThat(estimator.observationCount).isEqualTo(0)
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

        assertThat(estimator.observationCount).isAtLeast(1)
    }

    @Test
    fun `overlapping windows from one episode cannot be folded repeatedly`() {
        val estimator = newEstimator()

        // A long steady fall: many consecutive ticks each close a qualifying window over the same
        // episode. Without a minimum spacing the state would take one step per tick.
        val afterRunUp = feed(estimator, 30, 200.0, 200.0, 3.2, 3.0)
        feed(estimator, 120, 200.0, 120.0, 3.0, 1.0, startMs = afterRunUp + 300_000L)

        // 120 minutes of qualifying ticks at a 30-minute minimum spacing.
        assertThat(estimator.observationCount).isAtMost(5)
    }

    @Test
    fun `the ratio survives a process restart`() {
        val directory = File(stateDir, "shared").apply { mkdirs() }
        val first = newEstimator(directory)

        val afterRunUp = feed(first, 30, 200.0, 200.0, 3.2, 3.0)
        val lastMs = feed(first, 50, 200.0, 140.0, 3.0, 2.0, startMs = afterRunUp + 300_000L)
        assertThat(first.observationCount).isAtLeast(1)
        val saved = first.ratio
        assertThat(saved).isNotWithin(1e-9).of(1.0)

        // A new instance over the same storage: the phone restarted, the patient did not change.
        val restarted = newEstimator(directory)
        restarted.observe(
            SensitivityRatioEstimator.Sample(
                timestampMs = lastMs + 300_000L,
                bgMgdl = 140.0, iobU = 2.0,
                profileBasalUph = profileBasal, deliveredBasalUph = profileBasal,
                smbU = 0.0, profileIsfMgdl = profileIsf, cobG = 0.0, lastBolusMs = 0L,
            ),
        )

        assertThat(restarted.ratio).isWithin(1e-9).of(saved)
        assertThat(restarted.observationCount).isEqualTo(first.observationCount)
    }

    @Test
    fun `state older than the staleness window is discarded`() {
        val directory = File(stateDir, "stale").apply { mkdirs() }
        val nowMs = 40L * 24L * 3_600_000L
        val staleMs = nowMs - (SensitivityRatioEstimator.STALE_AFTER_DAYS + 1L) * 24L * 3_600_000L
        File(directory, SensitivityRatioEstimator.STATE_FILE_NAME).writeText(
            """{"ratio":0.62,"observation_count":40,"last_fold_ms":$staleMs,"saved_at_ms":$staleMs}""",
        )

        val estimator = newEstimator(directory)
        estimator.observe(
            SensitivityRatioEstimator.Sample(
                timestampMs = nowMs,
                bgMgdl = 140.0, iobU = 2.0,
                profileBasalUph = profileBasal, deliveredBasalUph = profileBasal,
                smbU = 0.0, profileIsfMgdl = profileIsf, cobG = 0.0, lastBolusMs = 0L,
            ),
        )

        assertThat(estimator.ratio).isWithin(1e-9).of(1.0)
        assertThat(estimator.observationCount).isEqualTo(0)
    }

    private fun <T : Any> requireNonNull(value: T?): T = requireNotNull(value) { "expected an observation" }
}
