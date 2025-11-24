package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbDampingTest {

    private val policy = TailAwareSmbPolicy(
        tailIobHigh = 0.25,
        smbDampingAtTail = 0.5,
        postExerciseDamping = 0.6,
        lateFattyMealDamping = 0.7
    )
    private val damping = SmbDamping(policy)

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
    fun `test damp bypass`() {
        val result = damping.damp(
            smbU = 1.0,
            iobTailFrac = 0.5,
            exercise = true,
            suspectedLateFatMeal = true,
            bypassDamping = true,
            activity = createActivity()
        )
        assertEquals(1.0, result, 0.0)
    }

    @Test
    fun `test damp tail applied`() {
        // Tail fraction 0.5 > 0.25 (threshold)
        // Activity stage RISING, relative 0.5
        // Relief = 0.5 * 0.5 + ... ~ 0.25
        // Damping base 0.5. Multiplier = 0.5 + (1-0.5)*relief
        // If relief is small, multiplier is close to 0.5.
        
        val result = damping.damp(
            smbU = 1.0,
            iobTailFrac = 0.5,
            exercise = false,
            suspectedLateFatMeal = false,
            bypassDamping = false,
            activity = createActivity(relativeActivity = 0.0) // Zero relief
        )
        // Relief should be 0 (freshness 0 if postWindow 1.0? No, postWindow 0.0 -> freshness 1.0)
        // Freshness = 1.0. Blended = 0.3 * 1.0 = 0.3.
        // Multiplier = 0.5 + 0.5 * 0.3 = 0.65.
        assertEquals(0.65, result, 0.01)
    }

    @Test
    fun `test damp exercise`() {
        val result = damping.damp(
            smbU = 1.0,
            iobTailFrac = 0.0,
            exercise = true,
            suspectedLateFatMeal = false,
            bypassDamping = false,
            activity = createActivity()
        )
        assertEquals(0.6, result, 0.01)
    }
}
