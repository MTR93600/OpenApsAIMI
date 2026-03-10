package app.aaps.core.interfaces.insulin

import app.aaps.core.interfaces.profile.Profile

interface ConcentrationHelper {
    // return true if default concentration (U100) false if another concentration is set
    fun isU100(): Boolean

    // Convert values sent to pump with current concentration
    fun toPump(amount: Double): Double

    // Convert values received from pump with current concentration
    fun fromPump(amount: Double): Double

    // Convert values sent to pump with current concentration
    fun toPump(profile: Profile): Profile

    // Get Current Profile with concentration convertion (to use within Pump Driver
    fun getProfile(): Profile?

    // show basalrate with units in U/h if U100: i.e. "0.6 U/h", and with both value if other concentration: i.e. for U200 "0.6 U/h (0.3 U/h)"
    fun basalRateString(rate: Double, toPump: Boolean = false): String

    // show bolus with units in U if U100: i.e. "4 U", and with both value if other concentration: i.e. for U200 "4 U (2 U)"
    fun insulinAmountString(amount: Double, toPump: Boolean = false): String

    // show insulinConcentration as a String i.e. "U100", "U200", ...
    fun insulinConcentrationString(): String

    // show bolus with volume in µl
    fun bolusWithVolume(amount:Double): String

    // show bolus with volume in µl after convertion due to concentration
    fun bolusWithConvertedVolume(amount:Double): String

    // show bolus Progress information with delivered
    fun bolusProgress(delivered: Double, totalAmount: Double): String

    // show bolus Progress information without delivered
    fun bolusProgressShort(delivered: Double, totalAmount: Double): String

    val concentration: Double
}