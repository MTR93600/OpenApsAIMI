package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Glucose units for the AIMI screens (Assistant, "Я ем", coefficient analysis). The screens set [mmol] from the
 * user's units in AndroidAPS when they open, so every hint, default value and input is shown in the same units as
 * the rest of the app. The code keeps all values in mg/dL; only the text and the input fields change.
 */
object AimiUnits {

    /** mg/dL in 1 mmol/L. */
    const val MGDL_PER_MMOL = 18.0

    /** True when the user works in mmol/L. */
    @Volatile var mmol: Boolean = true

    private val ru = Locale("ru", "RU")

    /** The divider from mg/dL to the shown unit: 18 for mmol/L, 1 for mg/dL. */
    fun div(): Double = if (mmol) MGDL_PER_MMOL else 1.0

    /** Name of the glucose unit. */
    fun unit(): String = if (mmol) "ммоль/л" else "мг/дл"

    /** Glucose value in the user's unit, without the unit name: 108 mg/dL → "6,0" or "108". */
    fun num(mgdl: Double, mmolDecimals: Int = 1): String =
        if (mmol) String.format(ru, "%.${mmolDecimals}f", mgdl / MGDL_PER_MMOL) else mgdl.roundToInt().toString()

    /** Glucose value with the unit: 108 mg/dL → "6,0 ммоль/л" or "108 мг/дл". */
    fun g(mgdl: Double, mmolDecimals: Int = 1): String = "${num(mgdl, mmolDecimals)} ${unit()}"

    /** Change of glucose in 5 minutes: 3 mg/dL → "0,17 ммоль/л за 5 мин" or "3 мг/дл за 5 мин". */
    fun rate5(mgdl: Double): String = "${num(mgdl, 2)} ${unit()} за 5 мин"

    /** Sensitivity: 72 mg/dL per U → "4,00 ммоль/л на 1 ЕД" or "72 мг/дл на 1 ЕД". */
    fun isf(mgdlPerU: Double): String = "${num(mgdlPerU, 2)} ${unit()} на 1 ЕД"

    /** Short sensitivity form for tables: "4,00 ммоль/л/ЕД" or "72 мг/дл/ЕД". */
    fun isfShort(mgdlPerU: Double): String = "${num(mgdlPerU, 2)} ${unit()}/ЕД"
}
