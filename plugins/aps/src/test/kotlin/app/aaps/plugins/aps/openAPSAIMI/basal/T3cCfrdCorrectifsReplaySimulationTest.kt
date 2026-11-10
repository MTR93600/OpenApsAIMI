package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Offline replay of the CFRD / T3C support-package failure modes (2026-07-29/30).
 *
 * Validates that the preference defaults + hyper basal floor + CFRD anticipation
 * produce the basal-only behaviour expected after the UI/defaults correctifs —
 * without requiring a device loop.
 *
 * Reference episodes (UTC):
 * - Afternoon under-dose: BG 108→195 with ~0.42 U/h stuck basal
 * - Morning whipsaw: BG 228 @ 1.68 U/h then BG 208 with basal collapsed to 0
 */
class T3cCfrdCorrectifsReplaySimulationTest {

    private data class Tick(
        val label: String,
        val bg: Double,
        val delta: Float,
        val shortAvgDelta: Double,
        val profileBasal: Double,
        val iob: Double,
        val prevRate: Double,
        val hyperDwellOk: Boolean,
    )

    /** Mirrors [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2] hyper-floor apply. */
    private fun applyHyperBasalFloor(
        piRate: Double,
        bg: Double,
        hyperFloorEnabled: Boolean,
        dwellOk: Boolean,
        profileMaxBasal: Double,
        maxBasalCap: Double,
        hyperFloorBgMgdl: Double = 160.0,
    ): Double {
        val applies = hyperFloorEnabled && bg >= hyperFloorBgMgdl && dwellOk
        val floor = if (applies) profileMaxBasal.coerceIn(0.0, maxBasalCap) else 0.0
        return piRate.coerceAtLeast(floor)
    }

    private fun simulateTick(
        tick: Tick,
        aggressiveness: Double,
        anticipationStrength: Double,
        cobDelaySteps: Int,
        lgsFloor: Double,
        activationThreshold: Double,
        unlock: Boolean,
        hyperFloorEnabled: Boolean,
        profileMaxBasal: Double,
        steadyCap: Double,
        riseCap: Double,
        isf: Double = 48.0,
        targetBg: Double = 100.0,
    ): Triple<Double, Double, Boolean> {
        val hints = T3cAnticipation.buildHints(
            predictions = null,
            bgNow = tick.bg,
            lgsThresholdMgdl = lgsFloor,
            activationThreshold = activationThreshold,
            eventualBg = tick.bg + tick.delta * 6,
            strengthRaw = anticipationStrength,
            lgsFloorMgdl = lgsFloor,
            cobDelaySteps = cobDelaySteps,
        )
        val pi = DynamicBasalController.computeT3c(
            bg = tick.bg,
            targetBg = targetBg,
            delta = tick.delta,
            shortAvgDelta = tick.shortAvgDelta,
            longAvgDelta = tick.shortAvgDelta,
            accel = 0.0,
            iob = tick.iob,
            maxIob = 20.0,
            profileBasal = tick.profileBasal,
            isf = isf,
            duraISFminutes = if (tick.bg > 160) 40.0 else 0.0,
            duraISFaverage = if (tick.bg > 160) tick.bg else targetBg,
            eventualBg = tick.bg + tick.delta * 6,
            activationThreshold = activationThreshold,
            aggressiveness = aggressiveness,
            maxBasalCap = if (unlock) riseCap else steadyCap,
            anticipationHints = hints,
        )
        val unlockDec = T3cAutodriveBasalBridge.UnlockDecision(unlock, if (unlock) "glycemic_override" else "no_confirmed_rise")
        val fusion = T3cAutodriveBasalBridge.fuse(
            piUph = pi,
            adTbrUph = null,
            strippedSmbU = 0.0,
            profileBasalUph = tick.profileBasal,
            steadyCapUph = steadyCap,
            riseCapUph = riseCap,
            previousRateUph = tick.prevRate,
            unlock = unlockDec,
        )
        val ramped = T3cAutodriveBasalBridge.applyRamp(tick.prevRate, fusion.fusedTargetUph, fusion.maxStepUpUph)
        val floored = applyHyperBasalFloor(
            piRate = ramped,
            bg = tick.bg,
            hyperFloorEnabled = hyperFloorEnabled,
            dwellOk = tick.hyperDwellOk,
            profileMaxBasal = profileMaxBasal,
            maxBasalCap = fusion.maxBasalCapUph,
        )
        return Triple(pi, floored, fusion.unlock)
    }

