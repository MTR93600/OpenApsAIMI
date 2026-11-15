package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPreferenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step C1 of the settings plan: the Control Center is disarmed.
 *
 * - A family level writes tuning keys only, never a dose limit.
 * - The manifest names every writer of a key that a screen, a family or TPO writes.
 * - The behaviour profile that reaches dosing is a fixed neutral profile, so a display level or a
 *   dead switch can not change a dose.
 */
class AimiControlCenterDisarmTest {

    private val doseLimitKeys: Set<String> = setOf(
        DoubleKey.OApsAIMIMaxSMB.key,
        DoubleKey.OApsAIMIHighBGMaxSMB.key,
        DoubleKey.OApsAIMIPriorityMaxIobFactor.key,
        DoubleKey.OApsAIMIPriorityMaxIobExtraU.key,
        DoubleKey.autodriveMaxBasal.key,
        DoubleKey.meal_modes_MaxBasal.key,
    )

    /** Stored states that make a ladder move: the defaults, every double at its bound, booleans both ways. */
    private fun preferenceStates(): List<MapPreferences> {
        val doubleKeys = DoubleKey.entries
        val booleanKeys = BooleanKey.entries
        return listOf(
            MapPreferences(),
            MapPreferences().apply { doubleKeys.forEach { doubles[it] = it.min } },
            MapPreferences().apply { doubleKeys.forEach { doubles[it] = it.max } },
            MapPreferences().apply { booleanKeys.forEach { booleans[it] = true } },
            MapPreferences().apply { booleanKeys.forEach { booleans[it] = false } },
        )
    }

    private fun draft(level: Int, physio: Int, autonomy: AimiAutonomyMode) = AimiControlCenterDraft(
        protectionLevel = level,
        mealCaptureLevel = level,
        stabilityLevel = level,
        physioLevel = physio,
        autonomyMode = autonomy,
    )

    /** Every change any family can plan, from any level to any other level. */
    private fun allPlannedChanges(): List<AimiPreferenceChange> {
        val changes = mutableListOf<AimiPreferenceChange>()
        preferenceStates().forEach { preferences ->
            for (from in 0..4) for (to in 0..4) {
                val fromDraft = draft(from, from.coerceAtMost(2), AimiAutonomyMode.entries[from.coerceAtMost(3)])
                val toDraft = draft(to, to.coerceAtMost(2), AimiAutonomyMode.entries[to.coerceAtMost(3)])
                changes += buildAimiControlCenterPendingChanges(preferences, fromDraft, toDraft)
                    .familyPlans
                    .flatMap { it.changes }
            }
        }
        return changes
    }

    private fun keysWrittenByFamilies(): Set<String> = allPlannedChanges().map { it.preferenceKey }.toSet()

    @Test
    fun `no family level writes a dose limit`() {
        val written = keysWrittenByFamilies()
        assertEquals(emptySet(), written.intersect(doseLimitKeys))
    }

    @Test
    fun `the exercise damping rungs never go below the damping floor`() {
        // Since P3 the loop reads a value below the floor as the floor, so a lower rung would show
        // a value that does nothing.
        val exerciseTargets = mutableListOf<Double>()
        preferenceStates().forEach { preferences ->
            for (from in 0..4) for (to in 0..4) {
                val fromDraft = draft(from, 1, AimiAutonomyMode.ControlledAuthority)
                val toDraft = draft(to, 1, AimiAutonomyMode.ControlledAuthority)
                buildAimiControlCenterPendingChanges(preferences, fromDraft, toDraft)
                    .familyPlans
                    .flatMap { it.changes }
                    .filter { it.preferenceKey == DoubleKey.OApsAIMISmbExerciseDamping.key }
                    .forEach { change ->
                        val probe = MapPreferences()
                        change.apply(probe)
                        exerciseTargets += probe.get(DoubleKey.OApsAIMISmbExerciseDamping)
                    }
            }
        }
        assertTrue(exerciseTargets.isNotEmpty(), "the stability family should still write the exercise damping")
        val belowFloor = exerciseTargets.filter { it < SmbDamping.DAMPING_FLOOR - 1e-9 }
        assertEquals(emptyList(), belowFloor)
    }

    /** The value one stability step writes for [key], or the stored value when nothing is written. */
    private fun stabilityStep(key: DoubleKey, stored: Double, from: Int, to: Int): Double {
        val preferences = MapPreferences().apply { doubles[key] = stored }
        val change = buildAimiControlCenterPendingChanges(
            preferences,
            draft(from, 1, AimiAutonomyMode.ControlledAuthority),
            draft(to, 1, AimiAutonomyMode.ControlledAuthority),
        ).familyPlans.flatMap { it.changes }.firstOrNull { it.preferenceKey == key.key }
        change?.apply?.invoke(preferences)
        return preferences.get(key)
    }

