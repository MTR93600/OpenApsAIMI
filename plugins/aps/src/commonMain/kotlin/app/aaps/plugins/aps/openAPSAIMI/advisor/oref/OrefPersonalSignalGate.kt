package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

/**
 * Single switch that keeps the personal on-device signal of [OrefPersonalMlTrainer] out of decisions.
 *
 * The personal head is trained on 0/1 labels **against its raw output**, and the value is then read back
 * through a sigmoid. So the reported number is not a probability. Measured on the real head settings it stays
 * inside roughly 50 % to 73 %, it can never fall below 50 %, and a fully confident prediction of 1.0 reports
 * only about 73 %. The reported value mostly follows `sigmoid(base rate)`, which means almost every patient
 * lands above 52 %. See the class KDoc of [OrefPersonalMlTrainer] for the full description.
 *
 * While that is true the signal must not steer anything. [CALIBRATED] is the only thing to change: as long as
 * it is `false`, [tripsDecision] answers `false` for every value, so the signal cannot open an advisor gate on
 * its own. The batch that gives the head a calibrated output can flip this one constant back; no call site has
 * to move.
 */
object OrefPersonalSignalGate {

    /**
     * `true` only once the personal signal has been checked against real outcomes and reads as a probability.
     * It has not been checked yet, so it is `false` and the signal is informational only.
     */
    const val CALIBRATED: Boolean = false

    /**
     * Reads the personal signal for a decision gate.
     *
     * @param signalPct mean personal signal in percent, or `null` when the head did not run
     * @param thresholdPct threshold this signal used to be compared against
     * @return always `false` while [CALIBRATED] is `false`, whatever the value is
     */
    fun tripsDecision(signalPct: Double?, thresholdPct: Double): Boolean =
        CALIBRATED && (signalPct ?: 0.0) >= thresholdPct
}
