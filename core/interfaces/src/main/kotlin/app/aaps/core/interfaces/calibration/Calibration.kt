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
     */
    suspend fun status(): CalibrationStatus

    /**
     * Drop every fingerstick entry older than [timestamp] from the running fit.
     *
     * Called when a sensor is swapped in a way that leaves the entries of the PREVIOUS sensor
     * inside the new sensor's session. A pre-soak promotion does exactly that: the new sensor's
     * session is dated at its own activation, hours before the swap, so every fingerstick the user
     * took in between — all of them paired against the OLD sensor — would otherwise be fitted onto
     * the new one, and applied from its first minute with no warm-up left.
     *
     * The entries themselves are kept: they are true history of the sensor that recorded them, and
     * only the fit stops using them. The default plugin has no fit and does nothing.
     */
    suspend fun ignoreEntriesBefore(timestamp: Long) {}
}
