package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiIntelligenceSnapshot
import app.aaps.plugins.aps.openAPSAIMI.orchestration.CausalContextView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.EffectiveKinetics
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinActivityView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.InsulinKineticsView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.IntelligenceSnapshotJson
import app.aaps.plugins.aps.openAPSAIMI.orchestration.IsfAuthorityView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.MlFeatureSliceView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.PredictionAuthorityView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.SmbPolicyContextView
import app.aaps.plugins.aps.openAPSAIMI.orchestration.SnapshotMeta
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.shared.tests.TestBase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

/**
 * Integration tests for `OApsAIMIPkpdCurveLearningGate`: the switch, [PkpdLearningWindowGate], the
 * estimator and the exported trace, all through `PkPdIntegration.computeRuntime`.
 *
 * `PkpdLearningWindowGateTest` tests the gate class alone. That test passed while nothing in
 * production called the gate, so these tests look at what really reaches the learner.
 */
class PkpdCurveLearningGateIntegrationTest : TestBase() {

    @Mock private lateinit var preferences: Preferences

    private val behaviorProfileSource = object : AimiBehaviorProfileSource {
        override fun read(preferences: Preferences) = AimiBehaviorRuntimeProfile(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.AssistedApplication,
        )
    }

    private lateinit var integration: PkPdIntegration

    @BeforeEach
    fun setUp() {
        whenever(preferences.get(BooleanKey.OApsAIMIPkpdEnabled)).thenReturn(true)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        stubPkpdDefaults()
        IsfTddProvider.set(50.0)
        integration = PkPdIntegration(preferences, PkPdLearnedState(), behaviorProfileSource)
    }

