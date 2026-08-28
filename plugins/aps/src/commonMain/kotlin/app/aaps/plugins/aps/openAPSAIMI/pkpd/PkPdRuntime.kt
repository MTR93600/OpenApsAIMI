package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Read-only numbers about the DIA learning loop, for the intelligence snapshot.
 *
 * @property diaAtFloor learned DIA sitting on the lower bound (within 0.01 h)
 * @property diaRegPullH last pull back toward the anchor, in hours
 * @property diaLearnStepH last raw learning step, in hours, before the anchor pull and any clamp
 * @property iobResidual120Min insulin left on board 2 h after a dose, 0..1
 * @property diaAcceptedUpdates how many learning steps the estimator has accepted so far. When this
 *   number is the same as on the tick before, [diaRegPullH] and [diaLearnStepH] were kept from an
 *   older tick and must not be read as this tick's step.
 * @property diaLearnBlockedBy why learning did not run on this tick, or null when it did run. Taken
 *   in `PkPdIntegration.computeRuntime` at the line where the gate is really applied.
 */
data class PkpdLearningTrace(
    val diaAtFloor: Boolean,
    val diaRegPullH: Double,
    val diaLearnStepH: Double,
    val iobResidual120Min: Double,
    val diaAcceptedUpdates: Long = 0L,
    val diaLearnBlockedBy: String? = null,
)

class PkPdRuntime(
    val params: PkPdParams,
    val tailFraction: Double,
    val fusedIsf: Double,
    val profileIsf: Double,
    val tddIsf: Double,
    val pkpdScale: Double,
    val weightKineticFactor: Double,
    val physioAbsorptionFactor: Double,
    val physioSiFactor: Double,
    private val damping: SmbDamping,
    val activity: InsulinActivityState,
    val learningTrace: PkpdLearningTrace? = null,
) {

    fun dampSmbWithAudit(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        elapsedSinceMealMin: Double = 0.0
    ): SmbDampingAudit =
        damping.dampWithAudit(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity, elapsedSinceMealMin)

    fun dampSmb(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false,
        elapsedSinceMealMin: Double = 0.0
    ): Double =
        damping.damp(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity, elapsedSinceMealMin)
}
