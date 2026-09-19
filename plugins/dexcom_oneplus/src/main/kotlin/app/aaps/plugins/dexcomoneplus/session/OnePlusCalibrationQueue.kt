package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrateTx

/**
 * The one command a caller outside the BLE thread may ask a running session to send.
 *
 * The Control/EGV loop is a blocking cycle: it writes a request, waits for an indication, handles it,
 * and starts again. There is no general command channel into it — the only thing of this kind before
 * was a boolean for "start a new sensor". A calibration cannot use that shape, because it carries a
 * value, a time, and an answer that has to come back to the user.
 *
 * So: one slot, not a list. A second fingerstick while one is still waiting is a mistake far more
 * often than an intention, and a sensor keeps every calibration it accepts for good.
 *
 * ⚠️ Nothing in this file sends anything. The path is built step by step (see
 * docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md) and stays behind an engineering switch that is off
 * by default until a Dexcom ONE+ has been seen to answer 0x35.
 */
class OnePlusCalibrationQueue {

    /** A fingerstick waiting to be handed to the sensor. */
    data class Pending(
        /** Blood value in mg/dL. */
        val glucoseMgdl: Int,
        /** Wall clock (epoch ms) of the BLOOD measurement, never of the moment it was typed in. */
        val bloodAtMs: Long,
    )

    @Volatile
    private var pending: Pending? = null

    /**
     * Ask for [glucoseMgdl] to be sent to the sensor.
     *
     * @return false when the value is outside what the sensor accepts, when the fingerstick is
     *   already too old to be meaningful, or when another one is still waiting.
     */
    fun offer(glucoseMgdl: Int, bloodAtMs: Long, nowMs: Long): Boolean {
        if (glucoseMgdl < OnePlusCalibrateTx.MIN_GLUCOSE_MGDL || glucoseMgdl > OnePlusCalibrateTx.MAX_GLUCOSE_MGDL) return false
        if (isTooOld(bloodAtMs, nowMs) || bloodAtMs > nowMs) return false
        if (pending != null) return false
        pending = Pending(glucoseMgdl, bloodAtMs)
        return true
    }

    /** What is waiting, without taking it — for a status screen. */
    fun peek(): Pending? = pending

    /** Take the waiting fingerstick, if any. The slot is free again from here on. */
    fun take(): Pending? {
        val taken = pending
        pending = null
        return taken
    }

    fun clear() {
        pending = null
    }

    companion object {

        /**
         * Past this age a fingerstick is not worth sending: the sensor lines it up with its own
         * history, and an hour of drift makes the pairing wrong. Same limit as xDrip and as the
         * Dexcom apps, which ask for the value within minutes of the prick.
         */
        const val MAX_AGE_MS = 60L * 60L * 1000L

        fun isTooOld(bloodAtMs: Long, nowMs: Long): Boolean = nowMs - bloodAtMs > MAX_AGE_MS

        /**
         * Transmitter time of the BLOOD measurement.
         *
         * The sensor is told when the blood was measured, not when the packet is written, so the
         * wall-clock age of the fingerstick is taken off the transmitter clock. The transmitter
         * clock itself is only known as "this value, read at that moment", so its own age counts too.
         *
         * @param lastDexTimeSeconds transmitter time last read from the sensor (0 = never)
         * @param lastDexTimeAtMs wall clock when that transmitter time was read
         * @return the transmitter second to put in the packet, or null when it cannot be worked out
         */
        fun dexTimeForBlood(
            bloodAtMs: Long,
            nowMs: Long,
            lastDexTimeSeconds: Int,
            lastDexTimeAtMs: Long,
        ): Int? {
            if (lastDexTimeSeconds <= 0 || lastDexTimeAtMs <= 0L) return null
            val dexNow = lastDexTimeSeconds + ((nowMs - lastDexTimeAtMs) / 1000L)
            val dexBlood = dexNow - ((nowMs - bloodAtMs) / 1000L)
            if (dexBlood <= 0L || dexBlood > Int.MAX_VALUE) return null
            return dexBlood.toInt()
        }
    }
}
