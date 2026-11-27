package app.aaps.plugins.source

import app.aaps.core.interfaces.source.CgmSensorAlertRules
import app.aaps.core.interfaces.source.CgmSensorAlertState
import app.aaps.core.interfaces.source.CgmSensorFailure
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrationState

/**
 * What the ONE+ / G7 driver says, turned into the generic sensor alarm state
 * ([app.aaps.core.interfaces.source.CgmSensorAlertProvider]).
 *
 * The plugin hands every driver event to it ([onWarmup], [onReading], [onStaging]); the times of the
 * changes are kept here, so the state names its episode with a time that does not move (the start of
 * a lost link, the moment warm-up ended). [state] then applies [CgmSensorAlertRules].
 *
 * What the driver tells:
 * - `FAILED` with the calibration state of the sensor as message: `SensorFailed` is a sensor error;
 *   `Ended`, `SensorExpired` and `SensorStopped` are an end the sensor reports (expired after the
 *   usable time, else ended early). `FAILED` for any other reason (no pairing, refused) is a failure.
 * - `RECONNECTING`: the link is lost, since the first event of that phase.
 * - `WARMING` with a calibration state that is not a warm-up (`ExcessNoise`, a calibration request,
 *   an unknown state): the sensor sends, but no usable value. A sensor issue.
 * - `READY` after `WARMING`: warm-up done.
 *
 * Not thread safe on its own: the plugin calls it under one lock.
 */
internal class DexcomOnePlusSensorAlerts {

    private var phase: OnePlusWarmupState.Phase = OnePlusWarmupState.Phase.IDLE
    private var failure: CgmSensorFailure? = null
    private var endedBySensor = false
    private var linkLostSinceMs: Long? = null
    private var issueSinceMs: Long? = null
    private var warmupDoneAtMs: Long? = null
    private var lastReadingAtMs: Long? = null
    private var secondSensorReadyAtMs: Long? = null
    private var lastWatchReadingAtMs: Long? = null
    private var watchOnlySinceMs: Long? = null

    /** When the plugin started, for [DexcomOnePlusFeed.STARTING]. */
    private var startedAtMs: Long? = null

    /** The plugin started: until the first reading, [feed] says so instead of "no recent glucose". */
    fun onStart(nowMs: Long) {
        startedAtMs = nowMs
    }

    /** The last WARMING was a sensor issue, not a warm-up, so READY after it is no "warm-up done". */
    private var lastWasIssue = false

    fun onWarmup(state: OnePlusWarmupState, nowMs: Long) {
        val previousWasWarmup = phase == OnePlusWarmupState.Phase.WARMING && !lastWasIssue
        phase = state.phase
        val calibration = state.message?.let { name -> OnePlusCalibrationState.entries.firstOrNull { it.name == name } }
        failure = null
        endedBySensor = false
        if (state.phase == OnePlusWarmupState.Phase.FAILED) {
            when (calibration) {
                OnePlusCalibrationState.SensorFailed       -> failure = CgmSensorFailure.SENSOR_ERROR

                OnePlusCalibrationState.Ended,
                OnePlusCalibrationState.SensorExpired,
                OnePlusCalibrationState.SensorStopped      -> endedBySensor = true

                else                                       -> failure = CgmSensorFailure.OTHER
            }
        }
        linkLostSinceMs = if (state.phase == OnePlusWarmupState.Phase.RECONNECTING) linkLostSinceMs ?: nowMs else null
        val issue = state.phase == OnePlusWarmupState.Phase.WARMING && calibration in ISSUE_STATES
        issueSinceMs = if (issue) issueSinceMs ?: nowMs else null
        if (state.phase == OnePlusWarmupState.Phase.READY && previousWasWarmup) warmupDoneAtMs = nowMs
        if (state.phase == OnePlusWarmupState.Phase.WARMING && !issue) warmupDoneAtMs = null
        lastWasIssue = issue
    }

    /** A usable reading of the production sensor arrived, taken at [timestampMs]. */
    fun onReading(timestampMs: Long) {
        if (timestampMs > (lastReadingAtMs ?: Long.MIN_VALUE)) lastReadingAtMs = timestampMs
        issueSinceMs = null
    }

