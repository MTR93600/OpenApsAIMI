package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.compose.PkpdLearningPace
import app.aaps.plugins.aps.openAPSAIMI.compose.applyTo
import app.aaps.plugins.aps.openAPSAIMI.compose.readFrom
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PK/PD learner takes its seed, its anchor and its bounds from the insulin of the running
 * profile (`iCfg.dia`, `iCfg.peak`), and starts again from it when that insulin changes.
 */
class PkpdProfileInsulinTest {

    private val preferences = MapPreferences().apply {
        booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
    }
    private val learnedState = PkPdLearnedState()

    private val behaviorProfileSource = object : AimiBehaviorProfileSource {
        override fun read(preferences: Preferences) = AimiBehaviorRuntimeProfile(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.AssistedApplication,
        )
    }

    private val lyumjev = ProfileInsulin(diaHours = 5.0, peakMinutes = 55.0)
    private val humalog = ProfileInsulin(diaHours = 7.0, peakMinutes = 75.0)

    private fun engine() = PkPdIntegration(preferences, learnedState, behaviorProfileSource)

    /** One quiet tick that the learner refuses (IOB too low), so only the seeding logic runs. */
    private fun tick(
        integration: PkPdIntegration,
        profileInsulin: ProfileInsulin?,
        allowLearning: Boolean = true,
        minute: Long = 0,
    ): PkPdRuntime? = integration.computeRuntime(
        epochMillis = 1_800_000_000_000L + minute * 60_000L,
        bg = 120.0,
        deltaMgDlPer5 = 0.0,
        iobU = 0.0,
        carbsActiveG = 0.0,
        windowMin = 90,
        exerciseFlag = false,
        profileIsf = 50.0,
        tdd24h = 40.0,
        allowLearning = allowLearning,
        profileInsulin = profileInsulin,
    )

