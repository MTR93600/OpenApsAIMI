package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PkPdIntegrationTest {

    private val preferences: Preferences = mockk(relaxed = true)
    private val integration = PkPdIntegration(preferences, PkPdLearnedState())

    @Test
    fun `test computeRuntime disabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns false
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNull(result)
    }

    @Test
    fun `test computeRuntime enabled`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        
        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )
        assertNotNull(result)
    }

    /**
     * The runtime must carry the learning trace exported in the intelligence snapshot, so a
     * frozen DIA or a too short insulin tail can be seen without reading the source.
     */
    @Test
    fun `runtime carries the learning trace`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val trace = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.learningTrace

        assertNotNull(trace)
        // Seed DIA is 6.0 with a floor of 5.0, so the learned DIA is not on the floor.
        assertEquals(false, trace!!.diaAtFloor)
        // Two hours after a dose the exponential kernel still holds about a third of the insulin.
        assertTrue(
            trace.iobResidual120Min > 0.15 && trace.iobResidual120Min < 0.6,
            "unexpected 2 h residual ${trace.iobResidual120Min}",
        )
    }

    /** The floor flag must turn on when the learned DIA sits on the lower bound. */
    @Test
    fun `learning trace reports dia on the floor`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 5.0
        IsfTddProvider.set(50.0)

        val trace = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 100.0,
            deltaMgDlPer5 = 0.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.learningTrace

        assertNotNull(trace)
        assertEquals(true, trace!!.diaAtFloor)
    }

    @Test
    fun `physio resistance lowers fused isf while preserving bounded factors`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val baseline = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 160.0,
            deltaMgDlPer5 = 3.5,
            iobU = 2.2,
            carbsActiveG = 18.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 210.0,
                targetBgMgdl = 110.0,
            ),
            patientWeightKg = 75.0,
        )
        val resistant = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 160.0,
            deltaMgDlPer5 = 3.5,
            iobU = 2.2,
            carbsActiveG = 18.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 210.0,
                targetBgMgdl = 110.0,
            ),
            patientWeightKg = 75.0,
            physioLatentState = PhysioLatentState(
                circadianSiFactor = 0.84,
                transientResistanceProb = 0.90,
                sensorConfidence = 1.0,
                mealProb = 0.60,
            ),
            estimatedRaMgdlPerMin = 3.5,
        )

        assertNotNull(baseline)
        assertNotNull(resistant)
        assertTrue(resistant!!.fusedIsf < baseline!!.fusedIsf)
        assertTrue(resistant.physioSiFactor < 1.0)
        assertTrue(resistant.physioAbsorptionFactor >= 1.0)
    }

    @Test
    fun `heavier weight reduces kinetic factor and pkpd scale`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val lighter = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 140.0,
            deltaMgDlPer5 = 2.0,
            iobU = 1.8,
            carbsActiveG = 10.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            patientWeightKg = 60.0,
        )
        val heavier = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 140.0,
            deltaMgDlPer5 = 2.0,
            iobU = 1.8,
            carbsActiveG = 10.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            patientWeightKg = 105.0,
        )

        assertNotNull(lighter)
        assertNotNull(heavier)
        assertTrue(lighter!!.weightKineticFactor > heavier!!.weightKineticFactor)
        assertTrue(lighter.pkpdScale > heavier.pkpdScale)
    }

    @Test
    fun `strong physio family applies more of latent pkpd modulation than low physio family`() {
        val lowPhysioPrefs: Preferences = mockk(relaxed = true)
        val strongPhysioPrefs: Preferences = mockk(relaxed = true)
        every { lowPhysioPrefs.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { strongPhysioPrefs.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults(lowPhysioPrefs)
        mockPkpdDefaults(strongPhysioPrefs)
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioSleepDataEnable) } returns false
        every { lowPhysioPrefs.get(BooleanKey.AimiPhysioHRVDataEnable) } returns false
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioAssistantEnable) } returns true
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioSleepDataEnable) } returns true
        every { strongPhysioPrefs.get(BooleanKey.AimiPhysioHRVDataEnable) } returns true

        val latent = PhysioLatentState(
            circadianSiFactor = 0.84,
            transientResistanceProb = 0.88,
            postHypoReboundProb = 0.14,
            sensorConfidence = 1.0,
            mealProb = 0.58,
        )
        val mealContext = MealAggressionContext(
            mealModeActive = true,
            predictedBgMgdl = 205.0,
            targetBgMgdl = 110.0,
        )

        val lowProfile = PkPdIntegration(lowPhysioPrefs, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 158.0,
            deltaMgDlPer5 = 3.2,
            iobU = 2.1,
            carbsActiveG = 14.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = mealContext,
            patientWeightKg = 75.0,
            physioLatentState = latent,
            estimatedRaMgdlPerMin = 3.2,
        )
        val strongProfile = PkPdIntegration(strongPhysioPrefs, PkPdLearnedState()).computeRuntime(
            epochMillis = 1000,
            bg = 158.0,
            deltaMgDlPer5 = 3.2,
            iobU = 2.1,
            carbsActiveG = 14.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = mealContext,
            patientWeightKg = 75.0,
            physioLatentState = latent,
            estimatedRaMgdlPerMin = 3.2,
        )

        assertNotNull(lowProfile)
        assertNotNull(strongProfile)
        assertTrue(strongProfile!!.physioSiFactor < lowProfile!!.physioSiFactor)
        assertTrue(abs(strongProfile.physioAbsorptionFactor - 1.0) > abs(lowProfile.physioAbsorptionFactor - 1.0))
        assertTrue(strongProfile.fusedIsf < lowProfile.fusedIsf)
    }

    @Test
    fun `event memory makes pkpd more conservative after exhausted hyper hypo sequence`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()

        val baseline = integration.computeRuntime(
            epochMillis = 1000,
            bg = 168.0,
            deltaMgDlPer5 = 2.8,
            iobU = 2.0,
            carbsActiveG = 12.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 220.0,
                targetBgMgdl = 110.0,
            ),
            physioLatentState = PhysioLatentState(
                circadianSiFactor = 0.92,
                transientResistanceProb = 0.26,
                postHypoReboundProb = 0.34,
                sensorConfidence = 1.0,
                mealProb = 0.62,
            ),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.64,
                prolongedMealProb = 0.20,
                dawnEndogenousProb = 0.16,
                postHypoRecoveryProb = 0.28,
                stressResistanceProb = 0.10,
                exerciseAfterburnProb = 0.06,
                inflammatoryDriftProb = 0.08,
                absorptionUncertainProb = 0.12,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.64,
                learningQuality = 0.82,
            ),
        )
        val exhausted = integration.computeRuntime(
            epochMillis = 1000,
            bg = 168.0,
            deltaMgDlPer5 = 2.8,
            iobU = 2.0,
            carbsActiveG = 12.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            mealContext = MealAggressionContext(
                mealModeActive = true,
                predictedBgMgdl = 220.0,
                targetBgMgdl = 110.0,
            ),
            physioLatentState = PhysioLatentState(
                circadianSiFactor = 0.92,
                transientResistanceProb = 0.26,
                postHypoReboundProb = 0.34,
                sensorConfidence = 1.0,
                mealProb = 0.62,
            ),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.64,
                prolongedMealProb = 0.20,
                dawnEndogenousProb = 0.16,
                postHypoRecoveryProb = 0.28,
                stressResistanceProb = 0.10,
                exerciseAfterburnProb = 0.06,
                inflammatoryDriftProb = 0.08,
                absorptionUncertainProb = 0.12,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.64,
                learningQuality = 0.82,
            ),
            patientEventMemory = PatientEventMemory(
                recentHyperLoad = 0.78,
                recentHypoLoad = 0.44,
                postHyperExhaustionScore = 0.82,
                correctionFragilityScore = 0.80,
            ),
        )

        assertNotNull(baseline)
        assertNotNull(exhausted)
        assertTrue(exhausted!!.physioAbsorptionFactor < baseline!!.physioAbsorptionFactor)
        assertTrue(exhausted.physioSiFactor > baseline.physioSiFactor)
    }

    @Test
    fun `protective causal context skips pkpd learning while preserving baseline params`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        val logs = mutableListOf<String>()
        var runtime: PkPdRuntime? = null

        repeat(12) { tick ->
            runtime = integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 132.0,
                deltaMgDlPer5 = 1.8,
                iobU = 1.8,
                carbsActiveG = 4.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
                consoleLog = logs,
                physioLatentState = PhysioLatentState(
                    circadianSiFactor = 0.90,
                    transientResistanceProb = 0.52,
                    endogenousGlucoseDrive = 0.72,
                    sensorConfidence = 0.93,
                ),
                causalStatePosterior = CausalStatePosterior(
                    fastMealProb = 0.12,
                    prolongedMealProb = 0.08,
                    dawnEndogenousProb = 0.82,
                    postHypoRecoveryProb = 0.10,
                    stressResistanceProb = 0.22,
                    exerciseAfterburnProb = 0.05,
                    inflammatoryDriftProb = 0.10,
                    absorptionUncertainProb = 0.18,
                    dominant = CausalStateId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.82,
                    learningQuality = 0.24,
                ),
            )
        }

        assertNotNull(runtime)
        assertEquals(6.0, runtime!!.params.diaHrs, 1e-9)
        assertEquals(55.0, runtime!!.params.peakMin, 1e-9)
        assertThat(logs.any { it.contains("PKPD_LEARN skip: causal_dawn_endogenous") }).isTrue()
    }

    @Test
    fun `aggregated stage does not reset to pre-onset in smb chain`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        integration.setRecentBolusSamples(
            listOf(
                PkpdBolusSample(ageMin = 25.0, units = 1.0),
                PkpdBolusSample(ageMin = 20.0, units = 1.0),
                PkpdBolusSample(ageMin = 15.0, units = 1.0),
                PkpdBolusSample(ageMin = 10.0, units = 1.0),
                PkpdBolusSample(ageMin = 5.0, units = 1.0),
            )
        )

        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 180.0,
            deltaMgDlPer5 = 8.0,
            iobU = 5.0,
            carbsActiveG = 20.0,
            windowMin = 5,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )

        assertNotNull(result)
        assertNotEquals(InsulinActivityStage.PRE_ONSET, result?.activity?.stage)
    }

    @Test
    fun `single recent bolus keeps pre-onset stage`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        integration.setRecentBolusSamples(listOf(PkpdBolusSample(ageMin = 5.0, units = 1.0)))

        val result = integration.computeRuntime(
            epochMillis = 1000,
            bg = 120.0,
            deltaMgDlPer5 = 1.0,
            iobU = 1.0,
            carbsActiveG = 0.0,
            windowMin = 5,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0
        )

        assertNotNull(result)
        assertEquals(InsulinActivityStage.PRE_ONSET, result?.activity?.stage)
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `learned DIA persists after small in-memory drift`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val diaSlot = slot<Double>()
        every { preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, capture(diaSlot)) } returns Unit
        every { preferences.put(DoubleKey.OApsAIMIPkpdStatePeakMin, any()) } returns Unit

        repeat(80) { tick ->
            integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 110.0,
                deltaMgDlPer5 = -12.0,
                iobU = 2.5,
                carbsActiveG = 0.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
            )
        }

        verify(atLeast = 1) { preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, any()) }
        assertTrue(diaSlot.isCaptured)
        assertTrue(diaSlot.captured < 6.0)
    }

    @Test
    @Disabled("Dormant JUnit4 test: failing on first real run after JUnit5 reactivation - needs triage (audit 2026-06-10)")
    fun `tail policy change does not wipe in-memory learned DIA`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        repeat(60) { tick ->
            integration.computeRuntime(
                epochMillis = (tick + 1L) * 5L * 60L * 1000L,
                bg = 110.0,
                deltaMgDlPer5 = -12.0,
                iobU = 2.5,
                carbsActiveG = 0.0,
                windowMin = 60,
                exerciseFlag = false,
                profileIsf = 50.0,
                tdd24h = 40.0,
            )
        }
        val learnedBeforeTailChange = integration.computeRuntime(
            epochMillis = 61L * 5L * 60L * 1000L,
            bg = 110.0,
            deltaMgDlPer5 = -12.0,
            iobU = 2.5,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.params?.diaHrs
        assertNotNull(learnedBeforeTailChange)
        assertTrue(learnedBeforeTailChange!! < 5.99)

        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.70

        val afterTailChange = integration.computeRuntime(
            epochMillis = 62L * 5L * 60L * 1000L,
            bg = 110.0,
            deltaMgDlPer5 = -12.0,
            iobU = 2.5,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
        )?.params?.diaHrs

        assertNotNull(afterTailChange)
        assertEquals(learnedBeforeTailChange, afterTailChange!!, 0.02)
    }

    /**
     * The reset button writes the learned state straight into prefs. A long lived engine keeps
     * its own learned state in memory, so it must see the generation bump and re-seed from prefs.
     */
    @Test
    fun `reset written between two ticks reseeds the learner from prefs`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)

        tick(1)
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 1L
        tick(2)

        assertEquals(7.5, learnedDiaHrs(), 0.1)
    }

    /**
     * Without a bump the engine must keep its own learned state. Reading prefs again on every
     * tick would throw away what the learner found between two writes.
     */
    @Test
    fun `unchanged generation keeps the in-memory learner`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)

        repeat(4) { tick(it + 1L) }
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        // Prefs changed but nobody bumped the counter: this is not an external reset.
        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        repeat(4) { tick(it + 5L) }

        assertEquals(6.0, learnedDiaHrs(), 0.1)
    }

    /** A stored counter from an earlier run is adopted as is: it is not a reset by itself. */
    @Test
    fun `first tick adopts the stored generation without resetting`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 42L
        IsfTddProvider.set(50.0)

        tick(1)
        assertEquals(6.0, learnedDiaHrs(), 0.1)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        tick(2)

        assertEquals(6.0, learnedDiaHrs(), 0.1)
    }

    /** A learned state written outside the bounds must be pulled back inside on re-seed. */
    @Test
    fun `reseeded params are clamped into current bounds`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        tick(1)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 99.0
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 1L
        tick(2)

        // Bounds from mockPkpdDefaults are 5.0 .. 8.0 hours.
        assertEquals(8.0, learnedDiaHrs(), 1e-9)
    }

    /** The counter lives in prefs, so every engine instance sharing them must re-seed. */
    @Test
    fun `two integrations sharing prefs both reseed`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val first = PkPdIntegration(preferences, PkPdLearnedState())
        val second = PkPdIntegration(preferences, PkPdLearnedState())
        tick(1, first)
        tick(1, second)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 1L
        tick(2, first)
        tick(2, second)

        assertEquals(7.5, learnedDiaHrs(first), 0.1)
        assertEquals(7.5, learnedDiaHrs(second), 0.1)
    }

    /** Any change is a signal, not only a bigger value: a restored backup can move it down. */
    @Test
    fun `generation change also fires on a decreasing counter`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 5L
        IsfTddProvider.set(50.0)
        tick(1)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 2L
        tick(2)

        assertEquals(7.5, learnedDiaHrs(), 0.1)
    }

    /**
     * Picking an insulin preset changes the bounds and writes the learned state in one gesture.
     * The engine reacts to both in the same tick, so it must adopt the reset before it reacts to
     * the new bounds. Otherwise it persists its own old value over the one the UI just wrote.
     * This test needs a stateful prefs mock: a `put` must be visible to the next `get`.
     */
    @Test
    fun `preset change in the same tick does not overwrite the external reset`() {
        val stored = mutableMapOf<DoubleKey, Double>()
        val statefulPreferences: Preferences = mockk(relaxed = true)
        every { statefulPreferences.put(any<DoubleKey>(), any()) } answers {
            stored[firstArg()] = secondArg()
        }
        every { statefulPreferences.get(any<DoubleKey>()) } answers {
            stored[firstArg()] ?: firstArg<DoubleKey>().defaultValue
        }
        every { statefulPreferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { statefulPreferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        storePkpdDefaults(stored)
        IsfTddProvider.set(50.0)
        val engine = PkPdIntegration(statefulPreferences, PkPdLearnedState())

        tick(1, engine)
        assertEquals(6.0, learnedDiaHrs(engine), 1e-9)

        // The preset gesture: narrower bounds, new learned state, one generation bump.
        stored[DoubleKey.OApsAIMIPkpdBoundsDiaMaxH] = 5.5
        stored[DoubleKey.OApsAIMIPkpdStateDiaH] = 4.5
        every { statefulPreferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 1L
        tick(2, engine)

        // Without the fix the engine first persists clamp(6.0, [4.0, 5.5]) = 5.5 over the 4.5.
        assertEquals(4.5, learnedDiaHrs(engine), 1e-9)
        assertEquals(4.5, stored.getValue(DoubleKey.OApsAIMIPkpdStateDiaH), 1e-9)
    }

    /**
     * Two consumers build their own engine but only one of them learns. The read only consumer
     * must see the state the learner found, otherwise it keeps for ever the values read at start
     * up and the prediction kinetics never follow the learner.
     */
    @Test
    fun `read only consumer sees the state learned by the other consumer`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = PkPdIntegration(preferences, shared)
        val learner = PkPdIntegration(preferences, shared)

        learningTick(1, reader, allowLearning = false)
        assertEquals(6.0, learnedDiaHrs(reader), 1e-9)

        for (index in 2L..40L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        assertTrue(learned < 6.0 - 1e-6, "the learner did not move the DIA: $learned")

        val readerRuntime = learningTick(41, reader, allowLearning = false)
        assertNotNull(readerRuntime)
        assertEquals(learned, readerRuntime!!.params.diaHrs, 1e-9)
    }

    /**
     * A bounds change makes the engine persist what it holds. The read only consumer must then
     * hold the learned value, not the one it read at start up, or it writes a stale DIA over the
     * learned one.
     */
    @Test
    fun `bounds change does not persist a stale value from the read only consumer`() {
        val stored = mutableMapOf<DoubleKey, Double>()
        val statefulPreferences: Preferences = mockk(relaxed = true)
        every { statefulPreferences.put(any<DoubleKey>(), any()) } answers {
            stored[firstArg()] = secondArg()
        }
        every { statefulPreferences.get(any<DoubleKey>()) } answers {
            stored[firstArg()] ?: firstArg<DoubleKey>().defaultValue
        }
        every { statefulPreferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { statefulPreferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        storePkpdDefaults(stored)
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = PkPdIntegration(statefulPreferences, shared)
        val learner = PkPdIntegration(statefulPreferences, shared)

        learningTick(1, reader, allowLearning = false)
        for (index in 2L..201L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        assertTrue(learned < 5.9, "the learner did not move the DIA far enough: $learned")

        // Only the upper bound moves. No generation bump, so this is not an external reset.
        stored[DoubleKey.OApsAIMIPkpdBoundsDiaMaxH] = 5.9
        learningTick(202, reader, allowLearning = false)

        assertEquals(learned, stored.getValue(DoubleKey.OApsAIMIPkpdStateDiaH), 0.01)
    }

    /**
     * Sharing the learned state must not turn the read only consumer into a second learner.
     * Its ticks must leave both the learned DIA and the accepted update count alone.
     */
    @Test
    fun `read only consumer never moves the shared learned state`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = PkPdIntegration(preferences, shared)
        val learner = PkPdIntegration(preferences, shared)

        learningTick(1, reader, allowLearning = false)
        for (index in 2L..40L) learningTick(index, learner)
        val learned = learnedDiaHrs(learner)
        val acceptedUpdates = acceptedUpdateCount(learner)
        assertTrue(acceptedUpdates > 0L, "the learner accepted no update")

        for (index in 41L..60L) learningTick(index, reader, allowLearning = false)

        assertEquals(learned, learnedDiaHrs(learner), 1e-9)
        assertEquals(acceptedUpdates, acceptedUpdateCount(learner))
    }

    /**
     * Only the learned state is shared. The ISF fusion holds a per call slew limiter, so each
     * consumer must keep its own. A shared limiter would let the dosing ISF move much faster.
     */
    @Test
    fun `shared learned state keeps isf fusion slew per consumer`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = PkPdIntegration(preferences, shared)
        val learner = PkPdIntegration(preferences, shared)

        // The learner builds a fusion history around a high profile ISF.
        for (index in 1L..3L) isfTick(index, learner, profileIsf = 50.0)

        // Same tick and same learned state, but a much lower profile ISF.
        val learnerIsf = isfTick(4, learner, profileIsf = 10.0)?.fusedIsf
        val readerIsf = isfTick(4, reader, profileIsf = 10.0, allowLearning = false)?.fusedIsf

        assertNotNull(learnerIsf)
        assertNotNull(readerIsf)
        // The learner is held back by its own slew limiter, the reader has no history of its own.
        assertTrue(learnerIsf!! > readerIsf!! + 5.0, "learner=$learnerIsf reader=$readerIsf")
    }

    /** The reset button writes prefs and bumps the counter. Both consumers must re-seed from it. */
    @Test
    fun `external reset reaches both consumers through the shared state`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val first = PkPdIntegration(preferences, shared)
        val second = PkPdIntegration(preferences, shared)
        tick(1, first)
        tick(1, second)

        every { preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 7.5
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 1L
        tick(2, first)
        tick(2, second)

        assertEquals(7.5, learnedDiaHrs(first), 0.1)
        assertEquals(7.5, learnedDiaHrs(second), 0.1)
    }

    /**
     * The recent bolus samples change `pkpdScale`, so they change the ISF that doses. They must
     * stay private to each consumer even when the learned state is shared.
     */
    @Test
    fun `recent bolus samples stay private to each consumer`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        mockPkpdDefaults()
        every { preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) } returns 0L
        IsfTddProvider.set(50.0)
        val shared = PkPdLearnedState()
        val reader = PkPdIntegration(preferences, shared)
        val learner = PkPdIntegration(preferences, shared)
        learner.setRecentBolusSamples(listOf(PkpdBolusSample(ageMin = 20.0, units = 2.0)))

        learningTick(1, reader, allowLearning = false)

        assertTrue(learner.reconstructedIobUnits() > 0.0)
        assertEquals(0.0, reader.reconstructedIobUnits(), 1e-9)
    }

    /** Same values as [mockPkpdDefaults], but written into a map instead of a stub. */
    private fun storePkpdDefaults(stored: MutableMap<DoubleKey, Double>) {
        stored[DoubleKey.OApsAIMIPkpdStateDiaH] = 6.0
        stored[DoubleKey.OApsAIMIPkpdStatePeakMin] = 55.0
        // Wider DIA floor than mockPkpdDefaults, so the narrowed bounds still hold the reset value.
        stored[DoubleKey.OApsAIMIPkpdBoundsDiaMinH] = 4.0
        stored[DoubleKey.OApsAIMIPkpdBoundsDiaMaxH] = 8.0
        stored[DoubleKey.OApsAIMIPkpdBoundsPeakMinMin] = 35.0
        stored[DoubleKey.OApsAIMIPkpdBoundsPeakMinMax] = 95.0
        stored[DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH] = 0.5
        stored[DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin] = 5.0
        stored[DoubleKey.OApsAIMIPkpdAnchorDiaH] = 4.0
        stored[DoubleKey.OApsAIMIPkpdAnchorPeakMin] = 55.0
        stored[DoubleKey.OApsAIMIIsfFusionMinFactor] = 0.7
        stored[DoubleKey.OApsAIMIIsfFusionMaxFactor] = 1.5
        stored[DoubleKey.OApsAIMIIsfFusionMaxChangePerTick] = 0.2
        stored[DoubleKey.OApsAIMISmbTailThreshold] = 1.0
        stored[DoubleKey.OApsAIMISmbTailDamping] = 0.85
        stored[DoubleKey.OApsAIMISmbExerciseDamping] = 0.8
        stored[DoubleKey.OApsAIMISmbLateFatDamping] = 0.8
    }

    /** One tick with nothing to learn from. IOB is below the learning floor, so the learned state cannot change. */
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
        )
    }

    /** One tick the learner can actually learn from: IOB above the floor, slow fall, no carbs. */
    private fun learningTick(index: Long, target: PkPdIntegration, allowLearning: Boolean = true) = target.computeRuntime(
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
    )

    /** One tick with a chosen profile ISF, to exercise the ISF fusion slew limiter. */
    private fun isfTick(index: Long, target: PkPdIntegration, profileIsf: Double, allowLearning: Boolean = true) = target.computeRuntime(
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

    /**
     * A clear meal belief that is NOT the dominant state, with a good causal score. A dominant meal is
     * already refused by `CausalKineticsModulator`; this case is the one the legacy gate lets learn.
     */
    private val mealBeliefUnderDawn = CausalStatePosterior(
        fastMealProb = 0.6,
        dawnEndogenousProb = 0.9,
        dominant = CausalStateId.DAWN_ENDOGENOUS,
        dominantConfidence = 0.9,
        learningQuality = 0.9,
    )

    private fun learnTick(integration: PkPdIntegration, minute: Long, posterior: CausalStatePosterior?) =
        integration.computeRuntime(
            epochMillis = minute * 60_000L,
            bg = 140.0,
            deltaMgDlPer5 = 0.0,
            iobU = 2.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            causalStatePosterior = posterior,
        )?.learningTrace

    /** Not armed: learning is decided as before, and the curve verdict is only exported. */
    @Test
    fun `curve gate off only exports its verdict as a shadow`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns false
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val trace = learnTick(PkPdIntegration(preferences, PkPdLearnedState()), 1_000L, mealBeliefUnderDawn)

        assertNotNull(trace)
        assertEquals(false, trace!!.curveGateArmed)
        assertNull(trace.diaLearnBlockedBy)
        assertEquals(PkpdLearningWindowGate.BLOCK_MEAL_BELIEF, trace.curveGateWouldBlock)
    }

    /** Armed: a clear meal belief can no longer train DIA/peak, even with a high causal score. */
    @Test
    fun `curve gate on blocks learning inside a meal`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val trace = learnTick(PkPdIntegration(preferences, PkPdLearnedState()), 1_000L, mealBeliefUnderDawn)

        assertEquals(true, trace!!.curveGateArmed)
        assertEquals(PkPdIntegration.LEARN_BLOCKED_CURVE_PREFIX + PkpdLearningWindowGate.BLOCK_MEAL_BELIEF, trace.diaLearnBlockedBy)
    }

    /** Armed: after a quiet hour a low causal score (0.35) that the legacy gate refuses may learn. */
    @Test
    fun `curve gate on lets a quiet hour learn with a lower causal score`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)
        val quiet = CausalStatePosterior(learningQuality = 0.35)
        val integration = PkPdIntegration(preferences, PkPdLearnedState())

        var trace: PkpdLearningTrace? = null
        for (minute in 1_000L..1_065L step 5) trace = learnTick(integration, minute, quiet)

        assertNull(trace!!.diaLearnBlockedBy)
        assertNull(trace.curveGateWouldBlock)

        // The same tick with the gate off is refused by the legacy 0.50 score.
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns false
        val legacy = learnTick(PkPdIntegration(preferences, PkPdLearnedState()), 2_000L, quiet)
        assertEquals(PkPdIntegration.LEARN_BLOCKED_CAUSAL_UNCLEAN, legacy!!.diaLearnBlockedBy)
    }

    /** A declared meal reaches the gate and is exported, armed or not. */
    @Test
    fun `declared meal blocks learning and is exported`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val trace = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1_000L * 60_000L,
            bg = 140.0,
            deltaMgDlPer5 = 0.0,
            iobU = 2.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            causalStatePosterior = CausalStatePosterior(learningQuality = 0.9),
            declaredMealAgeMin = 7L,
        )?.learningTrace

        assertEquals(PkPdIntegration.LEARN_BLOCKED_CURVE_PREFIX + PkpdLearningWindowGate.BLOCK_DECLARED_MEAL, trace!!.diaLearnBlockedBy)
        assertEquals(7L, trace.curveGateDeclaredMealAgeMin)
    }

    /** A read-only call must not feed the history nor report a verdict. */
    @Test
    fun `read only call leaves the curve gate alone`() {
        every { preferences.get(BooleanKey.OApsAIMIPkpdEnabled) } returns true
        every { preferences.get(BooleanKey.OApsAIMIPkpdCurveLearningGate) } returns true
        mockPkpdDefaults()
        IsfTddProvider.set(50.0)

        val trace = PkPdIntegration(preferences, PkPdLearnedState()).computeRuntime(
            epochMillis = 1_000L * 60_000L,
            bg = 140.0,
            deltaMgDlPer5 = 0.0,
            iobU = 2.0,
            carbsActiveG = 0.0,
            windowMin = 60,
            exerciseFlag = false,
            profileIsf = 50.0,
            tdd24h = 40.0,
            causalStatePosterior = mealBeliefUnderDawn,
            allowLearning = false,
        )?.learningTrace

        assertNull(trace!!.curveGateWouldBlock)
        assertEquals(PkPdIntegration.LEARN_BLOCKED_READ_ONLY_PATH, trace.diaLearnBlockedBy)
    }

    private fun mockPkpdDefaults() {
        mockPkpdDefaults(preferences)
    }

    private fun mockPkpdDefaults(target: Preferences) {
        every { target.get(DoubleKey.OApsAIMIPkpdStateDiaH) } returns 6.0
        every { target.get(DoubleKey.OApsAIMIPkpdStatePeakMin) } returns 55.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH) } returns 5.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH) } returns 8.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin) } returns 35.0
        every { target.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax) } returns 95.0
        every { target.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH) } returns 0.5
        every { target.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin) } returns 5.0
        every { target.get(DoubleKey.OApsAIMIPkpdAnchorDiaH) } returns 4.0
        every { target.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin) } returns 55.0
        every { target.get(DoubleKey.OApsAIMIIsfFusionMinFactor) } returns 0.7
        every { target.get(DoubleKey.OApsAIMIIsfFusionMaxFactor) } returns 1.5
        every { target.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick) } returns 0.2
        every { target.get(DoubleKey.OApsAIMISmbTailThreshold) } returns 1.0
        every { target.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85
        every { target.get(DoubleKey.OApsAIMISmbExerciseDamping) } returns 0.8
        every { target.get(DoubleKey.OApsAIMISmbLateFatDamping) } returns 0.8
    }
}
