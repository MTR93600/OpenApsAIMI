package app.aaps.plugins.aps.openAPSAIMI.model

/**
 * Shared constants describing pump and safety defaults used across the AIMI stack.
 */
object Constants {
    /** Default pump micro-bolus quantization step in insulin units. */
    const val DEFAULT_INSULIN_STEP_U = 0.05

    /** Baseline hypo guard threshold used when no profile override is supplied. */
    const val HYPO_GUARD_TARGET_MGDL = 70.0

    /** Lower bound where aggressive high BG override can activate when the trend is rising. */
    const val HIGH_BG_OVERRIDE_BG_MIN = 150.0

    /** Strong hyperglycemia threshold triggering immediate override regardless of trend. */
    const val HIGH_BG_OVERRIDE_BG_STRONG = 180.0
}
