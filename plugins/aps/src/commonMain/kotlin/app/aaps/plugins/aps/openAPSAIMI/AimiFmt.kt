package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import kotlin.math.abs

/**
 * Locale-stable number text for AIMI. Do not use `String.format` in commonMain.
 *
 * Every helper here replaces a `String.format("%.Nf", x)` call, so each one asks for
 * [NumberFormat.withDecimalsHalfUp]: `%.Nf` rounds ties away from zero, and [NumberFormat]'s own
 * default is half-even. The two differ whenever the halfway point is exactly representable as a
 * `Double`, which happens on values AIMI prints all the time - an odd quarter such as `0.25` or
 * `1.25` at one decimal, and any `x.5` at none. With the default, `aimiFmt1(0.25)` would write
 * `0.2` where the shipping Android build writes `0.3`.
 *
 * `:plugins:smoothing` reached the same conclusion for the same reason; see `fmtLog` there.
 *
 * The separator is pinned to a dot. Most of this text is read in logs or sent to a server, so it
 * must not follow the device locale. Text shown to the user goes through a string resource instead.
 */
internal fun aimiFmt0(value: Double): String =
    NumberFormat.withDecimalsHalfUp(0).format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmt1(value: Double): String =
    NumberFormat.withDecimalsHalfUp(1).format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmt2(value: Double): String =
    NumberFormat.withDecimalsHalfUp(2).format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmt4(value: Double): String =
    NumberFormat.withDecimalsHalfUp(4).format(value, NumberFormatPlatform.SEPARATOR_DOT)

internal fun aimiFmtSigned1(value: Double): String {
    val body = aimiFmt1(abs(value))
    return if (value >= 0.0) "+$body" else "-$body"
}
