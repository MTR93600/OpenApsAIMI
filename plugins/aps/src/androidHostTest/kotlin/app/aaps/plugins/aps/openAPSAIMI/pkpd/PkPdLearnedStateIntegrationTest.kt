package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.math.abs

/**
 * Matching tests for [PkPdLearnedState] from `origin/dev_OAPSAIMI` @ `0761e9c00a`
 * (`PkPdIntegrationTest`).
 *
 * The two regressions that fail without the shared holder:
 * - [read_only_consumer_sees_the_state_learned_by_the_other_consumer]
 * - [bounds_change_does_not_persist_a_stale_value_from_the_read_only_consumer]
 *
 * Plus the four 0761e9c guard tests (read-only does not learn; ISF slew per consumer;
 * external reset reaches both; bolus samples stay private).
 *
 * Adapted to study: mockito instead of mockk, and the study constructor also takes
 * [AimiBehaviorProfileSource]. Clinical tick inputs are copied from the reference tests.
 */
class PkPdLearnedStateIntegrationTest : TestBase() {

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

    /** Profile insulin of every tick: bounds DIA 4.5-7.5 h, peak 41.25-74.25 min, anchor 6.0 h / 55 min. */
    private val profileInsulin = ProfileInsulin(diaHours = 6.0, peakMinutes = 55.0)

    @BeforeEach
    fun setUp() {
        integration = engine(preferences)
    }

    @Test
    fun clearLearned_drops_values_but_keeps_generation() {
        val state = PkPdLearnedState()
        state.estimator = AdaptivePkPdEstimator()
        state.lastBounds = PkPdBounds()
        state.lastLearningCfg = PkPdLearningConfig()
        state.lastPersisted = PkPdParams(6.0, 55.0)
        state.seenLearnedStateGeneration = 3L

        state.clearLearned()

        assertEquals(null, state.estimator)
        assertEquals(null, state.lastBounds)
        assertEquals(null, state.lastLearningCfg)
        assertEquals(null, state.lastPersisted)
        assertEquals(3L, state.seenLearnedStateGeneration)
    }

