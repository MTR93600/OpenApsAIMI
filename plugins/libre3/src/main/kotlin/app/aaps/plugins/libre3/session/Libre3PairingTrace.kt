package app.aaps.plugins.libre3.session

import app.aaps.plugins.libre3.Libre3Log
import app.aaps.plugins.libre3.Libre3LogMarkers

/**
 * Step by step trace of one pairing attempt.
 *
 * It exists for one question the ordinary log cannot answer: when a pairing fails, was our answer
 * wrong, or were we simply too slow? The driver logs one line when the pairing starts and one when
 * it fails, and between the two there is nothing, so a sensor that hangs up because it waited too
 * long looks exactly like a sensor that refused a bad key.
 *
 * So every step gets a line with two numbers: how long that step took, and how long the whole
 * attempt has been running. A step that takes seconds is then plain to see.
 *
 * The bytes matter as much as the clock. A pairing that fails at the last step fails because one
 * of the values below is not what the sensor expected, and the only way to find out which is to
 * compare them with a trace from a reference implementation. So the values that travel on the
 * radio are written down as well.
 *
 * ⚠️ [TRACE_SECRETS] writes key material to the log. It is on while the first pairing is being
 * brought up, because without it the failing step cannot be compared with anything. Turn it off
 * once the first pairing works, and remember that a log taken with it on must not be shared
 * outside the people debugging this sensor.
 */
class Libre3PairingTrace(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val startedAt = nowMs()
    private var lastStepAt = startedAt

    /**
     * Writes one step down.
     *
     * @param step what just finished, in a few words.
     */
    fun mark(step: String) {
        val now = nowMs()
        val sinceLast = now - lastStepAt
        lastStepAt = now
        Libre3Log.i("${Libre3LogMarkers.TRACE}: $step +${sinceLast}ms (t=${now - startedAt}ms)")
    }

    /** Writes down a value that travels on the radio, so nothing about it is a secret. */
    fun bytes(label: String, value: ByteArray) {
        Libre3Log.i("${Libre3LogMarkers.TRACE}: $label ${value.size} bytes ${hex(value)}")
    }

    /**
     * Writes down a value that never leaves the phone.
     *
     * Only the length is logged unless [TRACE_SECRETS] is on.
     */
    fun secret(label: String, value: ByteArray) {
        if (TRACE_SECRETS) {
            Libre3Log.i("${Libre3LogMarkers.TRACE}: $label ${value.size} bytes ${hex(value)}")
        } else {
            Libre3Log.i("${Libre3LogMarkers.TRACE}: $label ${value.size} bytes, not shown")
        }
    }

    companion object {

        /** Whether key material is written to the log. See the warning on this class. */
        const val TRACE_SECRETS = true

        fun hex(value: ByteArray): String = value.joinToString("") { "%02x".format(it) }
    }
}
