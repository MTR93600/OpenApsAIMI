package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the late-fat damping floor from `origin/dev_OAPSAIMI` @ `81e370bfbf` (unchanged on tip
 * `c5db5a0333`). The preference range of `OApsAIMISmbLateFatDamping` starts at 0.0. Taken as is,
 * that would zero every SMB for up to four hours, so the multiplier is floored at 0.5.
 *
 * Study source set: [SmbDamping] is commonMain, so the test lives in `commonTest` (`kotlin.test`).
 */
class SmbDampingTest {

    private fun createActivity(
        stage: InsulinActivityStage = InsulinActivityStage.RISING,
        relativeActivity: Double = 0.5,
        postWindowFraction: Double = 0.0,
        anticipationWeight: Double = 0.0
    ) = InsulinActivityState(
        window = InsulinActivityWindow(0.0, 0.0, 0.0, 0.0),
        relativeActivity = relativeActivity,
        normalizedPosition = 0.0,
        postWindowFraction = postWindowFraction,
        anticipationWeight = anticipationWeight,
        minutesUntilOnset = 0.0,
        stage = stage
    )

    @Test
    fun `late fat damping never zeroes the smb even at the lowest preference`() {
        val zeroPolicy = SmbDamping(
            TailAwareSmbPolicy(
                tailIobHigh = 0.25,
                smbDampingAtTail = 0.5,
                postExerciseDamping = 0.6,
                lateFattyMealDamping = 0.0
            )
        )
        val audit = zeroPolicy.dampWithAudit(
            1.0, 0.0, exercise = false, suspectedLateFatMeal = true,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 0.0
        )
        assertTrue(audit.lateFatMult >= 0.5, "lateFatMult was ${audit.lateFatMult}")
        assertTrue(audit.out >= 0.5, "out was ${audit.out}")
    }

    private fun exercisePolicy(postExerciseDamping: Double) = SmbDamping(
        TailAwareSmbPolicy(
            tailIobHigh = 0.25,
            smbDampingAtTail = 0.5,
            postExerciseDamping = postExerciseDamping,
            lateFattyMealDamping = 0.7
        )
    )

    /**
     * Step P3: the preference range of `OApsAIMISmbExerciseDamping` starts at 0.0 too. Taken as is,
     * it would stop every SMB while exercise is detected, so it gets the same floor as the late fat
     * damping: 0.5.
     */
    @Test
    fun `exercise damping never zeroes the smb even at the lowest preference`() {
        val audit = exercisePolicy(0.0).dampWithAudit(
            1.0, 0.0, exercise = true, suspectedLateFatMeal = false,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 0.0
        )
        assertEquals(0.5, audit.exerciseMult, 1e-9)
        assertEquals(0.5, audit.out, 1e-9)
    }

    @Test
    fun `exercise damping below the floor acts as the floor`() {
        val audit = exercisePolicy(0.3).dampWithAudit(
            2.0, 0.0, exercise = true, suspectedLateFatMeal = false,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 0.0
        )
        assertEquals(0.5, audit.exerciseMult, 1e-9)
        assertEquals(1.0, audit.out, 1e-9)
    }

    @Test
    fun `exercise damping at or above the floor is not changed`() {
        listOf(0.5, 0.6, 0.85, 1.0).forEach { value ->
            val audit = exercisePolicy(value).dampWithAudit(
                1.0, 0.0, exercise = true, suspectedLateFatMeal = false,
                bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 0.0
            )
            assertEquals(value, audit.exerciseMult, 1e-9, "stored $value")
            assertEquals(value, audit.out, 1e-9, "stored $value")
        }
    }

    @Test
    fun `no exercise means no exercise damping`() {
        val audit = exercisePolicy(0.0).dampWithAudit(
            1.0, 0.0, exercise = false, suspectedLateFatMeal = false,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 0.0
        )
        assertEquals(1.0, audit.exerciseMult, 1e-9)
        assertEquals(1.0, audit.out, 1e-9)
    }
}
