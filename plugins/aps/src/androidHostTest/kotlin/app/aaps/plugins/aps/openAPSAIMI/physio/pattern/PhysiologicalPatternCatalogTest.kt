package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalPatternCatalogTest {

    @Test
    fun every_pattern_id_has_catalog_definition() {
        for (id in PhysiologicalPatternId.entries) {
            val def = PhysiologicalPatternCatalog.definitionOf(id)
            assertThat(def.id).isEqualTo(id)
            assertThat(def.category).isEqualTo(id.category)
            assertThat(def.dominantScaleMinutes).isAtLeast(15)
        }
        assertThat(PhysiologicalPatternId.entries.size).isEqualTo(28)
    }

    @Test
    fun all_meal_patterns_define_an_smb_cap() {
        // Cap coverage must be intrinsic to meal classification: when a meal pattern displaces a
        // capped pattern mid-rise, patternCapU must not silently become null (HTR min-cap skip).
        val mealPatterns = listOf(
            PhysiologicalPatternId.MEAL_DECLARED,
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
        )
        for (id in mealPatterns) {
            val def = PhysiologicalPatternCatalog.definitionOf(id)
            assertThat(def.smbCapFraction).isNotNull()
            // Generous (meal response must not be strangled) but never above the user's own ceiling.
            assertThat(def.smbCapFraction!!).isAtLeast(0.60)
            assertThat(def.smbCapFraction!!).isAtMost(1.0)
        }
        // Declared meals are the most trusted context — their cap must be the loosest of the four.
        val declared = PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.MEAL_DECLARED).smbCapFraction!!
        for (id in mealPatterns - PhysiologicalPatternId.MEAL_DECLARED) {
            assertThat(PhysiologicalPatternCatalog.definitionOf(id).smbCapFraction!!).isAtMost(declared)
        }
    }

    @Test
    fun poor_sleep_morning_rise_suppresses_hyper_and_meal() {
        val def = PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE)
        assertThat(def.suppressMealInterpretation).isTrue()
        assertThat(def.suppressHyperRelease).isTrue()
        assertThat(def.suppressWaveletBoost).isTrue()
        // 0.31 x 1.6 = 0.496, i.e. the previous 0.50 U at the reference ceiling.
        assertThat(def.capU(LEGACY_REFERENCE_MAX_SMB_HB_U)).isWithin(0.01).of(0.50)
    }
}
