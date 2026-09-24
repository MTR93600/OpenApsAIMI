package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.AimiBasalMergeRecord
import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
import app.aaps.plugins.aps.openAPSAIMI.applyBasalShadowRecords
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * The passive basal measurements: where the meal-absorption boost stands against its tier ceiling, and
 * what the engine / rT merge did. None of this changes a dose, so the tests check the reported numbers
 * and the export, not a delivered rate.
 *
 * The tier decisions come from `CorrectionAggressionGate.evaluate` rather than hand-built copies, so the
 * tiers the loop can really produce are the ones under test, including the two that waive the ceiling.
 */
class BasalShadowExportTest {

    /** A tick with no meal evidence at all. The named arguments select the tier. */
    private fun gateInput(
        bg: Double,
        deltaMgdl5m: Double = 0.0,
        minBgLookback75m: Double = 60.0,
        explicitMealMode: Boolean = false,
    ) = CorrectionAggressionGate.Input(
        bg = bg,
        targetBg = 100.0,
        deltaMgdl5m = deltaMgdl5m,
        shortAvgDelta = 0.0,
        combinedDelta = 0.0,
        cob = 0.0,
        minBgLookback75m = minBgLookback75m,
        estimatedCarbs = 0.0,
        estimatedCarbsAgeMin = 999.0,
        uamConfidence = 0.0,
        estimatedRa = 0.0,
        explicitMealMode = explicitMealMode,
        hasRecentMealEstimate = false,
        isConfirmedHighRise = false,
    )

    /** Post-hypo recovery: glucose back near target after a low, no meal evidence. */
    private val reboundGuard = CorrectionAggressionGate.evaluate(gateInput(bg = 110.0))

    /** Clearly high, drifting, no meal evidence and no recent low: a plain rise. */
    private val moderate = CorrectionAggressionGate.evaluate(gateInput(bg = 200.0, deltaMgdl5m = 2.0))

    /** The same rise going up fast, which makes MODERATE waive its own ceiling. */
    private val moderateRocket = CorrectionAggressionGate.evaluate(gateInput(bg = 200.0, deltaMgdl5m = 12.0))

    /** A declared meal, which is full meal evidence. */
    private val full = CorrectionAggressionGate.evaluate(gateInput(bg = 200.0, explicitMealMode = true))

    private val allGates: List<CorrectionAggressionGate.Decision?> =
        listOf(reboundGuard, moderate, moderateRocket, full, null)

    @Test
    fun theFixtureTiersAreTheOnesTheGateReallyProduces() {
        assertThat(reboundGuard.tier).isEqualTo(CorrectionAggressionGate.Tier.REBOUND_GUARD)
        assertThat(moderate.tier).isEqualTo(CorrectionAggressionGate.Tier.MODERATE)
        assertThat(moderateRocket.tier).isEqualTo(CorrectionAggressionGate.Tier.MODERATE)
        assertThat(full.tier).isEqualTo(CorrectionAggressionGate.Tier.FULL)
        // Two of the four waive the ceiling, which is why a bound ceiling is rarer than it looks.
        assertThat(reboundGuard.allowRocketBasalScale).isFalse()
        assertThat(moderate.allowRocketBasalScale).isFalse()
        assertThat(moderateRocket.allowRocketBasalScale).isTrue()
        assertThat(full.allowRocketBasalScale).isTrue()
    }

    // The shadow reports the real ceiling of each tier the gate can produce.
    @Test
    fun shadowReportsTheCeilingOfEveryRealTier() {
        val profileBasal = 0.53
        val requested = 4.7

        val guard = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, reboundGuard)
        assertThat(guard.maxAllowedUph!!).isWithin(1e-9).of(0.795)
        assertThat(guard.cappedUph).isWithin(1e-9).of(0.795)
        assertThat(guard.wouldBind).isTrue()

