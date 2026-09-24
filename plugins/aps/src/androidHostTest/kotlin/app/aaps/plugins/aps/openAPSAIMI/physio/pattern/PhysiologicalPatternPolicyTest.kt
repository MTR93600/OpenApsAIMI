package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PhysiologicalPatternPolicyTest {

    @Test
    fun meal_active_publishes_soft_proposal_while_protective_hard_still_binds() {
        val snapshot = PhysiologicalPatternPolicy.aggregate(
            listOf(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                    confidence = 0.88,
                    reason = "mealLike",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.85,
                    reason = "steps+hr",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    confidence = 0.80,
                    reason = "FIRST_WAVE",
                ),
            ),
        )

        assertThat(snapshot.smbCapU!!).isWithin(0.01).of(1.20)
        assertThat(snapshot.smbCapKind).isEqualTo(PatternCapKind.SOFT)
        assertThat(snapshot.mealPatternCap?.kind).isEqualTo(PatternCapKind.SOFT)
        // Soft meal is a proposal for Harmonia; co-active HARD protectors still bind via min().
        assertThat(snapshot.softProposedCapU()!!).isWithin(0.01).of(1.20)
        assertThat(snapshot.hardBindingCapU()!!).isWithin(0.01).of(0.40)
        assertThat(snapshot.suppressHyperRelease).isFalse()
        assertThat(snapshot.suppressMealInterpretation).isFalse()
    }

    @Test
    fun meal_soft_alone_has_no_hard_binding_cap() {
        val snapshot = PhysiologicalPatternPolicy.aggregate(
            listOf(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    confidence = 0.80,
                    reason = "FIRST_WAVE",
                ),
            ),
        )
        assertThat(snapshot.softProposedCapU()!!).isWithin(0.01).of(1.20)
        assertThat(snapshot.hardBindingCapU()).isNull()
    }

    @Test
    fun non_meal_context_keeps_restrictive_hard_min_cap() {
        val snapshot = PhysiologicalPatternPolicy.aggregate(
            listOf(
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.EXERCISE_ACUTE,
                    confidence = 0.85,
                    reason = "steps+hr",
                ),
                PhysiologicalPatternReading(
                    id = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                    confidence = 0.88,
                    reason = "surveillance",
                ),
            ),
        )

        assertThat(snapshot.smbCapU!!).isWithin(0.01).of(0.40)
        assertThat(snapshot.smbCapKind).isEqualTo(PatternCapKind.HARD)
        assertThat(snapshot.hardBindingCapU()!!).isWithin(0.01).of(0.40)
        assertThat(snapshot.softProposedCapU()).isNull()
        assertThat(snapshot.suppressHyperRelease).isTrue()
    }

    @Test
    fun catalog_marks_first_wave_and_undeclared_as_soft() {
        assertThat(PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.MEAL_FIRST_WAVE).capKind)
            .isEqualTo(PatternCapKind.SOFT)
        assertThat(PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.MEAL_UNDECLARED_FAST).capKind)
            .isEqualTo(PatternCapKind.SOFT)
        assertThat(PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.EXERCISE_ACUTE).capKind)
            .isEqualTo(PatternCapKind.HARD)
        assertThat(PhysiologicalPatternCatalog.definitionOf(PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE).capKind)
            .isEqualTo(PatternCapKind.HARD)
    }
}