    @Test
    fun reset_written_between_two_ticks_reseeds_the_learner_from_prefs() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)

        tick(1)
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(1L)
        tick(2)

        assertEquals(7.5, learnedDiaHrs(), 0.1)
    }

    @Test
    fun unchanged_generation_keeps_the_in_memory_learner() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)

        repeat(4) { tick(it + 1L) }
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        repeat(4) { tick(it + 5L) }

        assertEquals(6.0, learnedDiaHrs(), 0.1)
    }

    @Test
    fun first_tick_adopts_the_stored_generation_without_resetting() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(42L)
        IsfTddProvider.set(50.0)

        tick(1)
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        tick(2)

        assertEquals(6.0, learnedDiaHrs(), 0.1)
    }

    @Test
    fun reseeded_params_are_clamped_into_current_bounds() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        tick(1)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(99.0)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(1L)
        tick(2)

        assertEquals(7.5, learnedDiaHrs(), 1e-9)
    }

    @Test
    fun two_integrations_sharing_prefs_both_reseed() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val first = engine(preferences)
        val second = engine(preferences)
        tick(1, first)
        tick(1, second)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(1L)
        tick(2, first)
        tick(2, second)

        assertEquals(7.5, learnedDiaHrs(first), 0.1)
        assertEquals(7.5, learnedDiaHrs(second), 0.1)
    }

    @Test
    fun generation_change_also_fires_on_a_decreasing_counter() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(5L)
        IsfTddProvider.set(50.0)
        tick(1)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(2L)
        tick(2)

        assertEquals(7.5, learnedDiaHrs(), 0.1)
    }

    @Test
    fun pace_change_in_the_same_tick_does_not_overwrite_the_external_reset() {
        val stored = mutableMapOf<DoubleKey, Double>()
        val stateful = statefulPreferences(stored)
        whenever(stateful.get(BooleanKey.OApsAIMIPkpdEnabled)).thenReturn(true)
        whenever(stateful.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        storePkpdDefaults(stored)
        IsfTddProvider.set(50.0)
        val engine = engine(stateful)

        tick(1, engine)
        assertEquals(6.0, learnedDiaHrs(engine), 1e-9)

        // A new pace changes the bounds, like the insulin presets used to.
        stored[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 1.0
        stored[DoubleKey.OApsAIMIPkpdStateDiaH] = 4.6
        whenever(stateful.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(1L)
        tick(2, engine)

        assertEquals(4.6, learnedDiaHrs(engine), 1e-9)
        assertEquals(4.6, stored.getValue(DoubleKey.OApsAIMIPkpdStateDiaH), 1e-9)
    }

    @Test
    fun read_only_consumer_sees_the_state_learned_by_the_other_consumer() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = engine(preferences, shared)
        val learner = engine(preferences, shared)

        learningTick(1, reader, allowLearning = false)
        assertEquals(6.0, learnedDiaHrs(reader), 1e-9)

        for (index in 2L..40L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        // The anchor is the profile DIA (6.0 h) now, so the direction depends on the error only.
        assertTrue(abs(learned - 6.0) > 1e-6, "the learner did not move the DIA: $learned")

        val readerRuntime = learningTick(41, reader, allowLearning = false)
        assertNotNull(readerRuntime)
        assertEquals(learned, readerRuntime!!.params.diaHrs, 1e-9)
    }

    @Test
    fun bounds_change_does_not_persist_a_stale_value_from_the_read_only_consumer() {
        val stored = mutableMapOf<DoubleKey, Double>()
        val stateful = statefulPreferences(stored)
        whenever(stateful.get(BooleanKey.OApsAIMIPkpdEnabled)).thenReturn(true)
        whenever(stateful.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        storePkpdDefaults(stored)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = engine(stateful, shared)
        val learner = engine(stateful, shared)

        learningTick(1, reader, allowLearning = false)
        for (index in 2L..201L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        assertTrue(abs(learned - 6.0) > 0.05, "the learner did not move the DIA far enough: $learned")

        // A new pace changes the bounds and makes the read-only consumer persist.
        stored[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 1.0
        learningTick(202, reader, allowLearning = false)

        assertEquals(learned, stored.getValue(DoubleKey.OApsAIMIPkpdStateDiaH), 0.01)
    }

    @Test
    fun read_only_consumer_never_moves_the_shared_learned_state() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = engine(preferences, shared)
        val learner = engine(preferences, shared)

        learningTick(1, reader, allowLearning = false)
        for (index in 2L..40L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        val acceptedUpdates = acceptedUpdateCount(learner)
        assertTrue(acceptedUpdates > 0L, "the learner accepted no update")

        for (index in 41L..60L) learningTick(index, reader, allowLearning = false)

        assertEquals(learned, learnedDiaHrs(learner), 1e-9)
        assertEquals(acceptedUpdates, acceptedUpdateCount(learner))
    }

    @Test
    fun shared_learned_state_keeps_isf_fusion_slew_per_consumer() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = engine(preferences, shared)
        val learner = engine(preferences, shared)

        for (index in 1L..3L) isfTick(index, learner, profileIsf = 50.0)

        val learnerIsf = isfTick(4, learner, profileIsf = 10.0)?.fusedIsf
        val readerIsf = isfTick(4, reader, profileIsf = 10.0, allowLearning = false)?.fusedIsf

        assertNotNull(learnerIsf)
        assertNotNull(readerIsf)
        assertTrue(learnerIsf!! > readerIsf!! + 5.0, "learner=$learnerIsf reader=$readerIsf")
    }

    @Test
    fun external_reset_reaches_both_consumers_through_the_shared_state() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val first = engine(preferences, shared)
        val second = engine(preferences, shared)
        tick(1, first)
        tick(1, second)

        whenever(preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(7.5)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(1L)
        tick(2, first)
        tick(2, second)

        assertEquals(7.5, learnedDiaHrs(first), 0.1)
        assertEquals(7.5, learnedDiaHrs(second), 0.1)
    }

    @Test
    fun recent_bolus_samples_stay_private_to_each_consumer() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = engine(preferences, shared)
        val learner = engine(preferences, shared)
        learner.setRecentBolusSamples(listOf(PkpdBolusSample(ageMin = 20.0, units = 2.0)))

        learningTick(1, reader, allowLearning = false)

        assertTrue(learner.reconstructedIobUnits() > 0.0)
        assertEquals(0.0, reader.reconstructedIobUnits(), 1e-9)
    }

    @Test
    fun a_tick_the_estimator_refuses_counts_as_blocked_with_the_estimator_reason() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        PkpdTickTelemetry.learning.clear()

        // The gates pass, but IOB 0.1 U is below the learning minimum, so the estimator refuses the step.
        tick(1)

        val summary = PkpdTickTelemetry.learning.summary(nowMs = 5L * 60L * 1000L)
        assertEquals(1, summary.learningTicks)
        assertEquals(0, summary.acceptedSteps)
        assertEquals(1, summary.blockedTicks)
        assertEquals(PkPdIntegration.LEARN_BLOCKED_ESTIMATOR_PREFIX + "iob_too_low", summary.topBlockReason)
        PkpdTickTelemetry.learning.clear()
    }

    @Test
    fun an_accepted_learning_step_is_not_counted_as_blocked() {
        stubEnabled()
        stubPkpdDefaults(preferences)
        whenever(preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)).thenReturn(0L)
        IsfTddProvider.set(50.0)
        PkpdTickTelemetry.learning.clear()

        learningTick(1, integration)

        val summary = PkpdTickTelemetry.learning.summary(nowMs = 5L * 60L * 1000L)
        assertEquals(1, summary.learningTicks)
        assertEquals(1, summary.acceptedSteps)
        assertEquals(0, summary.blockedTicks)
        PkpdTickTelemetry.learning.clear()
    }

    private fun engine(
        prefs: Preferences,
        state: PkPdLearnedState = PkPdLearnedState(),
    ) = PkPdIntegration(prefs, state, behaviorProfileSource)

    private fun stubEnabled() {
        whenever(preferences.get(BooleanKey.OApsAIMIPkpdEnabled)).thenReturn(true)
    }

    private fun stubPkpdDefaults(target: Preferences) {
        whenever(target.get(DoubleKey.OApsAIMIPkpdStateDiaH)).thenReturn(6.0)
        whenever(target.get(DoubleKey.OApsAIMIPkpdStatePeakMin)).thenReturn(55.0)
        whenever(target.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)).thenReturn(0.5)
        whenever(target.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)).thenReturn(5.0)
        stubProfileInsulinSeen(target)
        whenever(target.get(DoubleKey.OApsAIMIIsfFusionMinFactor)).thenReturn(0.7)
        whenever(target.get(DoubleKey.OApsAIMIIsfFusionMaxFactor)).thenReturn(1.5)
        whenever(target.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)).thenReturn(0.2)
        whenever(target.get(DoubleKey.OApsAIMISmbTailThreshold)).thenReturn(1.0)
        whenever(target.get(DoubleKey.OApsAIMISmbTailDamping)).thenReturn(0.85)
        whenever(target.get(DoubleKey.OApsAIMISmbExerciseDamping)).thenReturn(0.8)
        whenever(target.get(DoubleKey.OApsAIMISmbLateFatDamping)).thenReturn(0.8)
    }

    private fun storePkpdDefaults(stored: MutableMap<DoubleKey, Double>) {
        stored[DoubleKey.OApsAIMIPkpdStateDiaH] = 6.0
        stored[DoubleKey.OApsAIMIPkpdStatePeakMin] = 55.0
        stored[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        stored[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 5.0
        stored[DoubleKey.OApsAIMIIsfFusionMinFactor] = 0.7
        stored[DoubleKey.OApsAIMIIsfFusionMaxFactor] = 1.5
        stored[DoubleKey.OApsAIMIIsfFusionMaxChangePerTick] = 0.2
        stored[DoubleKey.OApsAIMISmbTailThreshold] = 1.0
        stored[DoubleKey.OApsAIMISmbTailDamping] = 0.85
        stored[DoubleKey.OApsAIMISmbExerciseDamping] = 0.8
        stored[DoubleKey.OApsAIMISmbLateFatDamping] = 0.8
    }

    private fun statefulPreferences(stored: MutableMap<DoubleKey, Double>): Preferences {
        val prefs = org.mockito.kotlin.mock<Preferences>()
        whenever(prefs.put(any<DoubleNonPreferenceKey>(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleNonPreferenceKey>(0)
            if (key is DoubleKey) stored[key] = invocation.getArgument(1)
            null
        }
        whenever(prefs.get(any<DoubleKey>())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleKey>(0)
            stored[key] ?: key.defaultValue
        }
        stubProfileInsulinSeen(prefs)
        return prefs
    }

    /** The loop already started from [profileInsulin], so no tick re-seeds from it. */
    private fun stubProfileInsulinSeen(target: Preferences) {
        whenever(target.get(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin)).thenReturn(360L)
        whenever(target.get(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin)).thenReturn(55L)
    }

    private fun tick(index: Long, target: PkPdIntegration = integration) {
        target.computeRuntime(
            epochMillis = index * 5L * 60L * 1000L,
            bg = 120.0,
            deltaMgDlPer5 = 0.0,
            iobU = 0.1,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            profileInsulin = profileInsulin,
        )
    }

    private fun learningTick(
        index: Long,
        target: PkPdIntegration,
        allowLearning: Boolean = true,
    ) = target.computeRuntime(
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
        profileInsulin = profileInsulin,
    )

    private fun isfTick(
        index: Long,
        target: PkPdIntegration,
        profileIsf: Double,
        allowLearning: Boolean = true,
    ) = target.computeRuntime(
        epochMillis = index * 5L * 60L * 1000L,
        bg = 120.0,
        deltaMgDlPer5 = -0.5,
        iobU = 2.5,
        carbsActiveG = 0.0,
        windowMin = 60,
        exerciseFlag = false,
        profileIsf = profileIsf,
        tdd24h = 40.0,
        allowLearning = allowLearning,
        profileInsulin = profileInsulin,
    )

    private fun acceptedUpdateCount(target: PkPdIntegration): Long {
        val snapshot = target.learningStatusSnapshot()
        assertNotNull(snapshot, "the estimator was not built")
        return snapshot!!.acceptedUpdateCount
    }

    private fun learnedDiaHrs(target: PkPdIntegration = integration): Double {
        val snapshot = target.learningStatusSnapshot()
        assertNotNull(snapshot, "the estimator was not built")
        return snapshot!!.params.diaHrs
    }
}
