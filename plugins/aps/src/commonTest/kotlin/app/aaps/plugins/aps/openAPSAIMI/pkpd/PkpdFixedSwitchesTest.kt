package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Step P3: switches and weights that were keys are constants now. They must keep the default
 * behaviour of the old keys exactly, so dosing does not move for a user who never changed them.
 * - The DIA and peak governors blend the learned values with the old weights (0.45 and 0.55).
 * - The prediction array is built on the learned kinetics whenever they are valid
 *   (the old key `OApsAIMIPkpdPredictionKinetics`, default on).
 */
class PkpdFixedSwitchesTest {

    private val preferences = MapPreferences()

    private val diagnostics = PkpdLearningDiagnostics.from(
        causalStatePosterior = null,
        allowLearning = false,
        exerciseFlag = false,
        iobU = 0.0,
        carbsActiveG = 0.0,
        bg = 120.0,
        deltaMgDlPer5 = 0.0,
    )

    @Test
    fun `the DIA governor blends the learned DIA with the old key default`() {
        assertEquals(0.45, InsulinKineticsAuthority.DIA_LEARNED_BLEND_WEIGHT)
    }

    @Test
    fun `the peak governor blends the learned peak with the old key default`() {
        assertEquals(0.55, InsulinKineticsAuthority.PEAK_LEARNED_BLEND_WEIGHT)
    }

    @Test
    fun `resolve doses the profile and learned values blended with the fixed weights`() {
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        val view = InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray<IobTotal>(),
                profileDiaHours = 5.0,
                profilePeakMinutes = 75,
                pkpdRuntime = runtime(PkPdParams(diaHrs = 7.0, peakMin = 55.0)),
                causalPosterior = null,
                preferences = preferences,
                learningDiagnostics = diagnostics,
            ),
        )
        // DIA: 5.0 x 0.55 + 7.0 x 0.45 = 5.9 h. Peak: 75 x 0.45 + 55 x 0.55 = 64 min.
        assertEquals(5.9, view.effective.diaHours, 1e-9)
        assertEquals(64.0, view.effective.peakMinutes, 1e-9)
    }

    @Test
    fun `valid learned kinetics always give a prediction array`() {
        val kinetics = assertNotNull(LearnedPredictionKinetics.fromLearned(learnedDiaHrs = 6.0, learnedPeakMin = 55.0))
        assertEquals(6L * 3_600_000L, kinetics.insulinEndTimeMs)
        assertEquals(55L * 60_000L, kinetics.insulinPeakTimeMs)
    }

    @Test
    fun `invalid learned kinetics fall back to the profile array`() {
        assertNull(LearnedPredictionKinetics.fromLearned(null, 55.0))
        assertNull(LearnedPredictionKinetics.fromLearned(6.0, null))
        assertNull(LearnedPredictionKinetics.fromLearned(Double.NaN, 55.0))
        assertNull(LearnedPredictionKinetics.fromLearned(2.9, 55.0))
        assertNull(LearnedPredictionKinetics.fromLearned(12.1, 55.0))
        assertNull(LearnedPredictionKinetics.fromLearned(6.0, 19.0))
        // Peak 180 min is not below half of a 6 h DIA: the exponential model is not valid.
        assertNull(LearnedPredictionKinetics.fromLearned(6.0, 180.0))
    }

    private fun runtime(params: PkPdParams) = PkPdRuntime(
        params = params,
        tailFraction = 0.2,
        fusedIsf = 50.0,
        profileIsf = 50.0,
        tddIsf = 50.0,
        pkpdScale = 1.0,
        weightKineticFactor = 1.0,
        physioAbsorptionFactor = 1.0,
        physioSiFactor = 1.0,
        damping = SmbDamping(),
        activity = InsulinActivityState(
            window = InsulinActivityWindow(onsetMin = 20.0, peakMin = 70.0, offsetMin = 180.0, diaMin = 240.0),
            relativeActivity = 0.5,
            normalizedPosition = 0.4,
            postWindowFraction = 0.1,
            anticipationWeight = 0.1,
            minutesUntilOnset = 0.0,
            stage = InsulinActivityStage.RISING,
        ),
    )
}
