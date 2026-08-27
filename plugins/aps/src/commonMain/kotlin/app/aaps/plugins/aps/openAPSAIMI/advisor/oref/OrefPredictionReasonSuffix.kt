package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT

/**
 * Appends an OpenAPS/OREF-style prediction segment to [RT.reason] so [OrefReasonParser] can fill
 * `reason_minGuardBG`, `reason_Dev`-adjacent prediction fields, and ONNX feature rows.
 *
 * AIMI [DetermineBasalAIMI2] historically omitted this block; advanced prediction paths already set
 * [RT.predBGs]. For minGuard we follow the same convention as AIMI console logging (min of curve
 * when IOB/COB/UAM/ZT are aligned).
 */
object OrefPredictionReasonSuffix {

    fun build(rT: RT, convertBgForReason: (Double) -> String): String {
        val preds = rT.predBGs ?: return ""
        val minPred = minAcrossPreds(preds) ?: return ""
        val minGuard = minPred
        val iob = preds.IOB
        if (iob.isNullOrEmpty()) return ""
        val lastIob = iob.last().toDouble().takeIf { it.isFinite() } ?: return ""
        val lastUam = preds.UAM?.lastOrNull()?.toDouble()?.takeIf { it.isFinite() } ?: lastIob
        return ", minPredBG ${convertBgForReason(minPred)}, minGuardBG ${convertBgForReason(minGuard)}, " +
            "IOBpredBG ${convertBgForReason(lastIob)}, UAMpredBG ${convertBgForReason(lastUam)}"
    }

    private fun minAcrossPreds(p: Predictions): Double? {
        val mins = listOfNotNull(
            p.IOB?.minOrNull()?.toDouble(),
            p.COB?.minOrNull()?.toDouble(),
            p.UAM?.minOrNull()?.toDouble(),
            p.ZT?.minOrNull()?.toDouble(),
        ).filter { it.isFinite() }
        if (mins.isEmpty()) return null
        return mins.minOrNull()
    }
}
