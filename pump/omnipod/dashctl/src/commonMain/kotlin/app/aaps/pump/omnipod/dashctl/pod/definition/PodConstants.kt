package app.aaps.pump.omnipod.dashctl.pod.definition

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class PodConstants {
    companion object {

        val MAX_POD_LIFETIME: Duration = 80.hours

        // Expiration alert time in hours before lifetime end
        const val POD_EXPIRATION_ALERT_HOURS_REMAINING_DEFAULT = 7L

        // Imminent expiration alert time in hours before lifetime end
        const val POD_EXPIRATION_IMMINENT_ALERT_HOURS_REMAINING = 1L

        // Bolus & Priming units
        const val POD_PULSE_BOLUS_UNITS = 0.05

        // Reservoir units alert threshold
        const val DEFAULT_MAX_RESERVOIR_ALERT_THRESHOLD: Short = 20

        /**
         * What every Dash pod reports in its answer to SetUniqueId: the prime bolus (52 pulses, 2.6 U),
         * the cannula bolus (10 pulses, 0.5 U), one prime pulse per second (8 eighths of a second) and
         * the pulse rate. Trio (OmnipodKit `Pod.primeUnits`, `Pod.cannulaInsertionUnits`,
         * `Pod.secondsPerPrimePulse`) always uses these fixed values. Used only when that answer was
         * lost, see `DashActivation`.
         */
        const val DASH_FIRST_PRIME_PULSES: Short = 52
        const val DASH_SECOND_PRIME_PULSES: Short = 10
        const val DASH_PRIME_PULSE_RATE: Short = 8
        const val DASH_PULSE_RATE: Short = 16
    }
}
