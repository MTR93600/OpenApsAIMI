package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.test.Test
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
}
