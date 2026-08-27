package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Boolean flags for IOB surveillance / stacking diagnostics (finalize SMB path).
 * Logic matches the former private helpers on `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2`.
 */
internal fun signalEventualDrop(bgMgdl: Double, eventualBg: Double?): Boolean =
    eventualBg != null && eventualBg < bgMgdl - 6.0

internal fun signalMinPredDrop(bgMgdl: Double, minPredictedBg: Double?): Boolean =
    minPredictedBg != null && minPredictedBg < bgMgdl - 10.0

internal fun signalTrajectoryStack(trajectoryEnergy: Double?): Boolean =
    trajectoryEnergy != null && trajectoryEnergy.isFinite() && trajectoryEnergy > 2.0
