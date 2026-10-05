package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecideMealOnsetBehindVetoTest {

    @Test
    fun risingAccelerationIsAnOnsetUntilExertionSuppressesIt() {
        assertTrue(
            decideMealOnsetBehindEffortVeto(
                delta = 5f,
                predictedDelta = 5f,
                acceleration = 2f,
                predictedBg = 180f,
                targetBg = 100f,
                assessment = null,
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
        assertFalse(
            decideMealOnsetBehindEffortVeto(
                delta = 5f,
                predictedDelta = 5f,
                acceleration = 2f,
                predictedBg = 180f,
                targetBg = 100f,
                assessment = EffortActivityBelief.Assessment(
                    state = EffortActivityBelief.State.ACTIVE,
                    posture = EffortActivityBelief.Posture.EXERTION,
                    confidence = 0.30,
                    minutesSinceEffort = 0.0,
                    smbFactor = 1.0,
                    basalFactor = 1.0,
                    reasons = emptyList(),
                ),
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
        assertFalse(
            decideMealOnsetBehindEffortVeto(
                delta = 5f,
                predictedDelta = 5f,
                acceleration = 0f,
                predictedBg = 180f,
                targetBg = 100f,
                assessment = null,
                declaredMeal = false,
                cobG = 0.0,
            ),
        )
    }
}
