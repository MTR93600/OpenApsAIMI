package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * "Enable SMB" (`BooleanKey.ApsUseSmb`, also off in open loop or before the SMB objective) for every
 * SMB writer of AIMI.
 *
 * The switch reached AIMI as `microBolusAllowed`, but only the GlobalAIMI path checked it: Autodrive V3
 * (with its floor and the HTR/RBT lift), the DriftTerminator and the AI Auditor still sent SMBs. The
 * loop does not check it again (same as upstream AAPS). Measured: with the switch off and Autodrive on,
 * 6 of 8 ticks carried an SMB (support package 2026-08-15).
 *
 * The meal prebolus is the one exception, by decision of 2026-10-22: a declared meal mode (legacy
 * P1/P2, FCL) and the Meal Advisor need their prebolus to cover the meal, SMB switch or not.
 */
object SmbSwitchGate {

    /** Owners whose dose is a meal prebolus. Names as passed to `applySmbUnits`. */
    val MEAL_PREBOLUS_OWNERS = setOf("MealAdvisor", "LegacyMealModes", "LegacyPrebolus")

    fun allows(smbEnabled: Boolean, owner: String): Boolean = smbEnabled || owner in MEAL_PREBOLUS_OWNERS
}
