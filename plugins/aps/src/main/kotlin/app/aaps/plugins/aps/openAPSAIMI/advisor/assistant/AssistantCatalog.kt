package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.R

/**
 * Settings that the assistant may change. Only settings that were checked in the code
 * and really change the delivery are here. "Max minutes of basal" is not here: AIMI does not use it.
 */
object AssistantCatalog {

    private fun dbl(key: DoubleKey, name: String, step: Double, what: Int, related: Int, tpo: Boolean, min: Double = key.min) =
        AssistantSetting(name, AssistantSetting.Kind.DOUBLE, key.key, key.defaultValue, min, key.max, step, what, related, tpo)

    val highBgInterval = AssistantSetting(
        englishName = "High BG SMB interval (min)",
        kind = AssistantSetting.Kind.INT,
        prefKey = IntKey.OApsAIMIHighBGinterval.key,
        default = IntKey.OApsAIMIHighBGinterval.defaultValue.toDouble(),
        min = IntKey.OApsAIMIHighBGinterval.min.toDouble(),
        max = IntKey.OApsAIMIHighBGinterval.max.toDouble(),
        step = 1.0,
        whatItDoes = R.string.aimi_as_what_high_bg_interval,
        related = R.string.aimi_as_rel_interval,
    )
    val priorityExtra = dbl(DoubleKey.OApsAIMIPriorityMaxIobExtraU, "Priority MaxIOB extra (U)", 0.5, R.string.aimi_as_what_priority_extra, R.string.aimi_as_rel_iob, true)
    val priorityFactor = dbl(DoubleKey.OApsAIMIPriorityMaxIobFactor, "Priority MaxIOB factor", 0.1, R.string.aimi_as_what_priority_factor, R.string.aimi_as_rel_iob, true)
    val maxIob = dbl(DoubleKey.ApsSmbMaxIob, "Max IOB (U)", 0.5, R.string.aimi_as_what_max_iob, R.string.aimi_as_rel_iob, false)
    val highBgMaxSmb = dbl(DoubleKey.OApsAIMIHighBGMaxSMB, "High BG Max SMB (U)", 0.1, R.string.aimi_as_what_high_bg_max_smb, R.string.aimi_as_rel_smb_size, true)
    val maxSmb = dbl(DoubleKey.OApsAIMIMaxSMB, "Max SMB (U)", 0.1, R.string.aimi_as_what_max_smb, R.string.aimi_as_rel_smb_size, true)
    val redCarpet = dbl(DoubleKey.OApsAIMIRedCarpetRestoreThreshold, "Red Carpet restore threshold", 0.05, R.string.aimi_as_what_red_carpet, R.string.aimi_as_rel_smb_size, true)
    val tailDamping = dbl(DoubleKey.OApsAIMISmbTailDamping, "SMB tail damping", 0.05, R.string.aimi_as_what_tail_damping, R.string.aimi_as_rel_smb_size, true, min = 0.60)
    val lateFatDamping = dbl(DoubleKey.OApsAIMISmbLateFatDamping, "SMB late fat damping", 0.05, R.string.aimi_as_what_late_fat, R.string.aimi_as_rel_smb_size, true, min = 0.60)
    val adaptiveBasal = dbl(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling, "Adaptive basal max scaling", 0.1, R.string.aimi_as_what_adaptive_basal, R.string.aimi_as_rel_basal, false)

    val all: List<AssistantSetting> = listOf(
        highBgInterval, priorityExtra, priorityFactor, maxIob, highBgMaxSmb, maxSmb, redCarpet, tailDamping, lateFatDamping, adaptiveBasal,
    )

    fun byKey(prefKey: String): AssistantSetting? = all.firstOrNull { it.prefKey == prefKey }
}
