package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPreferenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Steps C1 and S2 of the settings plan: the Control Center was disarmed (C1), then removed with its
 * family ladders (S2).
 *
 * - The manifest names every writer of a key that a screen, a slider or TPO writes.
 * - The behaviour profile that reaches dosing is a fixed neutral profile, so a display level or a
 *   dead switch can not change a dose.
 */
class AimiControlCenterDisarmTest {

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

    /**
     * Step S2 removed the Control Center and its family ladders, so the only SLIDER writers left are
     * the PK/PD sliders and the learning pace chips. A SLIDER fact on any other key would tell the
     * audit screen that a control writes it.
     */
    @Test
    fun `the SLIDER writer is declared exactly for the keys a slider drives`() {
        val drivenBySlider = AimiSettingsScreens.sections
            .filter { it.content in AimiSettingsScreens.routedContents && it.kind == AimiSettingsSectionKind.CONTROL }
            .flatMap { section -> section.keys.map { it.key } }
            .toSet()
        val declaredSlider = AimiSettingsManifest.entries
            .filter { AimiSettingsWriter.SLIDER in it.writers }
            .map { it.key.key }
            .toSet()
        assertEquals(drivenBySlider.sorted(), declaredSlider.sorted())
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

    /**
     * A unit check of the profile source that is bound for dosing (`@ContributesBinding`). It does
     * not prove the wiring: it would still pass if a dosing path got its profile some other way.
     * The display-level parts never move; only the autonomy mode follows the Autodrive switches.
     */
    @Test
    fun `the profile source keeps every display level part neutral for very different stored settings`() {
        val source = NeutralAimiBehaviorProfileSource()
        val profiles = preferenceStates().map { source.read(it).copy(autonomyMode = AimiAutonomyMode.ControlledAuthority) }.toSet()
        assertEquals(setOf(AimiBehaviorRuntimeProfile.NEUTRAL), profiles)
    }

    private fun autodrive(
        active: Boolean,
        authoritative: Boolean = false,
        hyperTrajectoryRelease: Boolean = false,
        recursiveBeliefAuthority: Boolean = false,
    ) = MapPreferences().apply {
        booleans[BooleanKey.OApsAIMIautoDriveActive] = active
        booleans[BooleanKey.OApsAIMIautoDriveAuthoritative] = authoritative
        booleans[BooleanKey.OApsAIMIHyperTrajectoryRelease] = hyperTrajectoryRelease
        booleans[BooleanKey.OApsAIMIRecursiveBeliefAuthority] = recursiveBeliefAuthority
    }

    /** Step T2: the autonomy mode comes from the real Autodrive switches again, as before C1. */
    @Test
    fun `the autonomy mode follows the real Autodrive switches`() {
        val source = NeutralAimiBehaviorProfileSource()
        assertEquals(AimiAutonomyMode.Observation, source.read(autodrive(active = false, authoritative = true)).autonomyMode)
        assertEquals(AimiAutonomyMode.Recommendations, source.read(autodrive(active = true)).autonomyMode)
        assertEquals(AimiAutonomyMode.AssistedApplication, source.read(autodrive(active = true, hyperTrajectoryRelease = true)).autonomyMode)
        assertEquals(AimiAutonomyMode.ControlledAuthority, source.read(autodrive(active = true, authoritative = true)).autonomyMode)
        assertEquals(AimiAutonomyMode.ControlledAuthority, source.read(autodrive(active = true, recursiveBeliefAuthority = true)).autonomyMode)
        // The key defaults (all on) give controlled authority, the same as the C1 constant.
        assertEquals(AimiAutonomyMode.ControlledAuthority, source.read(MapPreferences()).autonomyMode)
    }

    /**
     * The ML correction limit with Autodrive off or not authoritative is lower again, as before C1.
     * The display levels stay neutral (protection 2), so the 0.88 factor of the old default
     * protection level 1 does not come back: 0.55 and 0.72, not 0.484 and 0.634. The owner decided
     * to keep these numbers (PLAN.md, "Decided by the owner (T2 follow-up)").
     */
    @Test
    fun `the ML correction limit follows the Autodrive switches`() {
        val source = NeutralAimiBehaviorProfileSource()
        assertEquals(0.55f, source.read(autodrive(active = false)).mlCorrectionFractionMultiplier(), 1e-6f)
        assertEquals(0.72f, source.read(autodrive(active = true)).mlCorrectionFractionMultiplier(), 1e-6f)
        assertEquals(0.88f, source.read(autodrive(active = true, hyperTrajectoryRelease = true)).mlCorrectionFractionMultiplier(), 1e-6f)
        assertEquals(1.0f, source.read(autodrive(active = true, authoritative = true)).mlCorrectionFractionMultiplier(), 1e-6f)
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
