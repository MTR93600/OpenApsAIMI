package app.aaps.plugins.aps.openAPSAIMI.pkpd

data class PkPdLogRow(
    val dateStr: String,
    val epochMin: Long,
    val bg: Double,
    val delta5: Double,
    val iobU: Double,
    val carbsActiveG: Double,
    val windowMin: Int,
    val diaH: Double,
    val peakMin: Double,
    val fusedIsf: Double,
    val tddIsf: Double,
    val profileIsf: Double,
    val tailFrac: Double,
    val smbProposedU: Double,
    val smbFinalU: Double,
    // NEW – audit (nullable pour compat ascendante)
    val tailMult: Double? = null,
    val exerciseMult: Double? = null,
    val lateFatMult: Double? = null,
    val highBgOverride: Boolean? = null,
    val lateFatRise: Boolean? = null,
    val quantStepU: Double? = null,
    val activityStage: String? = null,
    val activityRelief: Double? = null,
    val activityFraction: Double? = null,
    val anticipation: Double? = null
)
