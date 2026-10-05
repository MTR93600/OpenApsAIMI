package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMin
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry

/**
 * Android writes [PkpdSoftFloorTelemetry] and the log line. The approved iOS port stores the
 * same telemetry and does not read it back into the dose. The path-min math stays
 * [PkpdSoftFloorPathMin.fromCurves].
 */
internal interface AimiPkpdSoftFloorWrite {
    fun writeTelemetryAndLog(telemetry: PkpdSoftFloorTelemetry)
}

internal fun decideRecordPkpdSoftFloor(
    curves: AdvancedPredictionCurves,
    endogenousReversionEnabled: Boolean,
    calls: AimiPkpdSoftFloorWrite,
): PkpdSoftFloorTelemetry {
    val telemetry = PkpdSoftFloorPathMin.fromCurves(
        curves = curves,
        endogenousReversionEnabled = endogenousReversionEnabled,
    )
    calls.writeTelemetryAndLog(telemetry)
    return telemetry
}
