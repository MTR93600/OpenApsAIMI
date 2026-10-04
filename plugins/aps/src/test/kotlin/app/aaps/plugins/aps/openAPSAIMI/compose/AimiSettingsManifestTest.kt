package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The tests that keep the AIMI settings honest.
 *
 * They exist because of one real defect: `aimi_isf_fusion_max_change_per_tick` had a slider in
 * `PkpdExpertSettingsContent`, that function was called from nowhere, and nothing in the build
 * complained. Four rules now break the build instead:
 *
 * 1. completeness: every AIMI key is in the manifest, exactly once.
 * 2. reachability: every classified key is in a section of a content the navigation reaches.
 * 3. consistency: a key's level is the lowest level that offers it, and a field list holds only
 *    keys of its own level.
 * 4. the exemption lists never grow.
 *
 * A fifth rule sits beside these: exactly the keys holding a credential or a phone number are
 * marked [AimiSettingEntry.secret], so the future audit screen never prints one.
 */
class AimiSettingsManifestTest {

    /**
     * Size the [AimiSettingsManifest.UNCLASSIFIED] set may never pass.
     *
     * Lower it when keys leave the set. Never raise it.
     */
    private val unclassifiedCeiling = 0

    /**
     * The exact keys [AimiSettingsManifest.REACHABILITY_DEBT] may hold.
     *
     * A ceiling on the size alone lets one key be swapped for another without the test noticing:
     * a key could leave the list while an unrelated one quietly entered it, keeping the count the
     * same. Pinning the membership means any change to the list shows up as a diff here. Lower it
     * (remove the key below too) when a key is put on a screen. Never add a key that is not
     * already unreachable: a new key must be reachable on the day it is written.
     */
    private val expectedReachabilityDebt: Set<String> = setOf(
        DoubleKey.OApsAIMIActivityBasalCapFactor.key,
        BooleanKey.OApsAIMIAimiSmbComparatorEnabled.key,
        DoubleKey.OApsAIMIAutodriveAcceleration.key,
        BooleanKey.OApsAIMIAutodriveV3EnhancedGater.key,
        BooleanKey.OApsAIMIDiaGovernorEnabled.key,
        DoubleKey.OApsAIMIDiaGovernorLearnedWeight.key,
        BooleanKey.OApsAIMIEffectiveIobReleaseEnabled.key,
        BooleanKey.OApsAIMIEnableBasal.key,
        BooleanKey.OApsAIMIEnableStepsFromWatch.key,
        DoubleKey.OApsAIMIFCLFactor.key,
        IntKey.OApsAIMIHighBGinterval.key,
        BooleanKey.OApsAIMIHyperDroppingExemptEnabled.key,
        BooleanKey.OApsAIMIIntelligenceKineticsProfiler.key,
        BooleanKey.OApsAIMIIntelligenceSingleLearnPath.key,
        BooleanKey.OApsAIMIIntelligenceSnapshotExport.key,
        IntKey.OApsAIMIIntratickStallSeconds.key,
        BooleanKey.OApsAIMILoopBlackboxFileEnabled.key,
        BooleanKey.OApsAIMILoopExclusiveInvocationEnabled.key,
        BooleanKey.OApsAIMIMealHyperBypassEnabled.key,
        DoubleKey.OApsAIMINightGrowthBasalMultiplier.key,
        DoubleKey.OApsAIMINightGrowthMaxSmbClamp.key,
        DoubleKey.OApsAIMINightGrowthMinRiseSlope.key,
        DoubleKey.OApsAIMINightGrowthSmbMultiplier.key,
        BooleanKey.OApsAIMIPkpdEndogenousReversion.key,
        BooleanKey.OApsAIMIPkpdHyperReversion.key,
        BooleanKey.OApsAIMIPkpdPredictionKinetics.key,
        DoubleKey.OApsAIMIPlateauBandAbs.key,
        BooleanKey.OApsAIMIPredictionAuthorityEnabled.key,
        BooleanKey.OApsAIMIPredictionAuthorityShadow.key,
        DoubleKey.OApsAIMIR2Confident.key,
        BooleanKey.OApsAIMIT3cPhysioInformedEnabled.key,
        BooleanKey.OApsAIMIforcelimits.key,
        BooleanKey.AimiCosineGateEnabled.key,
        DoubleKey.AimiCosineGateAlpha.key,
        DoubleKey.AimiCosineGateMinDataQuality.key,
        DoubleKey.AimiCosineGateMinSensitivity.key,
        DoubleKey.AimiCosineGateMaxSensitivity.key,
        IntKey.AimiCosineGateMaxPeakShift.key,
        DoubleKey.AimiUamConfidence.key,
        StringKey.AimiPhysioLLMProvider.key,
        StringKey.AimiTuningContextSelection.key,
        // The context module's own settings. They came into scope when `isAimiScoped` was taught to
        // read its named list, and their screen is a standalone activity outside the settings tree
        // this manifest models. Listed here on purpose: the debt is pinned name by name, so nothing
        // joins it without being written down.
        StringKey.ContextMode.key,
        StringKey.ContextLLMProvider.key,
        StringKey.ContextLLMOpenAIKey.key,
        StringKey.ContextLLMGeminiKey.key,
        StringKey.ContextLLMDeepSeekKey.key,
        StringKey.ContextLLMClaudeKey.key,
    )

