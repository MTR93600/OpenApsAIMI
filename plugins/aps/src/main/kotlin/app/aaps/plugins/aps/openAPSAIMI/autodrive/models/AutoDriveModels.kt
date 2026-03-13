package app.aaps.plugins.aps.openAPSAIMI.autodrive.models

/**
 * 🧠 AutoDriveState
 *
 * Represents the continuous mathematical state of the patient at a specific time (T).
 * Unlike legacy models, this state is agnostic of high-level APS rules and focuses
 * on physiological and environmental variables.
 *
 * ### Example Usage:
 * ```kotlin
 * val state = AutoDriveState.createSafe(
 *     bg = 120.0,
 *     bgVelocity = 1.0,
 *     iob = 2.1,
 *     hour = 14,
 *     patientWeightKg = 75.0
 * )
 * ```
 *
 * @property bg Current blood glucose level in mg/dL. Must be in [30, 600].
 * @property bgVelocity Rate of change of blood glucose in mg/dL/min.
 * @property iob Insulin on Board (active insulin units). Cannot be negative.
 * @property cob Carbs on Board (active carb grams).
 * @property estimatedSI Estimated Insulin Sensitivity factor at time T.
 * @property estimatedRa Estimated Rate of Appearance of carbs.
 * @property patientWeightKg Body weight in Kg (used for Volume of Distribution calculations).
 * @property physiologicalStressMask Vector representing stress/sickness attention levels.
 * @property isNight Night mode flag (Sleep mode reduces algorithm aggressiveness).
 * @property hour Local hour (0-23), used for circadian adjustments like Dawn Guard.
 * @property steps Accumulated steps over the last 15 minutes.
 * @property hr Current heart rate (BPM).
 * @property rhr Resting heart rate (BPM).
 * @property sourceSensor Hardware sensor type (G6, G7, Guardian, etc.).
 * @property maxIOB Maximum allowed IOB safety limit.
 * @property maxSMB Maximum allowed SMB units for standard scenarios.
 * @property highBgMaxSMB Maximum allowed SMB units specifically for high BG corrections.
 */
data class AutoDriveState(
    val bg: Double,
    val bgVelocity: Double,
    val iob: Double,
    val cob: Double = 0.0,
    val estimatedSI: Double = 1.0,
    val estimatedRa: Double = 0.0,
    val patientWeightKg: Double = 70.0,
    val physiologicalStressMask: DoubleArray,
    val isNight: Boolean = false,
    val hour: Int = 12,
    val steps: Int = 0,
    val hr: Int = 70,
    val rhr: Int = 60,
    val sourceSensor: app.aaps.core.data.model.SourceSensor? = null,
    val maxIOB: Double = 3.0,
    val maxSMB: Double = 1.0,
    val highBgMaxSMB: Double = 2.0
) {
    init {
        require(bg in 30.0..600.0) { "BG out of safe bounds: $bg" }
        require(bgVelocity in -10.0..10.0) { "BG Velocity out of bounds: $bgVelocity" }
        require(iob >= 0.0) { "IOB cannot be negative: $iob" }
        require(cob >= 0.0) { "COB cannot be negative: $cob" }
        require(estimatedSI > 0.0) { "Estimated SI must be positive: $estimatedSI" }
        require(estimatedRa >= 0.0) { "Estimated Ra cannot be negative: $estimatedRa" }
        require(patientWeightKg in 40.0..250.0) { "Patient weight out of physiological bounds: $patientWeightKg" }
        require(hour in 0..23) { "Invalid hour: $hour" }
    }

    companion object {
        /**
         * Creates a safe [AutoDriveState] by coercing all inputs into physiological bounds.
         * Useful when parsing external data from sensors or persistence layers.
         */
        @JvmStatic
        fun createSafe(
            bg: Double,
            bgVelocity: Double,
            iob: Double,
            cob: Double = 0.0,
            estimatedSI: Double = 1.0,
            estimatedRa: Double = 0.0,
            patientWeightKg: Double = 70.0,
            physiologicalStressMask: DoubleArray = DoubleArray(0),
            isNight: Boolean = false,
            hour: Int = 12,
            steps: Int = 0,
            hr: Int = 70,
            rhr: Int = 60,
            sourceSensor: app.aaps.core.data.model.SourceSensor? = null,
            maxIOB: Double = 3.0,
            maxSMB: Double = 1.0,
            highBgMaxSMB: Double = 2.0
        ): AutoDriveState {
            return try {
                AutoDriveState(
                    bg = bg.coerceIn(30.0, 600.0),
                    bgVelocity = bgVelocity.coerceIn(-10.0, 10.0),
                    iob = iob.coerceAtLeast(0.0),
                    cob = cob.coerceAtLeast(0.0),
                    estimatedSI = estimatedSI.coerceAtLeast(0.1),
                    estimatedRa = estimatedRa.coerceAtLeast(0.0),
                    patientWeightKg = patientWeightKg.coerceIn(40.0, 250.0),
                    physiologicalStressMask = physiologicalStressMask,
                    isNight = isNight,
                    hour = hour.coerceIn(0, 23),
                    steps = steps.coerceAtLeast(0),
                    hr = hr.coerceAtLeast(0),
                    rhr = rhr.coerceAtLeast(0),
                    sourceSensor = sourceSensor,
                    maxIOB = maxIOB.coerceAtLeast(0.0),
                    maxSMB = maxSMB.coerceAtLeast(0.0),
                    highBgMaxSMB = highBgMaxSMB.coerceAtLeast(0.0)
                )
            } catch (e: Exception) {
                AutoDriveState(
                    bg = 100.0,
                    bgVelocity = 0.0,
                    iob = 0.0,
                    physiologicalStressMask = DoubleArray(0)
                )
            }
        }
    }
}

/**
 * 🕹️ AutoDriveCommand
 *
 * Represents a set of control actions computed by the AutoDrive engine (MPC)
 * before verification by the safety shield.
 *
 * @property scheduledMicroBolus Amount of insulin to deliver via SMB (units).
 * @property temporaryBasalRate Proposed target basal rate in U/h.
 * @property isSafe Indicates if the command passed internal sanity checks.
 * @property reason Explanatory string for human audibility and decision tracking.
 */
data class AutoDriveCommand(
    val scheduledMicroBolus: Double,
    val temporaryBasalRate: Double,
    val isSafe: Boolean = true,
    val reason: String = "Autodrive Init"
)
