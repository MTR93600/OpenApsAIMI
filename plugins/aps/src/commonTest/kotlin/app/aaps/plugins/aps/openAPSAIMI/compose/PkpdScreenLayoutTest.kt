package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.PreferenceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that each PK/PD tab draws exactly the keys declared for it in `AimiSettingsScreens`.
 *
 * Two sets of keys were lost on the way to Kotlin Multiplatform: the curve learning gate in the
 * advanced tab, and the whole expert tab. Both were still declared, so the manifest test passed
 * while the screen did not show them. The tabs now draw from [PkpdScreenLayout], and this test
 * ties that layout to the declared sections.
 */
class PkpdScreenLayoutTest {

    private val pkpdContents = listOf(
        AimiSettingsContentId.PkpdSimple,
        AimiSettingsContentId.PkpdAdvanced,
        AimiSettingsContentId.PkpdExpert,
    )

    private fun drawnKeysOf(content: AimiSettingsContentId): Set<String> =
        AimiSettingsScreens.keysOf(*PkpdScreenLayout.drawnSectionsOf(content).toTypedArray())
            .map { it.key }
            .toSet()

    @Test
    fun `each PK PD content draws every field section declared for it and nothing else`() {
        pkpdContents.forEach { content ->
            val declared = AimiSettingsScreens.sectionsOf(content)
                .filter { it.kind == AimiSettingsSectionKind.FIELDS }
                .map { it.id }
                .toSet()
            val drawn = PkpdScreenLayout.drawnSectionsOf(content)
            assertEquals(declared, drawn.toSet(), "sections of $content")
            assertEquals(drawn.size, drawn.toSet().size, "a section of $content is drawn twice")
        }
    }

    @Test
    fun `every PK PD level routes to a content that has a layout`() {
        AimiSettingsScreens.pkpdLevels.forEach { level ->
            val content = AimiSettingsScreens.pkpdContentByLevel[level]
            assertTrue(content in pkpdContents, "level $level routes to $content")
            assertTrue(PkpdScreenLayout.drawnSectionsOf(content!!).isNotEmpty(), "level $level draws nothing")
        }
    }

    @Test
    fun `every key of a field section has an item that can draw it`() {
        val fieldSections = PkpdScreenLayout.simpleFieldSections +
            PkpdScreenLayout.advancedFieldSections +
            PkpdScreenLayout.expertGroups.flatMap { it.sections }
        fieldSections.forEach { sectionId ->
            val keys = AimiSettingsScreens.keysOf(sectionId)
            keys.forEach { key ->
                assertTrue(key is PreferenceKey && PkpdScreenLayout.canDrawAsField(key), "${key.key} in $sectionId")
            }
        }
    }

    @Test
    fun `every key of a slider section is a double key`() {
        PkpdScreenLayout.advancedSliderSections.forEach { sectionId ->
            AimiSettingsScreens.keysOf(sectionId).forEach { key ->
                assertTrue(key is DoubleKey, "${key.key} in $sectionId is not a slider")
            }
        }
    }

    @Test
    fun `the advanced tab draws the curve learning gate`() {
        assertTrue(BooleanKey.OApsAIMIPkpdCurveLearningGate.key in drawnKeysOf(AimiSettingsContentId.PkpdAdvanced))
    }

    @Test
    fun `the expert tab draws the expert keys of dev`() {
        val expected = setOf(
            BooleanKey.OApsAIMIPeakGovernorEnabled.key,
            DoubleKey.OApsAIMIPeakGovernorLearnedWeight.key,
            DoubleKey.OApsAIMIIsfFusionMinFactor.key,
            DoubleKey.OApsAIMIIsfFusionMaxFactor.key,
            DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.key,
            BooleanKey.OApsAIMIDynIsfTrajectoryTuningEnabled.key,
            BooleanKey.OApsAIMIDynIsfTrajectoryShadowOnly.key,
            DoubleKey.OApsAIMIDynIsfTrajectoryMaxFraction.key,
            DoubleKey.OApsAIMISmbTailThreshold.key,
            DoubleKey.OApsAIMISmbTailDamping.key,
            DoubleKey.OApsAIMISmbExerciseDamping.key,
            DoubleKey.OApsAIMISmbLateFatDamping.key,
            BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled.key,
            DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor.key,
            DoubleKey.OApsAIMIRedCarpetRestoreThreshold.key,
            BooleanKey.OApsAIMIIobSurveillanceGuard.key,
            DoubleKey.OApsAIMIPriorityMaxIobFactor.key,
            DoubleKey.OApsAIMIPriorityMaxIobExtraU.key,
        )
        assertEquals(expected, drawnKeysOf(AimiSettingsContentId.PkpdExpert))
    }

    @Test
    fun `every title or summary override belongs to a drawn key`() {
        val drawn = pkpdContents.flatMap { drawnKeysOf(it) }.toSet()
        val stale = (PkpdScreenLayout.titleOverrides.keys + PkpdScreenLayout.summaryOverrides.keys) - drawn
        assertTrue(stale.isEmpty(), "overrides for keys no PK/PD tab draws: $stale")
    }
}
