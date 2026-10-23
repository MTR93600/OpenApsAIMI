package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of `HarmoniaSmbArbiterTest` from `dev_OAPSAIMI` (`plugins/aps/src/test`).
 *
 * [HarmoniaSmbArbiter] is in commonMain and has no platform calls, so the test runs on every target.
 * Adapted: Truth and JUnit replaced by `kotlin.test`, names in camelCase. The cases and the numbers
 * are the same as on dev. The KMP arbiter differs from dev only in JSON (kotlinx instead of org.json)
 * and in number formatting (`aimiFmt2` instead of `String.format`), which the reasons still match.
 */
class HarmoniaSmbArbiterTest {

    @Test
    fun liftsWithinMaxsmbhbWhenRiseSoftMealAndMpcAboveProposal() {
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

        assertEquals(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE, decision.mode)
        assertEquals(2.20, decision.smbU, 1e-9)
        assertTrue(decision.addsSmbAuthority)
        assertTrue((decision.reasons).contains("LIFT_WITHIN_ENVELOPE"))
    }

    @Test
    fun hardProtectiveCatalogDoesNotLift() {
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

        assertEquals(HarmoniaSmbAuthorityMode.ACCEPT, decision.mode)
        assertEquals(0.40, decision.smbU, 1e-9)
        assertFalse(decision.addsSmbAuthority)
    }

    @Test
    fun protectiveBlockReduces() {
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

        assertEquals(HarmoniaSmbAuthorityMode.REDUCE, decision.mode)
        assertTrue((decision.smbU) <= 1.20)
        assertTrue(decision.addsSmbAuthority)
    }

    @Test
    fun liftReachesTheFullHighBgCeilingNotAFractionOfIt() {
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

        assertEquals(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE, decision.mode)
        assertEquals(2.20, decision.smbU, 1e-9)
    }

    @Test
    fun liftNeverPassesWhatTheBarrierPermits() {
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

        assertEquals(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE, decision.mode)
        assertEquals(1.68, decision.smbU, 1e-9)
        assertTrue((decision.reasons).contains("LIFT_BARRIER_BOUND_1.68"))
    }

    @Test
    fun barrierBoundsTheLiftButNeverLowersTheExistingDemand() {
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

        assertEquals(1.20, decision.smbU, 1e-9)
    }

    @Test
    fun noBarrierValueLeavesTheLiftAtTheEnvelope() {
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

        assertEquals(2.20, decision.smbU, 1e-9)
    }

    @Test
    fun mealSupportIntentAlsoLifts() {
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

        assertEquals(HarmoniaSmbAuthorityMode.LIFT_WITHIN_ENVELOPE, decision.mode)
        assertEquals(2.00, decision.smbU, 1e-9)
    }
}
