package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HarmoniaSmbArbiterTest {

    @Test
    fun lifts_within_maxsmbhb_when_rise_soft_meal_and_mpc_above_proposal() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE)
        assertThat(decision.smbU).isWithin(1e-9).of(2.20)
        assertThat(decision.addsSmbAuthority).isTrue()
        assertThat(decision.reasons).contains("LIFT_WITHIN_ENVELOPE")
    }

    @Test
    fun hard_protective_catalog_does_not_lift() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 0.40,
            mpcDemandU = 2.20,
            catalogProposedCapU = 0.40,
            catalogCapKind = PatternCapKind.HARD,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.ACCEPT)
        assertThat(decision.smbU).isWithin(1e-9).of(0.40)
        assertThat(decision.addsSmbAuthority).isFalse()
    }

    @Test
    fun protective_block_reduces() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.80,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.PROTECTIVE,
            harmoniaAction = HarmoniaAction.PROTECTIVE_REDUCTION.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = true,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.REDUCE)
        assertThat(decision.smbU).isAtMost(1.20)
        assertThat(decision.addsSmbAuthority).isTrue()
    }

    @Test
    fun lift_reaches_the_full_high_bg_ceiling_not_a_fraction_of_it() {
        // The patient's ceiling is 2.20 U and the meal catalogue proposes 0.75 of it (1.65 U).
        // A LIFT may reach the full ceiling.
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.65,
            mpcDemandU = 2.60,
            catalogProposedCapU = 1.65,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE)
        assertThat(decision.smbU).isWithin(1e-9).of(2.20)
    }

    @Test
    fun lift_never_passes_what_the_barrier_permits() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
            barrierPermittedU = 1.68,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE)
        assertThat(decision.smbU).isWithin(1e-9).of(1.68)
        assertThat(decision.reasons).contains("LIFT_BARRIER_BOUND_1.68")
    }

    @Test
    fun barrier_bounds_the_lift_but_never_lowers_the_existing_demand() {
        // The barrier says 0.00 on ordinary rises (measured: 46–58 % of ticks). A lift must not turn
        // into a reduction of what the ungoverned path was already going to deliver.
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
            barrierPermittedU = 0.0,
        )

        assertThat(decision.smbU).isWithin(1e-9).of(1.20)
    }

    @Test
    fun no_barrier_value_leaves_the_lift_at_the_envelope() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.20,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.NEED_MORE_INSULIN,
            harmoniaAction = HarmoniaAction.MEAL_SUPPORT.name,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
            barrierPermittedU = null,
        )

        assertThat(decision.smbU).isWithin(1e-9).of(2.20)
    }

    @Test
    fun meal_support_intent_also_lifts() {
        val decision = HarmoniaSmbArbiter.decide(
            demandBeforeU = 1.20,
            mpcDemandU = 2.00,
            catalogProposedCapU = 1.20,
            catalogCapKind = PatternCapKind.SOFT,
            envelopeMaxU = 2.20,
            insulinIntent = InsulinIntent.MEAL_SUPPORT,
            harmoniaAction = null,
            riseConfirmed = true,
            mealCertaintySupports = true,
            protectiveBlock = false,
        )

        assertThat(decision.mode).isEqualTo(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE)
        assertThat(decision.smbU).isWithin(1e-9).of(2.00)
    }
}
