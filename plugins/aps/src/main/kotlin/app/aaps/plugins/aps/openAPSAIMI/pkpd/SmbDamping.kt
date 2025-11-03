package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class TailAwareSmbPolicy(
    val tailIobHigh: Double = 0.25,
    val smbDampingAtTail: Double = 0.5,
    val postExerciseDamping: Double = 0.6,
    val lateFattyMealDamping: Double = 0.7
)

class SmbDamping(
    private val policy: TailAwareSmbPolicy = TailAwareSmbPolicy()
) {
    fun damp(
        smbU: Double,
        iobTailFrac: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean
    ): Double {
        var out = smbU
        if (iobTailFrac > policy.tailIobHigh) out *= policy.smbDampingAtTail
        if (exercise) out *= policy.postExerciseDamping
        if (suspectedLateFatMeal) out *= policy.lateFattyMealDamping
        return out
    }
}