package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.core.interfaces.utils.Round
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import kotlin.math.ceil

/** Where the bolus wait shows how far the bolus is. On iOS: `BolusProgressData`. */
interface DashBolusProgress {

    /** Android `bolusProgressData.updateProgress(delivered = ...)`. */
    fun delivered(units: Double)

    /** Android `updateBolusProgressDialog(checking_delivery_status, 100)`, before each status read. */
    fun checkingDeliveryStatus()
}

/**
 * What the bolus wait needs from the pump. Each call runs one exchange with the pod, inside the
 * command that delivers the bolus (the pump holds its command lock for the whole wait).
 */
interface DashBolusWaitPod {

    /** True once the user asked to stop the bolus (Android `bolusCanceled`). */
    fun isCanceled(): Boolean

    /** Reads a status (Android `getPodStatus`). False when no status was read. */
    fun readStatus(): Boolean

    /**
     * Stops the bolus (Android `cancelBolus`: a stop of the bolus, with its own active command). False
     * when the stop was not sent or not confirmed; the wait then tries again after
     * [DashBolusWait.BOLUS_RETRY_INTERVAL_MS].
     */
    fun cancelBolus(): Boolean

    /** True when the last status shows a bolus running (Android `deliveryStatus.bolusDeliveringActive`). */
    fun bolusDeliveringActive(): Boolean

    /** The bolus the wait follows (Android `podStateManager.lastBolus`). */
    fun lastBolus(): DashLastBolus?

    /** The pod no longer delivers the bolus: end it and return it (Android `markComplete`). */
    fun completeLastBolus(): DashLastBolus?
}

/**
 * Follows a bolus the pod took until it ends. Port of Android
 * `OmnipodDashPumpPlugin.waitForBolusDeliveryToComplete`, with the same times:
 *
 * 1. Wait the time the bolus should take ([estimateBolusDeliverySeconds]), one second at a time,
 *    and show the estimated amount (not for an SMB). A stop ends this wait at once.
 * 2. Then up to [BOLUS_RETRIES] tries. Each try shows "checking delivery status" (100 %), then
 *    reads a status, or stops the bolus when the user asked for it.
 *    - The status or the stop failed: wait [BOLUS_RETRY_INTERVAL_MS] and try again.
 *    - The pod still delivers the bolus: show what went in (asked - left), then wait
 *      [BOLUS_RETRY_INTERVAL_MS] after a stop, or the time the rest should take.
 *    - The pod no longer delivers it: the bolus ended; what went in is asked - left
 *      (Android `LastBolus.deliveredUnits`).
 * 3. After the last try: the asked amount, as Android ("will be updated later"). The bolus is never
 *    sent again, and the result is never more than asked.
 *
 * Deliberate difference from Android: when the pod no longer delivers the bolus but the last status
 * still had units left (the pod stopped it, or a stop the pod took), the bolus is ended here and
 * asked - left is returned. Android reads `deliveredUnits()!!`, which throws when the bolus did not
 * end, and the whole bolus is then reported as failed with nothing delivered.
 *
 * The timer is [sleep], so a test runs the wait on virtual time.
 */
class DashBolusWait(
    private val pod: DashBolusWaitPod,
    private val progress: DashBolusProgress?,
    private val sleep: suspend (Long) -> Unit,
) {

    /**
     * Waits for the end of a bolus of [requestedUnits] the pod took, and returns what went in.
     *
     * @param isSmb an SMB shows no estimated progress, as Android.
     */
    suspend fun waitForBolusDeliveryToComplete(requestedUnits: Double, isSmb: Boolean): Double {
        val estimatedDeliveryTimeSeconds = estimateBolusDeliverySeconds(requestedUnits)
        var waited = 0L
        while (waited < estimatedDeliveryTimeSeconds && !pod.isCanceled()) {
            waited += 1
            sleep(ONE_SECOND_MS)
            if (isSmb) continue
            progress?.delivered(
                Round.roundTo(waited.toDouble() / estimatedDeliveryTimeSeconds * requestedUnits, PodConstants.POD_PULSE_BOLUS_UNITS)
            )
        }

        repeat(BOLUS_RETRIES) {
            progress?.checkingDeliveryStatus()
            val canceled = pod.isCanceled()
            val ok = if (canceled) pod.cancelBolus() else pod.readStatus()
            if (!ok) {
                sleep(BOLUS_RETRY_INTERVAL_MS)
                return@repeat
            }
            if (pod.bolusDeliveringActive()) {
                val remainingUnits = pod.lastBolus()?.bolusUnitsRemaining ?: requestedUnits
                progress?.delivered((requestedUnits - remainingUnits).coerceIn(0.0, requestedUnits))
                sleep(if (canceled) BOLUS_RETRY_INTERVAL_MS else estimateBolusDeliverySeconds(remainingUnits) * ONE_SECOND_MS)
            } else {
                return pod.completeLastBolus()?.deliveredUnits() ?: requestedUnits
            }
        }
        return requestedUnits
    }

    companion object {

        /** Android `BOLUS_RETRY_INTERVAL_MS`. */
        const val BOLUS_RETRY_INTERVAL_MS = 2000L

        /** Android `BOLUS_RETRIES`: tries to read the status or to stop the bolus. */
        const val BOLUS_RETRIES = 5

        private const val ONE_SECOND_MS = 1000L

        /** Android `estimateBolusDeliverySeconds`: 2 s per pulse, plus 3 s. */
        fun estimateBolusDeliverySeconds(units: Double): Long =
            ceil(units / PodConstants.POD_PULSE_BOLUS_UNITS).toLong() * 2 + 3
    }
}
