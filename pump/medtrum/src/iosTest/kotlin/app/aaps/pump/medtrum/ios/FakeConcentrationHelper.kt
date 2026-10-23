package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import kotlinx.coroutines.CoroutineScope

/** U100 insulin, so a pump amount is the same number of units. Only what `BolusProgressData` needs. */
object FakeConcentrationHelper : ConcentrationHelper {

    override val concentration: Double = 1.0
    override fun isU100(): Boolean = true
    override fun fromPump(amount: PumpInsulin, isPriming: Boolean): Double = amount.cU
    override fun fromPump(rate: PumpRate): Double = rate.cU
    override fun toPump(amount: Double): PumpInsulin = PumpInsulin(amount)
    override fun toPumpRate(rate: Double): PumpRate = PumpRate(rate)
    override fun bolusStep(amount: Double): Double = amount
    override fun basalRateString(rate: PumpRate, isAbsolute: Boolean, decimals: Int): String = ""
    override fun basalTbrString(rate: PumpRate, startTime: Long, durationInMin: Int, isAbsolute: Boolean, isExtended: Boolean, decimals: Int): String = ""
    override fun insulinAmountString(amount: PumpInsulin): String = ""
    override fun insulinAmountAgoString(amount: PumpInsulin, lastBolusTime: Long): String? = null
    override fun insulinDeliveryAgoString(amount: PumpInsulin, totalAmount: PumpInsulin, startTime: Long, durationInMin: Int?): String = ""
    override fun insulinConcentrationString(): String = ""
    override fun bolusWithVolume(amount: Double): String = ""
    override fun bolusWithConvertedVolume(amount: Double): String = ""
    override fun bolusProgressString(delivered: PumpInsulin, isPriming: Boolean): String = "${delivered.cU}"
    override fun bolusProgressString(delivered: PumpInsulin, total: Double, isPriming: Boolean): String = "${delivered.cU}/$total"
}

/** A real [BolusProgressData] for a test, on the given scope. */
fun testBolusProgressData(scope: CoroutineScope): BolusProgressData = BolusProgressData(FakeConcentrationHelper, scope)
