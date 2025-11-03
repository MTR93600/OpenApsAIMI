package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class TailAwareSmbPolicy(
    val tailIobHigh: Double = 0.25,
    val smbDampingAtTail: Double = 0.5,
    val postExerciseDamping: Double = 0.6,
    val lateFattyMealDamping: Double = 0.7
)
//
// class SmbDamping(
//     private val policy: TailAwareSmbPolicy = TailAwareSmbPolicy()
// ) {
//     fun damp(
//         smbU: Double,
//         iobTailFrac: Double,
//         exercise: Boolean,
//         suspectedLateFatMeal: Boolean
//     ): Double {
//         var out = smbU
//         if (iobTailFrac > policy.tailIobHigh) out *= policy.smbDampingAtTail
//         if (exercise) out *= policy.postExerciseDamping
//         if (suspectedLateFatMeal) out *= policy.lateFattyMealDamping
//         return out
//     }
// }
data class SmbDampingResult(
    val out: Double,
    val tailApplied: Boolean,   val tailMult: Double,
    val exerciseApplied: Boolean, val exerciseMult: Double,
    val lateFatApplied: Boolean,  val lateFatMult: Double
)

class SmbDamping(
    private val policy: TailAwareSmbPolicy = TailAwareSmbPolicy()
) {
    fun damp(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean
    ): Double = dampWithAudit(smbU, iobTailFrac, exercise, suspectedLateFatMeal).out

    fun dampWithAudit(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean
    ): SmbDampingResult {
        var out = smbU
        val tailHit = iobTailFrac > policy.tailIobHigh
        val tailMult = if (tailHit) policy.smbDampingAtTail else 1.0
        if (tailHit) out *= tailMult

        val exMult = if (exercise) policy.postExerciseDamping else 1.0
        if (exercise) out *= exMult

        val lateMult = if (suspectedLateFatMeal) policy.lateFattyMealDamping else 1.0
        if (suspectedLateFatMeal) out *= lateMult

        return SmbDampingResult(
            out = out,
            tailApplied = tailHit, tailMult = tailMult,
            exerciseApplied = exercise, exerciseMult = exMult,
            lateFatApplied = suspectedLateFatMeal, lateFatMult = lateMult
        )
    }
}


