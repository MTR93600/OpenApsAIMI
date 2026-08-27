package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.core.interfaces.aps.Predictions

/**
 * Lowest BG across available prediction traces (IOB / COB / UAM / ZT).
 * Conservative aggregate for hypo / stacking guards; same logic as the former
 * `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2` private helper.
 */
internal fun minPredictedAcrossCurves(predBGs: Predictions?): Double? {
    val p = predBGs ?: return null
    val seriesMins = listOfNotNull(p.IOB, p.COB, p.UAM, p.ZT)
        .filter { it.isNotEmpty() }
        .map { row -> row.minOf { it } }
    if (seriesMins.isEmpty()) return null
    return seriesMins.minOf { it.toDouble() }.takeIf { it.isFinite() }
}
