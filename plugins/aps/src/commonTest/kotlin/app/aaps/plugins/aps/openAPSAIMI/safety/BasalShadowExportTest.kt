package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.AimiBasalMergeRecord
import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
import app.aaps.plugins.aps.openAPSAIMI.applyBasalShadowRecords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The passive basal measurements: where the meal-absorption boost stands against its tier ceiling, and
 * what the engine / rT merge did. None of this changes a dose, so the tests check the reported numbers
 * and the export, not a delivered rate.
 *
 * The tier decisions come from `CorrectionAggressionGate.evaluate` rather than hand-built copies, so the
 * tiers the loop can really produce are the ones under test, including the two that waive the ceiling.
 *
 * Port of `BasalShadowExportTest` from `dev_OAPSAIMI` @ `4b0675549d`. Truth and org.json are replaced
 * by `kotlin.test` and kotlinx serialization, so the test runs on every target. Same cases, same numbers.
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

    private fun assertNear(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) <= 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun theFixtureTiersAreTheOnesTheGateReallyProduces() {
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, reboundGuard.tier)
        assertEquals(CorrectionAggressionGate.Tier.MODERATE, moderate.tier)
        assertEquals(CorrectionAggressionGate.Tier.MODERATE, moderateRocket.tier)
        assertEquals(CorrectionAggressionGate.Tier.FULL, full.tier)
        // Two of the four waive the ceiling, which is why a bound ceiling is rarer than it looks.
        assertFalse(reboundGuard.allowRocketBasalScale)
        assertFalse(moderate.allowRocketBasalScale)
        assertTrue(moderateRocket.allowRocketBasalScale)
        assertTrue(full.allowRocketBasalScale)
    }

    // The shadow reports the real ceiling of each tier the gate can produce.
    @Test
    fun shadowReportsTheCeilingOfEveryRealTier() {
        val profileBasal = 0.53
        val requested = 4.7

        val guard = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, reboundGuard)
        assertNear(0.795, guard.maxAllowedUph)
        assertNear(0.795, guard.cappedUph)
        assertTrue(guard.wouldBind)

        val moderateRecord = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, moderate)
        assertNear(1.59, moderateRecord.maxAllowedUph)
        assertNear(1.59, moderateRecord.cappedUph)
        assertTrue(moderateRecord.wouldBind)

        // Both waiving tiers report no ceiling at all, not a ceiling that happens not to bind.
        for (waiving in listOf(moderateRocket, full)) {
            val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, waiving)
            assertNull(record.maxAllowedUph)
            assertNear(requested, record.cappedUph)
            assertFalse(record.wouldBind)
        }
    }

    // REBOUND_GUARD, profile basal 0.53, requested 4.7 -> 1.5x = 0.795.
    @Test
    fun reboundGuardCeilingIsOnePointFiveTimesProfile() {
        val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(4.7, 0.53, reboundGuard)
        assertEquals(CorrectionAggressionGate.Tier.REBOUND_GUARD, record.tier)
        assertNear(4.7, record.requestedUph)
        assertNear(0.795, record.cappedUph)
        assertTrue(record.wouldBind)
    }

    // A rate under the MODERATE ceiling is reported unchanged and not marked as binding.
    @Test
    fun moderateLeavesRateBelowCeilingAlone() {
        val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(1.2, 0.53, moderate)
        assertNear(1.59, record.maxAllowedUph)
        assertNear(1.2, record.cappedUph)
        assertFalse(record.wouldBind)
    }

    // The reported ceiling can only lower a rate, for every tier the gate can produce.
    @Test
    fun theReportedCeilingNeverRaisesARate() {
        val profileBasal = 0.53
        for (gate in allGates) {
            var requested = 0.01
            while (requested <= 20.0) {
                val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(requested, profileBasal, gate)
                assertTrue(record.cappedUph <= record.requestedUph + 1e-9, "raised ${record.requestedUph} to ${record.cappedUph}")
                requested += 0.05
            }
        }
    }

    // A rate that is zero, negative or not finite is returned before any ceiling is worked out.
    @Test
    fun noCeilingIsReportedForARateThatIsNotUsable() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val record = CorrectionAggressionBasalCap.evaluateMealBoostCap(bad, 0.53, reboundGuard)
            assertNull(record.maxAllowedUph)
            assertFalse(record.wouldBind)
        }
    }

    // The merge reporting agrees with the merge, in both modes, and the merge itself is unchanged.
    @Test
    fun mergeReportingNamesTheWinnerInBothModes() {
        // min mode: the engine's safety zero wins over a boost on rT.
        val minMerged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(0.0, 4.7, reboundGuard)
        assertNear(0.0, minMerged)
        assertEquals("min", CorrectionAggressionBasalCap.mergeMode(reboundGuard))
        assertEquals("engine", CorrectionAggressionBasalCap.mergeWinner(0.0, 4.7, minMerged))

        // max mode: the same safety zero loses to the same boost. This is the thing being counted.
        val maxMerged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(0.0, 4.7, full)
        assertNear(4.7, maxMerged)
        assertEquals("max", CorrectionAggressionBasalCap.mergeMode(full))
        assertEquals("rt", CorrectionAggressionBasalCap.mergeWinner(0.0, 4.7, maxMerged))

        val engineWins = CorrectionAggressionBasalCap.mergeEngineAndRtRates(5.8, 1.11, full)
        assertNear(5.8, engineWins)
        assertEquals("engine", CorrectionAggressionBasalCap.mergeWinner(5.8, 1.11, engineWins))

        val rtWins = CorrectionAggressionBasalCap.mergeEngineAndRtRates(5.8, 1.11, reboundGuard)
        assertNear(1.11, rtWins)
        assertEquals("rt", CorrectionAggressionBasalCap.mergeWinner(5.8, 1.11, rtWins))
    }

    @Test
    fun mergeReportingHandlesEqualRatesNoRtRateAndNonFiniteRates() {
        assertEquals("equal", CorrectionAggressionBasalCap.mergeWinner(1.2, 1.2, 1.2))
        assertEquals("engine", CorrectionAggressionBasalCap.mergeWinner(1.2, null, 1.2))
        assertEquals("max", CorrectionAggressionBasalCap.mergeMode(null))
        // A NaN makes every comparison false, so no winner may be claimed.
        assertEquals("unknown", CorrectionAggressionBasalCap.mergeWinner(Double.NaN, 1.2, Double.NaN))
        assertEquals("unknown", CorrectionAggressionBasalCap.mergeWinner(1.2, Double.NaN, Double.NaN))
    }

    // ---------------------------------------------------------------- wiring into the export

    private fun baselineState() = AimiDecisionContext.BaselineState(
        profile_isf_mgdl = 50.0,
        profile_basal_uph = 0.53,
        current_bg_mgdl = 200.0,
        cob_g = 0.0,
        iob_u = 1.0,
    )

    private fun contextOf(state: AimiDecisionContext.BaselineState) = AimiDecisionContext(
        event_id = "test-event",
        timestamp = 1_700_000_000_000L,
        trigger = "unit-test",
        baseline_state = state,
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

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

        assertNear(4.7, state.meal_boost_requested_uph)
        assertEquals("REBOUND_GUARD", state.meal_boost_cap_tier)
        assertNear(0.795, state.meal_boost_cap_max_uph)
        assertNear(0.795, state.meal_boost_capped_uph)
        assertEquals(true, state.meal_boost_cap_would_bind)
        assertNear(0.0, state.engine_rate_uph)
        assertNear(4.7, state.rt_rate_uph)
        assertEquals("min", state.merge_mode)
        assertEquals("engine", state.merge_winner)
    }

    // The per-tick reset: the records live on the tick scratch, which is new every tick, so a tick that
    // reaches neither the boost branch nor the merge must export nothing rather than the previous numbers.
    @Test
    fun clearedRecordsLeaveEveryShadowFieldEmpty() {
        val state = baselineState()
        state.applyBasalShadowRecords(null, null)

        assertNull(state.meal_boost_requested_uph)
        assertNull(state.meal_boost_cap_tier)
        assertNull(state.meal_boost_cap_max_uph)
        assertNull(state.meal_boost_capped_uph)
        assertNull(state.meal_boost_cap_would_bind)
        assertNull(state.engine_rate_uph)
        assertNull(state.rt_rate_uph)
        assertNull(state.merge_mode)
        assertNull(state.merge_winner)
    }

    @Test
    fun onlyTheMergeRanSoOnlyTheMergeFieldsAreWritten() {
        val state = baselineState()
        state.applyBasalShadowRecords(
            mealBoost = null,
            merge = AimiBasalMergeRecord(1.2, null, "max", "engine"),
        )

        assertNull(state.meal_boost_requested_uph)
        assertNull(state.meal_boost_cap_would_bind)
        assertNear(1.2, state.engine_rate_uph)
        assertNull(state.rt_rate_uph)
        assertEquals("engine", state.merge_winner)
    }

    // ---------------------------------------------------------------- non-finite values

    /**
     * A non-finite Double makes the JSON builder throw, and `toMedicalJson` catches everything and
     * returns one error string. Without a guard a single non-finite rate would destroy the whole tick
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

        val json = parse(contextOf(state).toMedicalJson())

        assertFalse(json.containsKey("error"), "export failed: $json")
        assertEquals("test-event", json.getValue("event_id").jsonPrimitive.content)
        val base = json.getValue("baseline_state").jsonObject
        assertNear(200.0, base.getValue("current_bg_mgdl").jsonPrimitive.double)
        // Every non-finite number became JSON null; the finite neighbours are untouched.
        for (field in listOf(
            "engine_rate_uph",
            "rt_rate_uph",
            "meal_boost_requested_uph",
            "meal_boost_capped_uph",
            "meal_boost_cap_max_uph",
        )) {
            assertTrue(base.containsKey(field), "$field is missing")
            assertEquals(JsonNull, base[field], "$field should be null")
        }
        assertEquals("max", base.getValue("merge_mode").jsonPrimitive.content)
        assertEquals("unknown", base.getValue("merge_winner").jsonPrimitive.content)
    }

    @Test
    fun finiteRatesAreStillExportedAsNumbers() {
        val state = baselineState()
        state.applyBasalShadowRecords(
            mealBoost = CorrectionAggressionBasalCap.evaluateMealBoostCap(4.7, 0.53, reboundGuard),
            merge = AimiBasalMergeRecord(0.0, 4.7, "min", "engine"),
        )
        val base = parse(contextOf(state).toMedicalJson()).getValue("baseline_state").jsonObject

        assertNear(4.7, base.getValue("meal_boost_requested_uph").jsonPrimitive.double)
        assertNear(0.795, base.getValue("meal_boost_capped_uph").jsonPrimitive.double)
        assertTrue(base.getValue("meal_boost_cap_would_bind").jsonPrimitive.boolean)
        assertNear(0.0, base.getValue("engine_rate_uph").jsonPrimitive.double)
        assertEquals("engine", base.getValue("merge_winner").jsonPrimitive.content)
        assertFalse(base.containsKey("meal_boost_cap_applied"))
    }
}