        val moderateRecord = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, moderate)
        assertThat(moderateRecord.maxAllowedUph!!).isWithin(1e-9).of(1.59)
        assertThat(moderateRecord.cappedUph).isWithin(1e-9).of(1.59)
        assertThat(moderateRecord.wouldBind).isTrue()

        // Both waiving tiers report no ceiling at all, not a ceiling that happens not to bind.
        for (waiving in listOf(moderateRocket, full)) {
            val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, waiving)
            assertThat(record.maxAllowedUph).isNull()
            assertThat(record.cappedUph).isWithin(1e-9).of(requested)
            assertThat(record.wouldBind).isFalse()
        }
    }

    // REBOUND_GUARD, profile basal 0.53, requested 4.7 -> 1.5x = 0.795.
    @Test
    fun reboundGuardCeilingIsOnePointFiveTimesProfile() {
        val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(4.7, 0.53, reboundGuard)
        assertThat(record.tier).isEqualTo(CorrectionAggressionGate.Tier.REBOUND_GUARD)
        assertThat(record.requestedUph).isWithin(1e-9).of(4.7)
        assertThat(record.cappedUph).isWithin(1e-9).of(0.795)
        assertThat(record.wouldBind).isTrue()
    }

    // A rate under the MODERATE ceiling is reported unchanged and not marked as binding.
    @Test
    fun moderateLeavesRateBelowCeilingAlone() {
        val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(1.2, 0.53, moderate)
        assertThat(record.maxAllowedUph!!).isWithin(1e-9).of(1.59)
        assertThat(record.cappedUph).isWithin(1e-9).of(1.2)
        assertThat(record.wouldBind).isFalse()
    }

    // The reported ceiling can only lower a rate, for every tier the gate can produce.
    @Test
    fun theReportedCeilingNeverRaisesARate() {
        val profileBasal = 0.53
        for (gate in allGates) {
            var requested = 0.01
            while (requested <= 20.0) {
                val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, gate)
                assertThat(record.cappedUph).isAtMost(record.requestedUph + 1e-9)
                requested += 0.05
            }
        }
    }

    // A rate that is zero, negative or not finite is returned before any ceiling is worked out.
    @Test
    fun noCeilingIsReportedForARateThatIsNotUsable() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(bad, 0.53, reboundGuard)
            assertThat(record.maxAllowedUph).isNull()
            assertThat(record.wouldBind).isFalse()
        }
    }

    // The merge reporting agrees with the merge, in both modes, and the merge itself is unchanged.
    @Test
    fun mergeReportingNamesTheWinnerInBothModes() {
        // min mode: the engine's safety zero wins over a boost on rT.
        val minMerged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(0.0, 4.7, reboundGuard)
        assertThat(minMerged).isWithin(1e-9).of(0.0)
        assertThat(CorrectionAggressionBasalCap.mergeMode(reboundGuard)).isEqualTo("min")
        assertThat(CorrectionAggressionBasalCap.mergeWinner(0.0, 4.7, minMerged)).isEqualTo("engine")

        // max mode: the same safety zero loses to the same boost. This is the thing being counted.
        val maxMerged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(0.0, 4.7, full)
        assertThat(maxMerged).isWithin(1e-9).of(4.7)
        assertThat(CorrectionAggressionBasalCap.mergeMode(full)).isEqualTo("max")
        assertThat(CorrectionAggressionBasalCap.mergeWinner(0.0, 4.7, maxMerged)).isEqualTo("rt")

        val engineWins = CorrectionAggressionBasalCap.mergeEngineAndRtRates(5.8, 1.11, full)
        assertThat(engineWins).isWithin(1e-9).of(5.8)
        assertThat(CorrectionAggressionBasalCap.mergeWinner(5.8, 1.11, engineWins)).isEqualTo("engine")

        val rtWins = CorrectionAggressionBasalCap.mergeEngineAndRtRates(5.8, 1.11, reboundGuard)
        assertThat(rtWins).isWithin(1e-9).of(1.11)
        assertThat(CorrectionAggressionBasalCap.mergeWinner(5.8, 1.11, rtWins)).isEqualTo("rt")
    }

    @Test
    fun mergeReportingHandlesEqualRatesNoRtRateAndNonFiniteRates() {
        assertThat(CorrectionAggressionBasalCap.mergeWinner(1.2, 1.2, 1.2)).isEqualTo("equal")
        assertThat(CorrectionAggressionBasalCap.mergeWinner(1.2, null, 1.2)).isEqualTo("engine")
        assertThat(CorrectionAggressionBasalCap.mergeMode(null)).isEqualTo("max")
        // A NaN makes every comparison false, so no winner may be claimed.
        assertThat(CorrectionAggressionBasalCap.mergeWinner(Double.NaN, 1.2, Double.NaN)).isEqualTo("unknown")
        assertThat(CorrectionAggressionBasalCap.mergeWinner(1.2, Double.NaN, Double.NaN)).isEqualTo("unknown")
    }

    // ---------------------------------------------------------------- wiring into the export

    private fun baselineState() = AimiDecisionContext.BaselineState(
        profile_isf_mgdl = 50.0,
        profile_basal_uph = 0.53,
        current_bg_mgdl = 200.0,
        cob_g = 0.0,
        iob_u = 1.0,
    )

    @Test
    fun bothRecordsAreCopiedIntoTheExportedBaselineState() {
        val state = baselineState()
        val boost = CorrectionAggressionBasalCap.evaluateMealBoostCap(4.7, 0.53, reboundGuard)
        val merge = AimiBasalMergeRecord(
            engineRateUph = 0.0,
            rtRateUph = 4.7,
            mergeMode = "min",
            mergeWinner = "engine",
        )

        state.applyBasalShadowRecords(boost, merge)

        assertThat(state.meal_boost_requested_uph!!).isWithin(1e-9).of(4.7)
        assertThat(state.meal_boost_cap_tier).isEqualTo("REBOUND_GUARD")
        assertThat(state.meal_boost_cap_max_uph!!).isWithin(1e-9).of(0.795)
        assertThat(state.meal_boost_capped_uph!!).isWithin(1e-9).of(0.795)
        assertThat(state.meal_boost_cap_would_bind).isTrue()
        assertThat(state.engine_rate_uph!!).isWithin(1e-9).of(0.0)
        assertThat(state.rt_rate_uph!!).isWithin(1e-9).of(4.7)
        assertThat(state.merge_mode).isEqualTo("min")
        assertThat(state.merge_winner).isEqualTo("engine")
    }

    // The per-tick reset: the loop clears both records at the start of a tick, so a tick that reaches
    // neither the boost branch nor the merge must export nothing rather than the previous tick's numbers.
    @Test
    fun clearedRecordsLeaveEveryShadowFieldEmpty() {
        val state = baselineState()
        state.applyBasalShadowRecords(null, null)

        assertThat(state.meal_boost_requested_uph).isNull()
        assertThat(state.meal_boost_cap_tier).isNull()
        assertThat(state.meal_boost_cap_max_uph).isNull()
        assertThat(state.meal_boost_capped_uph).isNull()
        assertThat(state.meal_boost_cap_would_bind).isNull()
        assertThat(state.engine_rate_uph).isNull()
        assertThat(state.rt_rate_uph).isNull()
        assertThat(state.merge_mode).isNull()
        assertThat(state.merge_winner).isNull()
    }

    @Test
    fun onlyTheMergeRanSoOnlyTheMergeFieldsAreWritten() {
        val state = baselineState()
        state.applyBasalShadowRecords(
            mealBoost = null,
            merge = AimiBasalMergeRecord(1.2, null, "max", "engine"),
        )

        assertThat(state.meal_boost_requested_uph).isNull()
        assertThat(state.meal_boost_cap_would_bind).isNull()
        assertThat(state.engine_rate_uph!!).isWithin(1e-9).of(1.2)
        assertThat(state.rt_rate_uph).isNull()
        assertThat(state.merge_winner).isEqualTo("engine")
    }

    // ---------------------------------------------------------------- non-finite values

    /**
     * `JSONObject.put(String, double)` throws on NaN and infinity, and `toMedicalJson` catches everything
     * and returns one error string. Without a guard a single non-finite rate would destroy the whole tick
     * record, on the worst ticks first. The engine rate is the exposed one: it is read before the loop
     * hardens non-finite rates.
     */
    @Test
    fun aNonFiniteRateStillProducesACompleteRecord() {
        val state = baselineState()
        state.engine_rate_uph = Double.NaN
        state.rt_rate_uph = Double.POSITIVE_INFINITY
        state.meal_boost_requested_uph = Double.NaN
        state.meal_boost_capped_uph = Double.NEGATIVE_INFINITY
        state.meal_boost_cap_max_uph = Double.NaN
        state.merge_mode = "max"
        state.merge_winner = "unknown"

        val context = AimiDecisionContext(
            event_id = "test-event",
            timestamp = 1_700_000_000_000L,
            trigger = "unit-test",
            baseline_state = state,
        )
        val json = JSONObject(context.toMedicalJson())

        assertThat(json.has("error")).isFalse()
        assertThat(json.getString("event_id")).isEqualTo("test-event")
        val base = json.getJSONObject("baseline_state")
        assertThat(base.getDouble("current_bg_mgdl")).isWithin(1e-9).of(200.0)
        // Every non-finite number became JSON null; the finite neighbours are untouched.
        for (field in listOf(
            "engine_rate_uph",
            "rt_rate_uph",
            "meal_boost_requested_uph",
            "meal_boost_capped_uph",
            "meal_boost_cap_max_uph",
        )) {
            assertThat(base.isNull(field)).isTrue()
        }
        assertThat(base.getString("merge_mode")).isEqualTo("max")
        assertThat(base.getString("merge_winner")).isEqualTo("unknown")
    }

    @Test
    fun finiteRatesAreStillExportedAsNumbers() {
        val state = baselineState()
        state.applyBasalShadowRecords(
            mealBoost = CorrectionAggressionBasalCap.evaluateMealBoostCap(4.7, 0.53, reboundGuard),
            merge = AimiBasalMergeRecord(0.0, 4.7, "min", "engine"),
        )
        val context = AimiDecisionContext(
            event_id = "test-event",
            timestamp = 1_700_000_000_000L,
            trigger = "unit-test",
            baseline_state = state,
        )
        val base = JSONObject(context.toMedicalJson()).getJSONObject("baseline_state")

        assertThat(base.getDouble("meal_boost_requested_uph")).isWithin(1e-9).of(4.7)
        assertThat(base.getDouble("meal_boost_capped_uph")).isWithin(1e-9).of(0.795)
        assertThat(base.getBoolean("meal_boost_cap_would_bind")).isTrue()
        assertThat(base.getDouble("engine_rate_uph")).isWithin(1e-9).of(0.0)
        assertThat(base.getString("merge_winner")).isEqualTo("engine")
        assertThat(base.has("meal_boost_cap_applied")).isFalse()
    }
}
