// SmbDamping.kt
package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class TailAwareSmbPolicy(
    val tailIobHigh: Double = 0.25,
    val smbDampingAtTail: Double = 0.5,
    val postExerciseDamping: Double = 0.6,
    val lateFattyMealDamping: Double = 0.7
)

data class SmbDampingAudit(
    val out: Double,
    val tailApplied: Boolean,
    val tailMult: Double,
    val activityRelief: Double,
    val activityStage: InsulinActivityStage,
    val exerciseApplied: Boolean,
    val exerciseMult: Double,
    val lateFatApplied: Boolean,
    val lateFatMult: Double,
    val mealBypass: Boolean
)

class SmbDamping(
    private val policy: TailAwareSmbPolicy = TailAwareSmbPolicy()
) {

    // F6 — single implementation. `damp()` is a thin wrapper over `dampWithAudit()` so both entry points
    // can never diverge again (they previously used two different late-fat rules; see git history).
    fun damp(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        activity: InsulinActivityState,
        elapsedSinceMealMin: Double = 0.0
    ): Double = dampWithAudit(
        smbU = smbU,
        iobTailFrac = iobTailFrac,
        exercise = exercise,
        suspectedLateFatMeal = suspectedLateFatMeal,
        bypassDamping = bypassDamping,
        activity = activity,
        elapsedSinceMealMin = elapsedSinceMealMin
    ).out

    fun dampWithAudit(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        activity: InsulinActivityState,
        elapsedSinceMealMin: Double = 0.0
    ): SmbDampingAudit {
        if (bypassDamping) {
            return SmbDampingAudit(
                out = smbU,
                tailApplied = false, tailMult = 1.0,
                activityRelief = 0.0,
                activityStage = activity.stage,
                exerciseApplied = false, exerciseMult = 1.0,
                lateFatApplied = false, lateFatMult = 1.0,
                mealBypass = true // (optionnel: renommer en pkpdBypass si tu veux)
            )
        }
        var out = smbU
        val tailApplied = iobTailFrac > policy.tailIobHigh
        val tailRelief = computeActivityRelief(activity)
        val tailMult = if (tailApplied) computeTailMultiplier(activity) else 1.0
        // --- Late fat correction: plus permissif après plusieurs heures post-meal ---
        val lateApplied = suspectedLateFatMeal
        var lateMult = 1.0

        if (lateApplied) {
            // B1+B2+F5 — Late fat/protein rise damping.
            // Base is the user preference `lateFattyMealDamping` (default 0.7 = up to −30% SMB), NOT a
            // hardcoded constant. It ramps back toward neutral (0.95) as time since the meal elapses, since
            // the delayed rise fades over ~4h. `elapsedSinceMealMin` is now passed in explicitly by the
            // caller — the previous reflection into `ModeState.timeSinceMealMin` always threw (no such field)
            // so `elapsed` was pinned at 0 and the whole ladder collapsed to a constant 0.85.
            val base = policy.lateFattyMealDamping
            val progress = (elapsedSinceMealMin / 240.0).coerceIn(0.0, 1.0)
            lateMult = base + (0.95 - base).coerceAtLeast(0.0) * progress
            out *= lateMult
        }
        if (tailApplied) out *= tailMult

        val exerciseApplied = exercise
        val exerciseMult = if (exerciseApplied) policy.postExerciseDamping else 1.0
        if (exerciseApplied) out *= exerciseMult

        //val lateApplied = suspectedLateFatMeal
        //val lateMult = if (lateApplied) policy.lateFattyMealDamping else 1.0
        // if (lateApplied) out *= lateMult  <-- REMOVED DUPLICATE

        return SmbDampingAudit(
            out = out,
            tailApplied = tailApplied, tailMult = tailMult,
            activityRelief = tailRelief,
            activityStage = activity.stage,
            exerciseApplied = exerciseApplied, exerciseMult = exerciseMult,
            lateFatApplied = lateApplied, lateFatMult = lateMult,
            mealBypass = false
        )
    }

    private fun computeTailMultiplier(activity: InsulinActivityState): Double {
        val relief = computeActivityRelief(activity)
        val base = policy.smbDampingAtTail
        return base + (1.0 - base) * relief
    }

    private fun computeActivityRelief(activity: InsulinActivityState): Double {
        val stageRelief = when (activity.stage) {
            InsulinActivityStage.PRE_ONSET -> activity.anticipationWeight * 0.7
            InsulinActivityStage.RISING -> activity.relativeActivity
            InsulinActivityStage.PEAK -> activity.relativeActivity
            InsulinActivityStage.TAIL -> (1.0 - activity.postWindowFraction) * 0.3
            InsulinActivityStage.EXHAUSTED -> 0.0
        }
        val freshness = (1.0 - activity.postWindowFraction).coerceIn(0.0, 1.0)
        val anticip = activity.anticipationWeight
        val blended = 0.5 * stageRelief + 0.3 * freshness + 0.2 * anticip
        return blended.coerceIn(0.0, 1.0)
    }
}