    @Test
    fun `an old exercise damping below the floor steps up to the same effective value as before C1`() {
        // Before C1 the ladder was 0.30, 0.45, 0.60, 0.72, 0.85 and the loop reads a value below
        // 0.5 as 0.5. From an old 0.30 one step up gave 0.45 (acts as 0.5) and two steps 0.60.
        fun effective(value: Double) = value.coerceAtLeast(SmbDamping.DAMPING_FLOOR)
        val key = DoubleKey.OApsAIMISmbExerciseDamping
        assertEquals(0.50, effective(stabilityStep(key, 0.30, from = 2, to = 3)), 1e-9)
        assertEquals(0.60, effective(stabilityStep(key, 0.30, from = 2, to = 4)), 1e-9)
        assertEquals(0.60, effective(stabilityStep(key, 0.45, from = 2, to = 3)), 1e-9)
        assertEquals(0.60, effective(stabilityStep(key, 0.50, from = 2, to = 3)), 1e-9)
        assertEquals(0.50, effective(stabilityStep(key, 0.85, from = 4, to = 0)), 1e-9)
        assertEquals(0.50, effective(stabilityStep(key, 0.30, from = 2, to = 1)), 1e-9)
    }

    @Test
    fun `an old tail damping in the legacy zone steps from its effective value`() {
        // A stored 0.20 acts as 0.85 (see PkpdSmbTailDamping.effectiveStoredValue). A TPO revert or an
        // import can bring such a value back while the app runs, after the start migration.
        val key = DoubleKey.OApsAIMISmbTailDamping
        val moreReactive = stabilityStep(key, 0.20, from = 2, to = 3)
        assertTrue(moreReactive > PkpdSmbTailDamping.DAMPING_NEUTRAL, "more reactive must weaken the damping, got $moreReactive")
        val smoother = stabilityStep(key, 0.20, from = 2, to = 1)
        assertTrue(
            smoother < PkpdSmbTailDamping.DAMPING_NEUTRAL && smoother > PkpdSmbTailDamping.LEGACY_NEUTRAL_CUTOFF,
            "smoother must strengthen the damping inside the band, got $smoother",
        )
    }

    @Test
    fun `the family registry no longer lists a dose limit as a managed key`() {
        val managed = doseLimitKeys.filter { key ->
            AimiBehaviorFamilyId.entries.any { family -> AimiBehaviorFamilyRegistry.isManagedBy(family, key) }
        }
        assertEquals(emptyList(), managed)
    }

    @Test
    fun `every key a family writes is declared with the SLIDER writer`() {
        val missing = keysWrittenByFamilies().filter { key ->
            val entry = AimiSettingsManifest.entryForKeyString(key)
            entry == null || AimiSettingsWriter.SLIDER !in entry.writers
        }
        assertEquals(emptyList(), missing.sorted())
    }

    @Test
    fun `every key TPO may write is declared with the LOOP writer`() {
        val missing = TpoPreferenceKeys.allKeys().map { it.key }.filter { key ->
            val entry = AimiSettingsManifest.entryForKeyString(key)
            entry == null || AimiSettingsWriter.LOOP !in entry.writers
        }
        assertEquals(emptyList(), missing.sorted())
    }

    @Test
    fun `every TPO key a settings screen offers is declared with the USER writer`() {
        val onScreen = AimiSettingsScreens.sections
            .filter { it.content in AimiSettingsScreens.routedContents }
            .flatMap { section -> section.keys.map { it.key } }
            .toSet()
        val missing = TpoPreferenceKeys.allKeys().map { it.key }
            .filter { it in onScreen }
            .filter { key -> AimiSettingsWriter.USER !in AimiSettingsManifest.entryForKeyString(key)!!.writers }
        assertEquals(emptyList(), missing.sorted())
    }

    @Test
    fun `every key a routed FIELDS section offers is declared with the USER writer`() {
        val missing = AimiSettingsScreens.sections
            .filter { it.content in AimiSettingsScreens.routedContents && it.kind == AimiSettingsSectionKind.FIELDS }
            .flatMap { section -> section.keys.map { it.key } }
            .toSet()
            .filter { key ->
                val entry = AimiSettingsManifest.entryForKeyString(key)
                entry != null && AimiSettingsWriter.USER !in entry.writers
            }
        assertEquals(emptyList(), missing.sorted())
    }

    /**
     * The keys the Advisor can propose (`AimiAction.PreferenceUpdate` in `advisor/` and in
     * `SafetyAggressionPlugin`). The person accepts a proposal on a screen, so each one is a USER
     * write. Keep this list in sync when a proposal is added or removed.
     */
    private val advisorProposalKeys: Set<String> = setOf(
        DoubleKey.OApsAIMIMaxSMB.key,
        DoubleKey.OApsAIMILunchFactor.key,
        BooleanKey.OApsAIMIPkpdEnabled.key,
        DoubleKey.OApsAIMIIsfFusionMaxFactor.key,
        DoubleKey.OApsAIMISmbTailDamping.key,
        BooleanKey.OApsAIMIRecursiveBeliefShadow.key,
        BooleanKey.OApsAIMIRecursiveBeliefAuthority.key,
        BooleanKey.OApsAIMIHyperTrajectoryRelease.key,
        BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key,
        DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key,
        DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key,
        DoubleKey.OApsAIMIPriorityMaxIobFactor.key,
        DoubleKey.OApsAIMIPriorityMaxIobExtraU.key,
    )

