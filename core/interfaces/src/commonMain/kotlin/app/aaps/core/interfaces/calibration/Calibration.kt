package app.aaps.core.interfaces.calibration

import app.aaps.core.data.iob.InMemoryGlucoseValue

interface Calibration {

    /**
     * Apply calibration override to in-memory glucose values.
     *
     * Implementations populate [InMemoryGlucoseValue.calibrated] for each entry
     * where the override should take effect. Consumers read the corrected value
     * via [InMemoryGlucoseValue.recalculated], which falls back through
     * smoothed -> calibrated -> value.
     *
     * The default plugin (no calibration) returns the input list unchanged.
     *
     * @param data    input list ([0] is the most recent reading)
     * @param context optional hints such as sensor session boundary
     * @return the same list with [InMemoryGlucoseValue.calibrated] populated where applicable
     */
    suspend fun calibrate(
        data: MutableList<InMemoryGlucoseValue>,
        context: CalibrationContext = CalibrationContext.NONE
    ): MutableList<InMemoryGlucoseValue>

    /**
     * Persist a new fingerstick entry as a calibration input.
     * The default plugin treats this as a no-op and returns [AddEntryResult.Accepted].
     *
     * @param bgMgdl    fingerstick value in mg/dL
     * @param timestamp the submission moment in epoch ms — typically `dateUtil.now()`.
     *                  Pre-conditions like warm-up and pair lookback are evaluated relative
     *                  to this timestamp, so it MUST be close to the current time.
     *                  Historical re-entry (e.g. from a backup import) is not supported here.
     * @return [AddEntryResult.Accepted] if the entry was persisted, or a
     *         [AddEntryResult.Rejected] variant describing why it was not
     */
    suspend fun addEntry(bgMgdl: Double, timestamp: Long): AddEntryResult

    /**
     * Check whether [addEntry] would currently be accepted. Lets callers (e.g. the
     * calibration dialog) gate UI affordances before the user picks a BG value,
     * instead of letting them confirm and then silently rejecting. The default
     * plugin always returns [AddEntryResult.Accepted].
     *
     * Conditions can change between this call and [addEntry], so [addEntry] still
     * re-evaluates everything. This is a UX hint, not a contract.
     */
    suspend fun checkPreconditions(): AddEntryResult

    /**
     * Current [CalibrationStatus] for the running sensor session, evaluated now.
     *
     * Meant for feedback right after [addEntry] returns [AddEntryResult.Accepted]: an accepted
     * entry can still leave the sensor value unchanged (e.g. the session's first entry, with
     * [CalibrationStatus.NeedMoreEntries]), and the caller needs to say so instead of going quiet.
     * The default plugin has nothing to fit and always returns [CalibrationStatus.Applied].
     *
     * Ref `Calibration.kt` L59 @ `6598201d`.
     */
    suspend fun status(): CalibrationStatus

    /**
     * Leave every calibration entry older than [timestamp] out of the fit.
     *
     * A promoted pre-soak sensor is given a session dated at its own activation, hours before the
     * swap. Without this, fingersticks taken on the sensor just retired are fitted onto the new one.
     * The default does nothing: a plugin that does not fit a line has nothing to cut off.
     *
     * Ref `Calibration.kt` L73 @ `6598201d` (`1b81e356c8`). ONE+ calls it from
     * `DexcomOnePlusPlugin` promotion; Libre 3 calls it from `Libre3NativePlugin` promotion.
     */
    suspend fun ignoreEntriesBefore(timestamp: Long) {}
}