    private fun storedDia() = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH)
    private fun storedPeak() = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
    private fun generation() = preferences.get(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)

    private fun markSeen(profile: ProfileInsulin) {
        preferences.longs[LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin] = (profile.diaHours * 60.0).toLong()
        preferences.longs[LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin] = profile.peakMinutes.toLong()
    }

    @Test
    fun bounds_come_from_the_profile_insulin() {
        val bounds = PkpdProfileInsulinBounds.bounds(lyumjev, maxDiaChangePerDayH = 2.0, maxPeakChangePerDayMin = 20.0)

        assertEquals(3.75, bounds.diaMinH, 1e-9)
        assertEquals(6.25, bounds.diaMaxH, 1e-9)
        assertEquals(41.25, bounds.peakMinMin, 1e-9)
        assertEquals(74.25, bounds.peakMinMax, 1e-9)
        assertEquals(2.0, bounds.maxDiaChangePerDayH, 1e-9)
        assertEquals(20.0, bounds.maxPeakChangePerDayMin, 1e-9)
    }

    @Test
    fun peak_35_is_valid_and_the_lowest_peak_bound_is_35() {
        val modified = ProfileInsulin(diaHours = 5.0, peakMinutes = 35.0)
        val bounds = PkpdProfileInsulinBounds.bounds(modified, maxDiaChangePerDayH = 2.0, maxPeakChangePerDayMin = 20.0)
        assertEquals(35.0, bounds.peakMinMin, 1e-9)
        assertEquals(47.25, bounds.peakMinMax, 1e-9)
        assertEquals(PkPdParams(5.0, 35.0), PkpdProfileInsulinBounds.seed(modified))

        // 0.75 x 45 = 33.75 is below the editor limit, so the floor is 35.
        val near = PkpdProfileInsulinBounds.bounds(ProfileInsulin(5.0, 45.0), 2.0, 20.0)
        assertEquals(35.0, near.peakMinMin, 1e-9)
        assertEquals(60.75, near.peakMinMax, 1e-9)
    }

    @Test
    fun learner_bounds_and_anchor_are_the_profile_insulin() {
        markSeen(lyumjev)
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 5.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 60.0

        assertNotNull(tick(engine(), lyumjev))

        val cfg = assertNotNull(learnedState.lastLearningCfg)
        assertEquals(5.0, cfg.anchorDiaHrs, 1e-9)
        assertEquals(55.0, cfg.anchorPeakMin, 1e-9)
        assertEquals(3.75, cfg.bounds.diaMinH, 1e-9)
        assertEquals(6.25, cfg.bounds.diaMaxH, 1e-9)
        assertEquals(41.25, cfg.bounds.peakMinMin, 1e-9)
        assertEquals(74.25, cfg.bounds.peakMinMax, 1e-9)
        // Same insulin as last time: the learned values are kept.
        assertEquals(5.5, storedDia(), 1e-9)
        assertEquals(60.0, storedPeak(), 1e-9)
    }

    @Test
    fun a_new_profile_insulin_reseeds_the_learned_state() {
        markSeen(lyumjev)
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 5.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 60.0
        val integration = engine()
        tick(integration, lyumjev, minute = 0)
        val generationBefore = generation()

        val runtime = assertNotNull(tick(integration, humalog, minute = 5))

        assertEquals(7.0, storedDia(), 1e-9)
        assertEquals(75.0, storedPeak(), 1e-9)
        assertEquals(PkPdParams(7.0, 75.0), runtime.params)
        assertTrue(generation() > generationBefore, "the generation must move so every reader re-seeds")
        assertEquals(7L * 60L, preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin))
        assertEquals(75L, preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin))
        val cfg = assertNotNull(learnedState.lastLearningCfg)
        assertEquals(7.0, cfg.anchorDiaHrs, 1e-9)
        assertEquals(75.0, cfg.anchorPeakMin, 1e-9)
    }

    @Test
    fun a_reset_request_reseeds_from_the_profile_insulin() {
        markSeen(lyumjev)
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 6.0
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 70.0
        val integration = engine()
        tick(integration, lyumjev, minute = 0)

        PkpdProfileInsulinMemory.requestReset(preferences)
        tick(integration, lyumjev, minute = 5)

        assertEquals(5.0, storedDia(), 1e-9)
        assertEquals(55.0, storedPeak(), 1e-9)
    }

    @Test
    fun migration_keeps_the_learned_state_but_clamps_it_into_the_new_bounds() {
        // An install from before this change: learned values exist, no profile insulin was ever stored.
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 9.0
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 40.0

        val runtime = assertNotNull(tick(engine(), lyumjev))

        assertEquals(6.25, storedDia(), 1e-9)
        assertEquals(41.25, storedPeak(), 1e-9)
        assertEquals(PkPdParams(6.25, 41.25), runtime.params)
        assertEquals(300L, preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin))
        assertEquals(55L, preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinPeakMin))
    }

    @Test
    fun migration_keeps_a_learned_state_that_is_already_inside_the_bounds() {
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 5.6
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 50.0

        tick(engine(), lyumjev)

        assertEquals(5.6, storedDia(), 1e-9)
        assertEquals(50.0, storedPeak(), 1e-9)
    }

    @Test
    fun with_nothing_learned_yet_the_seed_is_the_profile_insulin() {
        val runtime = assertNotNull(tick(engine(), lyumjev))

        assertEquals(PkPdParams(5.0, 55.0), runtime.params)
        assertEquals(5.0, storedDia(), 1e-9)
        assertEquals(55.0, storedPeak(), 1e-9)
    }

    @Test
    fun a_read_only_call_without_profile_insulin_uses_the_last_one_seen() {
        markSeen(lyumjev)
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 5.5
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 60.0

        val runtime = assertNotNull(tick(engine(), profileInsulin = null, allowLearning = false))

        assertEquals(PkPdParams(5.5, 60.0), runtime.params)
        assertEquals(5.0, assertNotNull(learnedState.lastLearningCfg).anchorDiaHrs, 1e-9)
    }

    @Test
    fun without_any_profile_insulin_there_is_no_runtime() {
        assertNull(tick(engine(), profileInsulin = null, allowLearning = false))
    }

    @Test
    fun the_default_learning_pace_is_the_faster_one() {
        // Nothing stored: the key defaults.
        assertEquals(PkpdLearningPace.FASTER, PkpdLearningPace.NORMAL.readFrom(preferences))
        val dia = PkpdLearningBounds.coerceMaxDiaChangePerDayH(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH.defaultValue)
        val peak = PkpdLearningBounds.coerceMaxPeakChangePerDayMin(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin.defaultValue)
        assertEquals(2.0, dia, 1e-9)
        assertEquals(20.0, peak, 1e-9)

        markSeen(lyumjev)
        tick(engine(), lyumjev)
        val bounds = assertNotNull(learnedState.lastLearningCfg).bounds
        assertEquals(2.0, bounds.maxDiaChangePerDayH, 1e-9)
        assertEquals(20.0, bounds.maxPeakChangePerDayMin, 1e-9)
    }

    @Test
    fun an_old_stored_pace_above_the_fastest_one_is_capped_to_it() {
        // 3.0 h/day and 20 min/day were the defaults before the pace chips.
        assertEquals(2.0, PkpdLearningBounds.coerceMaxDiaChangePerDayH(3.0), 1e-9)
        assertEquals(20.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(40.0), 1e-9)
        // A pace the user picked is kept.
        assertEquals(0.5, PkpdLearningBounds.coerceMaxDiaChangePerDayH(0.5), 1e-9)
        assertEquals(5.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(5.0), 1e-9)
    }

    @Test
    fun the_faster_chip_writes_the_faster_pace() {
        PkpdLearningPace.FASTER.applyTo(preferences)
        assertEquals(2.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH), 1e-9)
        assertEquals(20.0, preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin), 1e-9)
        assertEquals(PkpdLearningPace.FASTER, PkpdLearningPace.NORMAL.readFrom(preferences))
    }

    @Test
    fun the_estimator_gain_is_raised() {
        assertEquals(0.04, PkPdLearningConfig().lr, 1e-12)
    }

    private fun governed(profileDiaHours: Double, profilePeakMinutes: Int, physioPeakShiftMinutes: Int) =
        InsulinKineticsAuthority.resolve(
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray(),
                profileDiaHours = profileDiaHours,
                profilePeakMinutes = profilePeakMinutes,
                pkpdRuntime = null,
                causalPosterior = null,
                physioPeakShiftMinutes = physioPeakShiftMinutes,
                preferences = preferences,
                learningDiagnostics = PkpdLearningDiagnostics.from(
                    causalStatePosterior = null,
                    allowLearning = false,
                    exerciseFlag = false,
                    iobU = 0.0,
                    carbsActiveG = 0.0,
                    bg = 120.0,
                    deltaMgDlPer5 = 0.0,
                ),
            ),
        ).effective

    @Test
    fun the_governors_keep_their_old_fixed_envelope() {
        // Only the learner moved to the profile insulin bounds. The DIA and peak governors keep the
        // old envelope (DIA 4-24 h, peak 30-240 min), so a physio shift is not cut to 1.35 x profile.
        assertEquals(95.0, governed(5.0, 55, physioPeakShiftMinutes = 40).peakMinutes, 1e-9)
        // The owner's 28 min insulin: the shift is kept, not pinned near 37.8 min.
        assertEquals(48.0, governed(5.0, 28, physioPeakShiftMinutes = 20).peakMinutes, 1e-9)
        assertEquals(240.0, governed(5.0, 75, physioPeakShiftMinutes = 400).peakMinutes, 1e-9)
    }

    @Test
    fun a_bad_profile_insulin_keeps_the_fixed_dosing_floor() {
        val zero = governed(0.0, 0, physioPeakShiftMinutes = 0)
        assertEquals(InsulinKineticsAuthority.GOVERNOR_DIA_MIN_H, zero.diaHours, 1e-9)
        assertTrue(zero.peakMinutes >= InsulinKineticsAuthority.GOVERNOR_PEAK_MIN_MIN)
    }

    @Test
    fun an_insulin_change_while_pkpd_is_off_reseeds_when_pkpd_is_turned_on() {
        // The update runs with PK/PD off: the migration still happens and the insulin is remembered.
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = false
        preferences.doubles[DoubleKey.OApsAIMIPkpdStateDiaH] = 5.6
        preferences.doubles[DoubleKey.OApsAIMIPkpdStatePeakMin] = 50.0
        val integration = engine()
        assertNull(tick(integration, lyumjev, minute = 0))
        assertEquals(300L, preferences.get(LongNonKey.OApsAIMIPkpdProfileInsulinDiaMin))

        // Still off, the user moves to another insulin, then turns PK/PD on.
        assertNull(tick(integration, humalog, minute = 5))
        preferences.booleans[BooleanKey.OApsAIMIPkpdEnabled] = true
        val runtime = assertNotNull(tick(integration, humalog, minute = 10))

        // It starts again from the new insulin, not from the old learned values clamped.
        assertEquals(PkPdParams(7.0, 75.0), runtime.params)
        assertEquals(7.0, storedDia(), 1e-9)
        assertEquals(75.0, storedPeak(), 1e-9)
    }
}