    // ---------------------------------------------------------------- 1. completeness

    @Test
    fun `every AIMI key is in the manifest exactly once`() {
        val scoped = AimiSettingsManifest.scopedKeys.map { it.key }
        val declared = AimiSettingsManifest.entries.map { it.key.key }

        assertThat(declared).containsNoDuplicates()

        val missing = scoped - declared.toSet() - AimiSettingsManifest.UNCLASSIFIED - AimiSettingsManifest.DEAD_KEYS
        assertThat(missing).isEmpty()

        val unknown = declared.toSet() - scoped.toSet()
        assertThat(unknown).isEmpty()
    }

    @Test
    fun `the manifest holds no key that is also exempt as unclassified`() {
        val declared = AimiSettingsManifest.entries.map { it.key.key }.toSet()
        assertThat(declared.intersect(AimiSettingsManifest.UNCLASSIFIED)).isEmpty()
    }

    @Test
    fun `the manifest holds no key that is also marked dead`() {
        val declared = AimiSettingsManifest.entries.map { it.key.key }.toSet()
        assertThat(declared.intersect(AimiSettingsManifest.DEAD_KEYS)).isEmpty()
    }

    // ---------------------------------------------------------------- 2. reachability

    @Test
    fun `every classified key is reachable from a routed screen`() {
        val unreachable = AimiSettingsManifest.entries
            // A key only USER, SLIDER or PRESET may write is the one kind of value a person can
            // own. A key only the loop writes, or only bookkeeping, is runtime state and needs no
            // screen. A key the loop rewrites AND the person owns — max SMB is the example — stays
            // in scope: losing its screen is exactly the failure this test exists to catch.
            .filter { it.userOwned }
            .map { it.key.key }
            .filterNot { it in AimiSettingsManifest.REACHABILITY_DEBT }
            .filterNot { it in reachableKeyStrings() }

        assertThat(unreachable).isEmpty()
    }

    @Test
    fun `no SIMPLE key sits in the reachability debt`() {
        // A SIMPLE key is the person's own therapy value or a hard safety limit. If it has no
        // screen, it is not "debt to pay down later" — it is the exact defect this manifest exists
        // to catch, so it must be fixed in the same change that classifies it SIMPLE.
        val simpleInDebt = AimiSettingsManifest.REACHABILITY_DEBT
            .mapNotNull { AimiSettingsManifest.entryForKeyString(it) }
            .filter { it.level == AimiSettingsLevel.SIMPLE }
            .map { it.key.key }

        assertThat(simpleInDebt).isEmpty()
    }