    @Test
    fun `every key the Advisor can propose is declared with the USER writer`() {
        val missing = advisorProposalKeys.filter { key ->
            val entry = AimiSettingsManifest.entryForKeyString(key)
            entry == null || AimiSettingsWriter.USER !in entry.writers
        }
        assertEquals(emptyList(), missing.sorted())
    }

    @Test
    fun `a Control Center control section lists exactly the keys its family writes`() {
        val familyBySection = mapOf(
            AimiSettingsSectionId.ControlCenterProtectionLevel to AimiBehaviorFamilyId.Protection,
            AimiSettingsSectionId.ControlCenterMealCaptureLevel to AimiBehaviorFamilyId.MealCapture,
            AimiSettingsSectionId.ControlCenterStabilityLevel to AimiBehaviorFamilyId.Stability,
            AimiSettingsSectionId.ControlCenterAutonomyMode to AimiBehaviorFamilyId.Autonomy,
        )
        val writtenByFamily = mutableMapOf<AimiBehaviorFamilyId, MutableSet<String>>()
        preferenceStates().forEach { preferences ->
            for (from in 0..4) for (to in 0..4) {
                val fromDraft = draft(from, from.coerceAtMost(2), AimiAutonomyMode.entries[from.coerceAtMost(3)])
                val toDraft = draft(to, to.coerceAtMost(2), AimiAutonomyMode.entries[to.coerceAtMost(3)])
                buildAimiControlCenterPendingChanges(preferences, fromDraft, toDraft).familyPlans.forEach { plan ->
                    writtenByFamily.getOrPut(plan.familyId) { mutableSetOf() } += plan.changes.map { it.preferenceKey }
                }
            }
        }
        val controlSections = AimiSettingsScreens.sections
            .filter { it.content == AimiSettingsContentId.ControlCenter && it.kind == AimiSettingsSectionKind.CONTROL }
        assertEquals(familyBySection.keys, controlSections.map { it.id }.toSet())
        controlSections.forEach { section ->
            val family = familyBySection.getValue(section.id)
            assertEquals(
                writtenByFamily[family].orEmpty().sorted(),
                section.keys.map { it.key }.toSet().sorted(),
                "keys of ${section.id}",
            )
        }
    }

    /**
     * A unit check of the profile source that is bound for dosing (`@ContributesBinding`). It does
     * not prove the wiring: it would still pass if a dosing path got its profile some other way.
     */
    @Test
    fun `the neutral profile source gives the same profile for very different stored settings`() {
        val source = NeutralAimiBehaviorProfileSource()
        val profiles = preferenceStates().map { source.read(it) }.toSet()
        assertEquals(setOf(AimiBehaviorRuntimeProfile.NEUTRAL), profiles)
    }

    @Test
    fun `the neutral profile is the balanced middle of every family`() {
        val neutral = AimiBehaviorRuntimeProfile.NEUTRAL
        assertEquals(2, neutral.protectionLevel)
        assertEquals(2, neutral.mealCaptureLevel)
        assertEquals(2, neutral.stabilityLevel)
        assertEquals(1, neutral.physioLevel)
        assertEquals(AimiAutonomyMode.ControlledAuthority, neutral.autonomyMode)
    }

    @Test
    fun `the neutral profile leaves the dosing factors at their neutral values`() {
        val neutral = AimiBehaviorRuntimeProfile.NEUTRAL
        assertEquals(1.0, neutral.pkpdMealAbsorptionFactor(mealModeActive = true), 1e-12)
        assertEquals(1.0, neutral.pkpdMealAbsorptionFactor(mealModeActive = false), 1e-12)
        assertEquals(1.0, neutral.pkpdCorrectionAggressionFactor(), 1e-12)
        assertEquals(1.0, neutral.pkpdStabilityAggressionFactor(isRising = true), 1e-12)
        assertEquals(1.0f, neutral.mlCorrectionFractionMultiplier(), 1e-6f)
        // The same values the meal hypothesis uses when it gets no profile at all.
        assertEquals(0.32, neutral.mealSuppressionCap(), 1e-12)
        assertEquals(0.10, neutral.competingNonMealDominanceMargin(), 1e-12)
        assertEquals(0.60, neutral.competingNonMealConfidenceFloor(), 1e-12)
        assertEquals(0.08, neutral.suppressMealDecisionMargin(), 1e-12)
        assertEquals(0.60, neutral.suppressMealDecisionFloor(), 1e-12)
        // Physio level 1 of 2: half of the physio signal is blended toward neutral.
        assertEquals(0.725, neutral.pkpdPhysioBlendFraction(), 1e-12)
    }
}
