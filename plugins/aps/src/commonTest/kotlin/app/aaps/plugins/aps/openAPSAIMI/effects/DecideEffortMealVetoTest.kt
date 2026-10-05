package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sport scene from `basalDecisionEngineRaisesSportTemp`: BG 180, delta +5, acceleration 0.
 * No assessment, so the veto is false and onset is false. The temp basal stays 1.30 U/h
 * from the sport rate, not from a forced meal.
 */
class DecideEffortMealVetoTest {

    @Test
    fun sportSceneDoesNotVetoAndDoesNotStartAMeal() {
        val veto = decideEffortSuppressesUndeclaredMeal(
            assessment = null,
            declaredMeal = false,
            cobG = 0.0,
        )
        assertFalse(veto)
        assertFalse(
            decideDetectMealOnset(
                delta = 5.0f,
                predictedDelta = 5.0f,
                acceleration = 0.0f,
                predictedBg = 180.0f,
                targetBg = 100.0f,
                effortSuppressesUndeclaredMeal = veto,
            ),
        )
    }

    @Test
    fun exertionActiveAtTheConfidenceFloorSuppressesAnUndeclaredRise() {
        assertTrue(
            decideEffortSuppressesUndeclaredMeal(
                assessment = assessment(
                    state = EffortActivityBelief.State.ACTIVE,
                    posture = EffortActivityBelief.Posture.EXERTION,
                    confidence = EFFORT_MEAL_SUPPRESS_CONF,
                ),
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
    }

    @Test
    fun recentEffortSuppressesAndADeclaredMealOrTwelveGramsDoesNot() {
        val recent = assessment(
            state = EffortActivityBelief.State.RECENT_EFFORT,
            posture = EffortActivityBelief.Posture.EXERTION,
            confidence = 0.90,
        )
        assertTrue(decideEffortSuppressesUndeclaredMeal(recent, declaredMeal = false, cobG = 11.9))
        assertFalse(decideEffortSuppressesUndeclaredMeal(recent, declaredMeal = true, cobG = 0.0))
        assertFalse(decideEffortSuppressesUndeclaredMeal(recent, declaredMeal = false, cobG = EFFORT_MEAL_SUPPRESS_MAX_COB_G))
        assertFalse(
            decideEffortSuppressesUndeclaredMeal(
                assessment(
                    state = EffortActivityBelief.State.ACTIVE,
                    posture = EffortActivityBelief.Posture.STRESS,
                    confidence = 0.90,
                ),
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
        assertFalse(
            decideEffortSuppressesUndeclaredMeal(
                assessment(
                    state = EffortActivityBelief.State.ACTIVE,
                    posture = EffortActivityBelief.Posture.EXERTION,
                    confidence = 0.29,
                ),
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
    }

    private fun assessment(
        state: EffortActivityBelief.State,
        posture: EffortActivityBelief.Posture,
        confidence: Double,
    ) = EffortActivityBelief.Assessment(
        state = state,
        posture = posture,
        confidence = confidence,
        minutesSinceEffort = 0.0,
        smbFactor = 1.0,
        basalFactor = 1.0,
        reasons = emptyList(),
    )
}