    @Test
    fun `isf fusion keys are reachable - regression for the unrouted expert content`() {
        // The defect: PkpdExpertSettingsContent was never called, so these three keys were
        // stored but could not be corrected. If EXPERT stops being routed, this test fails.
        val isfFusionKeys = listOf(
            DoubleKey.OApsAIMIIsfFusionMinFactor.key,
            DoubleKey.OApsAIMIIsfFusionMaxFactor.key,
            DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key,
        )
        assertThat(reachableKeyStrings()).containsAtLeastElementsIn(isfFusionKeys)
    }

    @Test
    fun `the expert level of the guided PK PD screen is routed`() {
        assertThat(AimiSettingsScreens.pkpdLevels).contains(PkpdSettingsLevel.EXPERT)
        assertThat(AimiSettingsScreens.routedContents).contains(AimiSettingsContentId.PkpdExpert)
    }

    @Test
    fun `each PK PD level draws its own content, not another level's`() {
        // This is the defect the whole manifest exists for: the screen used to send EXPERT to the
        // advanced content, and nothing could see it. `AimiPkpdSettingsScreen` now reads this map to
        // choose what to draw, so sending a level to the wrong content fails here AND changes the
        // screen. A test that only checked "EXPERT appears somewhere" passed while the bug was live.
        assertThat(AimiSettingsScreens.pkpdContentByLevel)
            .containsExactly(
                PkpdSettingsLevel.SIMPLE, AimiSettingsContentId.PkpdSimple,
                PkpdSettingsLevel.ADVANCED, AimiSettingsContentId.PkpdAdvanced,
                PkpdSettingsLevel.EXPERT, AimiSettingsContentId.PkpdExpert,
            )
    }

    @Test
    fun `every PK PD level the tab row offers has a content to draw`() {
        // The tab row is built from `pkpdLevels`. A level offered with no content would draw an empty
        // screen, which is how an orphaned content hides.
        val missing = AimiSettingsScreens.pkpdLevels
            .filterNot { AimiSettingsScreens.pkpdContentByLevel.containsKey(it) }
        assertThat(missing).isEmpty()
    }

    @Test
    fun `every section names a content the navigation reaches`() {
        val orphans = AimiSettingsScreens.sections
            .filterNot { it.content in AimiSettingsScreens.routedContents }
            .map { it.id }

        assertThat(orphans).isEmpty()
    }

    // ---------------------------------------------------------------- 3. consistency

