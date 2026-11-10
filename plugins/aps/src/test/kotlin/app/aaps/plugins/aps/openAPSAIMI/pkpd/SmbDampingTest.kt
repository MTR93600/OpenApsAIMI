package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // B2 — at meal onset (elapsed 0) the late-fat multiplier must equal the *preference* floor (0.7),
    // NOT the old hardcoded 0.85 that ignored the user setting.
    @Test
    fun `late fat uses preference floor at meal onset`() {
        val audit = damping.dampWithAudit(
            smbU = 1.0,
            iobTailFrac = 0.0,
            exercise = false,
            suspectedLateFatMeal = true,
            bypassDamping = false,
            activity = createActivity(),
            elapsedSinceMealMin = 0.0
        )
        assertEquals(0.70, audit.lateFatMult, 1e-9)
        assertEquals(0.70, audit.out, 1e-9)
    }

    // B1+F5 — the time ladder must actually depend on elapsed. Past 4h the rise has faded, so the
    // multiplier ramps toward neutral (0.95) instead of being pinned at a constant.
    @Test
    fun `late fat ramps toward neutral after four hours`() {
        val mid = damping.dampWithAudit(
            1.0, 0.0, false, suspectedLateFatMeal = true,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 120.0
        )
        assertEquals(0.825, mid.lateFatMult, 1e-9) // 0.7 + (0.95-0.7)*0.5

        val late = damping.dampWithAudit(
            1.0, 0.0, false, suspectedLateFatMeal = true,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 300.0
        )
        assertEquals(0.95, late.lateFatMult, 1e-9) // clamped at 4h
    }

    // F6 — damp() and dampWithAudit() must agree (single implementation).
    @Test
    fun `damp matches dampWithAudit out`() {
        val direct = damping.damp(
            1.0, 0.3, exercise = true, suspectedLateFatMeal = true,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 90.0
        )
        val audited = damping.dampWithAudit(
            1.0, 0.3, exercise = true, suspectedLateFatMeal = true,
            bypassDamping = false, activity = createActivity(), elapsedSinceMealMin = 90.0
        )
        assertEquals(audited.out, direct, 1e-9)
    }

    // The preference range of `OApsAIMISmbLateFatDamping` starts at 0.0. Taken as is, that would
    // zero every SMB for up to four hours, so the multiplier is floored at 0.5.
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