    /**
     * A reading the watch took itself and sent to the phone, taken at [timestampMs]. Also when the
     * phone had it already: it shows that the watch still hears the sensor.
     */
    fun onWatchReading(timestampMs: Long) {
        if (timestampMs > (lastWatchReadingAtMs ?: Long.MIN_VALUE)) lastWatchReadingAtMs = timestampMs
    }

    /** Where the glucose comes from now, see [DexcomOnePlusFeed]. Keeps the start of a watch-only time. */
    fun feed(nowMs: Long): DexcomOnePlusFeedState {
        val feed = DexcomOnePlusFeed.of(lastReadingAtMs, lastWatchReadingAtMs, nowMs, startedAtMs)
        watchOnlySinceMs = if (feed == DexcomOnePlusFeed.WATCH_ONLY) watchOnlySinceMs ?: lastReadingAtMs ?: nowMs else null
        return DexcomOnePlusFeedState(feed, watchOnlySinceMs)
    }

    /** The state of the pre-soak slot changed. */
    fun onStaging(state: StagingState, nowMs: Long) {
        secondSensorReadyAtMs = if (state == StagingState.READY) secondSensorReadyAtMs ?: nowMs else null
    }

    /** A new sensor in the production slot: what was known of the old one is forgotten. */
    fun onNewSensor() {
        failure = null
        endedBySensor = false
        linkLostSinceMs = null
        issueSinceMs = null
        warmupDoneAtMs = null
        lastReadingAtMs = null
        lastWatchReadingAtMs = null
        watchOnlySinceMs = null
        lastWasIssue = false
    }

    /**
     * While the watch sends readings, glucose still reaches the loop: no signal loss is reported, the
     * plugin shows its own watch-only notice instead. When the watch stops too, the signal loss starts
     * at the last reading of either side, so it has the weight a phone-only loss had before.
     */
    fun state(
        running: Boolean,
        sensorStored: Boolean,
        expiresAtMs: Long?,
        nowMs: Long,
        expiringLeadMs: Long,
    ): CgmSensorAlertState {
        val watchCovers = DexcomOnePlusFeed.isFresh(lastWatchReadingAtMs, nowMs)
        val watchAt = lastWatchReadingAtMs
        val linkLost = when {
            watchCovers -> null
            watchAt == null -> linkLostSinceMs
            else -> linkLostSinceMs?.let { maxOf(it, watchAt) }
        }
        val lastReading = when {
            watchCovers -> null
            watchAt == null -> lastReadingAtMs
            else -> maxOf(lastReadingAtMs ?: watchAt, watchAt)
        }
        return map(running, sensorStored, expiresAtMs, nowMs, expiringLeadMs, linkLost, lastReading)
    }

    private fun map(
        running: Boolean,
        sensorStored: Boolean,
        expiresAtMs: Long?,
        nowMs: Long,
        expiringLeadMs: Long,
        linkLost: Long?,
        lastReading: Long?,
    ): CgmSensorAlertState = CgmSensorAlertRules.map(
        CgmSensorAlertRules.Input(
            stopped = !running,
            sensorStored = sensorStored,
            failure = failure,
            endedBySensor = endedBySensor,
            expiresAtMs = expiresAtMs,
            linkLostSinceMs = linkLost,
            lastReadingAtMs = lastReading,
            readingExpected = phase !in SILENCE_EXPECTED,
            issueSinceMs = issueSinceMs,
            secondSensorReadyAtMs = secondSensorReadyAtMs,
            warmupDoneAtMs = warmupDoneAtMs,
            expiringLeadMs = expiringLeadMs,
            nowMs = nowMs,
        )
    )

    companion object {

        /** Phases in which no reading comes on purpose, so silence is not a lost signal. */
        private val SILENCE_EXPECTED = setOf(OnePlusWarmupState.Phase.WARMING, OnePlusWarmupState.Phase.PAIRING)

        /** Calibration states in which the sensor sends, but no usable value, and is not warming up. */
        private val ISSUE_STATES = setOf(
            OnePlusCalibrationState.ExcessNoise,
            OnePlusCalibrationState.NeedsFirstCalibration,
            OnePlusCalibrationState.NeedsSecondCalibration,
            OnePlusCalibrationState.Unknown,
            OnePlusCalibrationState.Other,
        )
    }
}