    @Test
    fun `a key level is the lowest level that offers it`() {
        val wrong = mutableListOf<String>()
        sectionLevelsByKeyString().forEach { (keyString, levels) ->
            val entry = AimiSettingsManifest.entryForKeyString(keyString) ?: return@forEach
            val lowest = levels.min()
            if (lowest != entry.level) {
                wrong += "$keyString is ${entry.level} but its lowest section is $lowest"
            }
        }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `a field list holds only keys of its own level`() {
        val wrong = mutableListOf<String>()
        AimiSettingsScreens.sections
            .filter { it.kind == AimiSettingsSectionKind.FIELDS }
            .forEach { section ->
                section.keys.forEach { key ->
                    val level = AimiSettingsManifest.levelOf(key)
                    if (level != section.level) {
                        wrong += "${section.id} is ${section.level} but ${key.key} is $level"
                    }
                }
            }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `a field list holds only keys a preference screen can show`() {
        val wrong = AimiSettingsScreens.sections
            .filter { it.kind == AimiSettingsSectionKind.FIELDS }
            .flatMap { section -> section.keys.map { section.id to it } }
            .filterNot { (_, key) -> key is PreferenceKey }
            .map { (sectionId, key) -> "$sectionId holds ${key.key}" }

        assertThat(wrong).isEmpty()
    }

    @Test
    fun `every key of a section is in the manifest`() {
        val unknown = AimiSettingsScreens.sections
            .flatMap { it.keys }
            .filter { AimiSettingsManifest.entryFor(it) == null }
            .map { it.key }

        assertThat(unknown).isEmpty()
    }

    @Test
    fun `section ids are unique`() {
        val ids = AimiSettingsScreens.sections.map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

    // ---------------------------------------------------------------- 4. the lists only shrink

    @Test
    fun `the exemption lists never grow`() {
        assertThat(AimiSettingsManifest.UNCLASSIFIED.size).isAtMost(unclassifiedCeiling)
        assertThat(AimiSettingsManifest.REACHABILITY_DEBT).isEqualTo(expectedReachabilityDebt)
    }

    @Test
    fun `no loop written key sits in the reachability debt`() {
        // A key NOTHING but the loop writes is runtime state. It is exempt by its writer, not by the
        // list, so putting it in the list would hide the list's real size.
        val loopKeys = AimiSettingsManifest.entries
            .filter { it.writers == setOf(AimiSettingsWriter.LOOP) }
            .map { it.key.key }
            .filter { it in AimiSettingsManifest.REACHABILITY_DEBT }

        assertThat(loopKeys).isEmpty()
    }

    @Test
    fun `every key in the reachability debt is really unreachable`() {
        val reachable = reachableKeyStrings()
        val stale = AimiSettingsManifest.REACHABILITY_DEBT.filter { it in reachable }

        assertThat(stale).isEmpty()
    }

    // ---------------------------------------------------------------- secret keys

    @Test
    fun `every password key in scope is marked secret`() {
        // Derived, not copied. `isPassword` and `isPin` are the platform's own statement that a value
        // must never be shown, and the manifest must agree with them rather than keep a second list
        // that can drift. The first version of this test read only `StringKey` and only `isPassword`,
        // so it passed while the remote-control PIN (an `AimiStringKey`, `isPin = true`) and the Oura
        // token were rendered in clear on the audit screen. It now reads the INTERFACE, which covers
        // every string key enum, present and future.
        val passwordKeys = AimiSettingsManifest.scopedKeys
            .filterIsInstance<StringPreferenceKey>()
            .filter { it.isPassword || it.isPin }
            .map { it.key }
            .toSet()
        val notMarked = passwordKeys.filterNot { AimiSettingsManifest.entryForKeyString(it)?.secret == true }

        assertThat(notMarked).isEmpty()
    }

    @Test
    fun `only the keys holding a credential or a phone number are marked secret`() {
        val expectedSecretKeys = setOf(
            StringKey.AimiAdvisorOpenAIKey.key,
            StringKey.AimiAdvisorGeminiKey.key,
            StringKey.AimiAdvisorDeepSeekKey.key,
            StringKey.AimiAdvisorClaudeKey.key,
            StringKey.AimiEmergencySosPhone.key,
            StringKey.AimiEmergencySosPhone2.key,
            // The context module keeps its own provider credentials, separate from the advisor's.
            StringKey.ContextLLMOpenAIKey.key,
            StringKey.ContextLLMGeminiKey.key,
            StringKey.ContextLLMDeepSeekKey.key,
            StringKey.ContextLLMClaudeKey.key,
            // Declared by the keys themselves (`isPin` / `isPassword`), not by the manifest.
            AimiStringKey.RemoteControlPin.key,
            AimiStringKey.OuraPersonalAccessToken.key,
        )
        val actualSecretKeys = AimiSettingsManifest.entries
            .filter { it.secret }
            .map { it.key.key }
            .toSet()

        assertThat(actualSecretKeys).isEqualTo(expectedSecretKeys)
    }

    // ---------------------------------------------------------------- helpers

    private fun reachableKeyStrings(): Set<String> =
        AimiSettingsScreens.sections
            .filter { it.content in AimiSettingsScreens.routedContents }
            .flatMap { section -> section.keys.map { it.key } }
            .toSet()

    private fun sectionLevelsByKeyString(): Map<String, List<AimiSettingsLevel>> {
        val levels = mutableMapOf<String, MutableList<AimiSettingsLevel>>()
        AimiSettingsScreens.sections.forEach { section ->
            section.keys.forEach { key ->
                levels.getOrPut(key.key) { mutableListOf() } += section.level
            }
        }
        return levels
    }
}
