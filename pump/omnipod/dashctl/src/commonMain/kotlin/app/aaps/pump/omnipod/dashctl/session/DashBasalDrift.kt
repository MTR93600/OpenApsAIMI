package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

/**
 * The arithmetic of the basal drift correction of Android `OmnipodDashPodStateManagerImpl` (this
 * fork), ported as it is: [integrateExpectedDelivery] and [calculateBolusPulseIncrease]. The state
 * that uses them is in [DashPodStateManager] (`statusApplied`, `needsBasalCorrection`).
 *
 * Pure functions, so the Android tests run on them unchanged (`DashBasalDriftTest`).
 */
object DashBasalDrift {

    private const val MS_PER_DAY = 86_400_000L
    private const val MS_PER_SLOT = 30 * 60_000L
    private const val MS_PER_HOUR = 3_600_000.0

    /**
     * Android `integrateExpectedDelivery`: the basal (units) the pod should give over
     * [startTime, endTime], from the temp basal where it runs and from the basal program elsewhere.
     *
     * - Null when the pod time zone is not known ([timeZoneOffset] null), when [startTime] is after
     *   [endTime], or when a part of the window has no rate (no program and no temp basal there).
     * - The program slots are taken in the time zone of the pod ([timeZoneOffset], ms), as Android:
     *   the slot boundaries are found from the pod midnight, and the rate is read with
     *   [BasalProgram.rateAt] in the time zone of the phone ([deviceTimeZone]), shifted by the pod
     *   offset minus the phone offset at [startTime].
     *
     * @param deviceTimeZone the time zone of the phone, Android `TimeZone.getDefault()`. A parameter
     *   so the tests can set it (Kotlin/Native cannot change the default).
     */
    fun integrateExpectedDelivery(
        startTime: Long,
        endTime: Long,
        timeZoneOffset: Int?,
        tempBasal: DashTempBasal?,
        basalProgram: BasalProgram?,
        deviceTimeZone: TimeZone,
    ): Double? {
        if (timeZoneOffset == null) return null
        if (startTime > endTime) return null

        // The times where the rate can change.
        val boundaries = mutableSetOf(startTime, endTime)
        tempBasal?.let { tb ->
            val tempStart = tb.startTimeMs
            val tempEnd = tb.startTimeMs + tb.durationInMinutes * 60_000L
            if (tempStart in startTime until endTime) boundaries.add(tempStart)
            if (tempEnd in startTime until endTime) boundaries.add(tempEnd)
        }
        basalProgram?.segments?.forEach { segment ->
            // The day starts at the pod midnight, not at UTC midnight.
            val dayStartLocal = ((startTime + timeZoneOffset) / MS_PER_DAY) * MS_PER_DAY - timeZoneOffset
            var segmentStart = dayStartLocal + segment.startSlotIndex.toLong() * MS_PER_SLOT
            if (segmentStart <= startTime) segmentStart += MS_PER_DAY
            while (segmentStart < endTime) {
                boundaries.add(segmentStart)
                segmentStart += MS_PER_DAY
            }
        }

        // rateAt reads the time in the phone time zone; shift it so it reads the pod time.
        val deviceOffsetMs = deviceTimeZone.offsetAt(Instant.fromEpochMilliseconds(startTime)).totalSeconds * 1000
        val podTimeAdjustmentMs = timeZoneOffset - deviceOffsetMs

        var total = 0.0
        for ((boundaryStart, boundaryEnd) in boundaries.sorted().windowed(2).map { it[0] to it[1] }) {
            val segmentHours = (boundaryEnd - boundaryStart) / MS_PER_HOUR
            val segmentMid = (boundaryStart + boundaryEnd) / 2
            val rate = tempBasal?.let { tb ->
                val tempBasalEnd = tb.startTimeMs + tb.durationInMinutes * 60_000L
                tb.rate.takeIf { segmentMid in tb.startTimeMs until tempBasalEnd }
            } ?: basalProgram?.rateAt(segmentMid + podTimeAdjustmentMs, deviceTimeZone) ?: return null
            total += rate * segmentHours
        }
        return total
    }

    /**
     * Android `calculateBolusPulseIncrease`: the bolus part of the pulses given between two statuses.
     * The total increase, capped at what the pulses left of the bolus went down by, when that is known
     * (the rest are basal pulses given at the same time).
     */
    fun calculateBolusPulseIncrease(
        previousTotalPulses: Int,
        newTotalPulses: Int,
        previousBolusPulsesRemaining: Int?,
        newBolusPulsesRemaining: Int,
    ): Int {
        var increase = newTotalPulses - previousTotalPulses
        if (previousBolusPulsesRemaining != null) {
            val expectedIncrease = previousBolusPulsesRemaining - newBolusPulsesRemaining
            if (increase > expectedIncrease) increase = expectedIncrease
        }
        return increase
    }
}