    @Test
    fun `defaults enable CFRD and hyper basal floor`() {
        assertThat(BooleanKey.OApsAIMIT3cCfrdMode.defaultValue).isTrue()
        assertThat(BooleanKey.OApsAIMIT3cHyperBasalFloor.defaultValue).isTrue()
        assertThat(BooleanKey.OApsAIMIT3cCfrdExacerbationMode.defaultValue).isFalse()
        assertThat(DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl.defaultValue).isEqualTo(95.0)
        assertThat(DoubleKey.OApsAIMIT3cCfrdCobDelayMin.defaultValue).isEqualTo(30.0)
    }

    @Test
    fun `morning whipsaw - hyper floor prevents basal zero at BG 208 falling`() {
        // Observed: 07:10 BG=208.2 Δ=-5.3 iob=3.76 profile=0.30 → target basal 0
        val before = Tick(
            label = "07:10_legacy",
            bg = 208.2,
            delta = -5.33f,
            shortAvgDelta = -5.0,
            profileBasal = 0.30,
            iob = 3.76,
            prevRate = 1.61,
            hyperDwellOk = true,
        )
        val legacy = simulateTick(
            tick = before,
            aggressiveness = 0.8,
            anticipationStrength = 0.3,
            cobDelaySteps = 0,
            lgsFloor = 70.0,
            activationThreshold = 110.0,
            unlock = false,
            hyperFloorEnabled = false,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        assertThat(legacy.second).isLessThan(0.05)

        val corrected = simulateTick(
            tick = before,
            aggressiveness = 0.8,
            anticipationStrength = 0.6,
            cobDelaySteps = 6, // 30 min / 5
            lgsFloor = 95.0,
            activationThreshold = 110.0,
            unlock = false,
            hyperFloorEnabled = true,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        // Floor holds at max basal (capped) — no collapse to zero while hyper dwell is satisfied
        assertThat(corrected.second).isAtLeast(3.0)
        assertThat(corrected.second).isGreaterThan(legacy.second + 1.0)
    }

    @Test
    fun `afternoon rise - unlock plus higher agg lifts basal vs stuck 0_42 path`() {
        // Observed mid-rise: 16:50 BG=186.8 Δ=+11.3 profile≈0.8–1.0 but delivered ~0.49, unlock=false
        val tick = Tick(
            label = "16:50_rise",
            bg = 186.8,
            delta = 11.26f,
            shortAvgDelta = 10.0,
            profileBasal = 0.30,
            iob = -0.79,
            prevRate = 0.42,
            hyperDwellOk = true,
        )
        val stuck = simulateTick(
            tick = tick,
            aggressiveness = 0.6,
            anticipationStrength = 0.3,
            cobDelaySteps = 0,
            lgsFloor = 70.0,
            activationThreshold = 110.0,
            unlock = false,
            hyperFloorEnabled = false,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        val fixed = simulateTick(
            tick = tick,
            aggressiveness = 1.2, // CFRD / less effort crush + resistance path
            anticipationStrength = 0.6,
            cobDelaySteps = 6,
            lgsFloor = 95.0,
            activationThreshold = 110.0,
            unlock = true,
            hyperFloorEnabled = true,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        assertThat(fixed.third).isTrue()
        assertThat(fixed.second).isGreaterThan(stuck.second)
        // Must clear the historical ~0.49 U/h plateau by a meaningful margin
        assertThat(fixed.second).isAtLeast(0.9)
    }

    @Test
    fun `morning climb still ramps under unlock with CFRD defaults`() {
        val climbBgs = listOf(
            Triple(168.8, 21.87f, false),
            Triple(194.7, 23.56f, true),
            Triple(217.2, 22.78f, true),
            Triple(228.4, 16.68f, true),
        )
        var prev = 0.01
        var last = 0.0
        for ((bg, delta, dwell) in climbBgs) {
            val tick = Tick(
                label = "climb_$bg",
                bg = bg,
                delta = delta,
                shortAvgDelta = delta.toDouble(),
                profileBasal = 0.30,
                iob = 3.1,
                prevRate = prev,
                hyperDwellOk = dwell,
            )
            val (_, rate, unlocked) = simulateTick(
                tick = tick,
                aggressiveness = 0.7,
                anticipationStrength = 0.6,
                cobDelaySteps = 6,
                lgsFloor = 95.0,
                activationThreshold = 110.0,
                unlock = true,
                hyperFloorEnabled = true,
                profileMaxBasal = 5.0,
                steadyCap = 5.0,
                riseCap = 3.0,
            )
            assertThat(unlocked).isTrue()
            assertThat(rate).isAtLeast(last * 0.85) // non-collapsing climb
            prev = rate
            last = rate
        }
        assertThat(last).isAtLeast(1.0)
    }

    @Test
    fun `CFRD cob delay shifts aggressive envelope later`() {
        // Isolate COB: aggressiveEnvelope is a stepwise max across series; UAM/IOB would mask the delay.
        // Use delay=2 (not 6) so the peak is still present after the shift.
        val cob = listOf(120, 140, 180, 200, 190, 170, 150)
        val predictions = Predictions(COB = cob)
        val delayed = T3cAnticipation.aggressiveEnvelope(predictions, 120.0, cobDelaySteps = 2)
        val immediate = T3cAnticipation.aggressiveEnvelope(predictions, 120.0, cobDelaySteps = 0)
        assertThat(delayed).hasSize(cob.size)
        assertThat(immediate).hasSize(cob.size)
        // Leading samples remain at the pre-absorption COB level
        assertThat(delayed[0]).isEqualTo(120.0)
        assertThat(delayed[1]).isEqualTo(120.0)
        assertThat(immediate[2]).isEqualTo(180.0)
        assertThat(delayed[2]).isEqualTo(120.0)
        assertThat(immediate[2]).isGreaterThan(delayed[2])
        // Peak index moves later after the delay shift
        assertThat(delayed.indexOf(200.0)).isEqualTo(immediate.indexOf(200.0) + 2)
    }

    @Test
    fun `hyper floor releases below 160 even if previously held`() {
        val tick = Tick(
            label = "release",
            bg = 150.0,
            delta = -8.0f,
            shortAvgDelta = -8.0,
            profileBasal = 0.30,
            iob = 2.5,
            prevRate = 5.0,
            hyperDwellOk = true,
        )
        val (_, rate, _) = simulateTick(
            tick = tick,
            aggressiveness = 0.8,
            anticipationStrength = 0.6,
            cobDelaySteps = 6,
            lgsFloor = 95.0,
            activationThreshold = 110.0,
            unlock = false,
            hyperFloorEnabled = true,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        // Below 160 → floor does not force max basal
        assertThat(rate).isLessThan(5.0)
    }

    @Test
    fun `safety - near target falling still allows zero without floor`() {
        val tick = Tick(
            label = "near_target",
            bg = 95.0,
            delta = -2.0f,
            shortAvgDelta = -2.0,
            profileBasal = 0.30,
            iob = 1.0,
            prevRate = 0.30,
            hyperDwellOk = false,
        )
        val (_, rate, _) = simulateTick(
            tick = tick,
            aggressiveness = 1.0,
            anticipationStrength = 0.6,
            cobDelaySteps = 6,
            lgsFloor = 95.0,
            activationThreshold = 110.0,
            unlock = false,
            hyperFloorEnabled = true,
            profileMaxBasal = 5.0,
            steadyCap = 5.0,
            riseCap = 3.0,
        )
        assertThat(rate).isEqualTo(0.0)
    }
}
