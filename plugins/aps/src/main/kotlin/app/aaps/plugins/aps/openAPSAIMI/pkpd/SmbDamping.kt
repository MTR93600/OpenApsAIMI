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
    val exerciseApplied: Boolean,
    val exerciseMult: Double,
    val lateFatApplied: Boolean,
    val lateFatMult: Double,
    val mealBypass: Boolean
)

class SmbDamping(
    private val policy: TailAwareSmbPolicy = TailAwareSmbPolicy()
) {

    fun damp(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): Double {
        if (bypassDamping) return smbU
        var out = smbU
        if (iobTailFrac > policy.tailIobHigh) out *= policy.smbDampingAtTail
        if (exercise) out *= policy.postExerciseDamping
        if (suspectedLateFatMeal) out *= policy.lateFattyMealDamping
        return out
    }

    fun dampWithAudit(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): SmbDampingAudit {
        if (bypassDamping) {
            return SmbDampingAudit(
                out = smbU,
                tailApplied = false, tailMult = 1.0,
                exerciseApplied = false, exerciseMult = 1.0,
                lateFatApplied = false, lateFatMult = 1.0,
                mealBypass = true // (optionnel: renommer en pkpdBypass si tu veux)
            )
        }
        var out = smbU
        val tailApplied = iobTailFrac > policy.tailIobHigh
        val tailMult = if (tailApplied) policy.smbDampingAtTail else 1.0
        if (tailApplied) out *= tailMult

        val exerciseApplied = exercise
        val exerciseMult = if (exerciseApplied) policy.postExerciseDamping else 1.0
        if (exerciseApplied) out *= exerciseMult

        val lateApplied = suspectedLateFatMeal
        val lateMult = if (lateApplied) policy.lateFattyMealDamping else 1.0
        if (lateApplied) out *= lateMult

        return SmbDampingAudit(
            out = out,
            tailApplied = tailApplied, tailMult = tailMult,
            exerciseApplied = exerciseApplied, exerciseMult = exerciseMult,
            lateFatApplied = lateApplied, lateFatMult = lateMult,
            mealBypass = false
        )
    }
}
