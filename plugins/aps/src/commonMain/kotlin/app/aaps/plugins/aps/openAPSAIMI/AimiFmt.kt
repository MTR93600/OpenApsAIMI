package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import kotlin.math.abs

/** Locale-stable number text for AIMI logs. Do not use `String.format` in commonMain. */
internal fun aimiFmt0(value: Double): String =
    NumberFormat.INTEGER.format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmt1(value: Double): String =
    NumberFormat.DECIMAL_1.format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmt2(value: Double): String =
    NumberFormat.DECIMAL_2.format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmtSigned1(value: Double): String {
    val body = aimiFmt1(abs(value))
    return if (value >= 0.0) "+$body" else "-$body"
}