    private fun arm(on: Boolean) {
        whenever(preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate)).thenReturn(on)
    }

    @Test
    fun armed_gate_keeps_the_first_ticks_away_from_the_learner() {
        arm(true)

        var last: PkPdRuntime? = null
        for (index in 1L..5L) last = tick(index)

        assertEquals(0L, acceptedUpdates(), "the gate has no history yet, so nothing may reach the learner")
        assertEquals("curve:" + PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, last?.learningTrace?.diaLearnBlockedBy)
    }

    @Test
    fun armed_gate_lets_clean_ticks_through_once_the_window_is_covered() {
        arm(true)

        var last: PkPdRuntime? = null
        for (index in 1L..30L) last = tick(index)

        assertTrue(acceptedUpdates() > 0L, "a quiet hour with no meal must still be learned from")
        assertNull(last?.learningTrace?.diaLearnBlockedBy)
        assertEquals(true, last?.learningTrace?.curveGateArmed)
        assertNull(last?.learningTrace?.curveGateWouldBlock)
    }

    @Test
    fun armed_gate_blocks_learning_after_a_declared_meal() {
        arm(true)

        var last: PkPdRuntime? = null
        for (index in 1L..30L) last = tick(index, declaredMealAgeMin = 20L)

        assertEquals(0L, acceptedUpdates(), "a meal declared 20 min ago must stop learning")
        assertEquals("curve:" + PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, last?.learningTrace?.diaLearnBlockedBy)
        assertEquals(20L, last?.learningTrace?.curveGateDeclaredMealAgeMin)
    }

    @Test
    fun a_high_carb_meal_blocks_longer_than_a_usual_meal() {
        arm(true)
        // 200 min is past the usual 180 min block and inside the 240 min high-carb block.
        for (index in 1L..30L) tick(index, declaredMealAgeMin = 200L, declaredMealHighCarb = true)
        assertEquals(0L, acceptedUpdates(), "high-carb meal 200 min ago is still blocked")

        val usual = PkPdIntegration(preferences, PkPdLearnedState(), behaviorProfileSource)
        for (index in 1L..30L) tick(index, target = usual, declaredMealAgeMin = 200L, declaredMealHighCarb = false)
        assertTrue(acceptedUpdates(usual) > 0L, "a usual meal 200 min ago no longer blocks")
    }

    @Test
    fun switch_off_learning_ignores_the_gate_and_the_declared_meal() {
        arm(false)
        val reference = PkPdIntegration(preferences, PkPdLearnedState(), behaviorProfileSource)

        var last: PkPdRuntime? = null
        for (index in 1L..30L) {
            last = tick(index, declaredMealAgeMin = 20L)
            tick(index, target = reference)
        }

        val learned = acceptedUpdates()
        assertTrue(learned > 0L, "with the switch off the learner runs as before")
        assertEquals(acceptedUpdates(reference), learned, "the declared meal must not change learning when off")
        assertEquals(learnedDia(reference), learnedDia(integration), 1e-12)
        assertNull(last?.learningTrace?.diaLearnBlockedBy)
        assertEquals(false, last?.learningTrace?.curveGateArmed)
    }

    @Test
    fun switch_off_still_exports_what_the_gate_would_do() {
        arm(false)

        var last: PkPdRuntime? = null
        for (index in 1L..30L) last = tick(index, declaredMealAgeMin = 20L)

        assertEquals(PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, last?.learningTrace?.curveGateWouldBlock)
        assertEquals(20L, last?.learningTrace?.curveGateDeclaredMealAgeMin)
    }

    @Test
    fun read_only_call_never_feeds_the_gate() {
        arm(true)
        for (index in 1L..30L) tick(index, allowLearning = false)

        // If the read-only ticks had fed the history, this first learning tick would find a covered window.
        val first = tick(31L)

        assertEquals("curve:" + PkpdLearningWindowGate.BLOCK_HISTORY_SHORT, first?.learningTrace?.diaLearnBlockedBy)
    }

    @Test
    fun snapshot_json_carries_the_dia_curve_gate_keys() {
        arm(true)
        var last: PkPdRuntime? = null
        for (index in 1L..30L) last = tick(index, declaredMealAgeMin = 20L)
        val trace = requireTrace(last?.learningTrace)

        val kinetics = IntelligenceSnapshotJson.toJsonObject(snapshotWith(trace)).getValue("kinetics").jsonObject

        assertTrue(kinetics.getValue("dia_curve_gate_armed").jsonPrimitive.boolean)
        assertEquals(PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, kinetics.getValue("dia_curve_gate_would_block").jsonPrimitive.content)
        assertTrue(kinetics.containsKey("dia_curve_gate_max_recent_delta"))
        assertEquals(20L, kinetics.getValue("dia_curve_gate_declared_meal_age_min").jsonPrimitive.long)
        // No rise was detected on this flat curve, so the reference leaves this key out.
        assertFalse(kinetics.containsKey("dia_curve_gate_detected_rise_age_min"))
    }

    private fun requireTrace(value: PkpdLearningTrace?): PkpdLearningTrace {
        assertNotNull(value, "the runtime has no learning trace")
        return value!!
    }

    private fun snapshotWith(trace: PkpdLearningTrace) = AimiIntelligenceSnapshot(
        meta = SnapshotMeta(timestampMs = 0L),
        causal = CausalContextView(1.0, true, "UNKNOWN", 0.0, "none"),
        kinetics = InsulinKineticsView(
            accountingIobArray = emptyArray(),
            predictionIobArray = emptyArray(),
            structural = PkPdParams(6.0, 55.0),
            effective = EffectiveKinetics(6.0, 55.0, 6.0, 55),
            learning = PkpdLearningDiagnostics(1.0, true, true, "UNKNOWN", null),
            activity = InsulinActivityView(0.5, "PEAK", 0.5),
            peakGovernor = null,
            diaGovernor = null,
            predictionUsesLearnedKinetics = false,
            learningTrace = trace,
        ),
        isf = IsfAuthorityView(50.0, 50.0, 50.0, 1.0, 1.0),
        predictions = PredictionAuthorityView(100.0, 100.0, null, null, null, null),
        smbPolicy = SmbPolicyContextView(0.5, "PEAK", true),
        mlFeatures = MlFeatureSliceView(50.0, 6.0, 55.0, 0.5, 1.0, 1.0),
    )

    private fun tick(
        index: Long,
        target: PkPdIntegration = integration,
        allowLearning: Boolean = true,
        declaredMealAgeMin: Long? = null,
        declaredMealHighCarb: Boolean = false,
    ): PkPdRuntime? = target.computeRuntime(
        epochMillis = index * 5L * 60L * 1000L,
        bg = 120.0,
        deltaMgDlPer5 = -0.5,
        iobU = 2.5,
        carbsActiveG = 0.0,
        windowMin = 60,
        exerciseFlag = false,
        profileIsf = 50.0,
        tdd24h = 40.0,
        allowLearning = allowLearning,
        declaredMealAgeMin = declaredMealAgeMin,
        declaredMealHighCarb = declaredMealHighCarb,
    )

    private fun acceptedUpdates(target: PkPdIntegration = integration): Long =
        target.learningStatusSnapshot()?.acceptedUpdateCount ?: 0L

    private fun learnedDia(target: PkPdIntegration): Double =
        target.learningStatusSnapshot()?.params?.diaHrs ?: Double.NaN

    private fun stubPkpdDefaults() {
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(6.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)).thenReturn(55.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)).thenReturn(5.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)).thenReturn(8.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)).thenReturn(35.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)).thenReturn(95.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)).thenReturn(0.5)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)).thenReturn(5.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdAnchorDiaH)).thenReturn(4.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin)).thenReturn(55.0)
        whenever(preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor)).thenReturn(1.5)
        whenever(preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)).thenReturn(0.2)
        whenever(preferences.get(DoubleKey.OApsAIMISmbTailThreshold)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.OApsAIMISmbTailDamping)).thenReturn(0.85)
        whenever(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping)).thenReturn(0.8)
        whenever(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)).thenReturn(0.8)
    }
}
