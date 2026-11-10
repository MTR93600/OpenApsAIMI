package app.aaps.core.objects.overview

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smoothing.Smoothing
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Keeps dashboard / overview headline glucose aligned with the APS [GlucoseStatus] pipeline
 * (bucket head, smoothed when applicable) when a smoothing plugin opts in, instead of a transient
 * raw DB value from [app.aaps.core.interfaces.overview.LastBgData].
 */
object DashboardCoherentGlucose {

    /** Same freshness window as SMB / AutoISF glucose status calculators for non-stale data. */
    private const val GLUCOSE_STATUS_FRESH_MS = 7 * 60 * 1000L

    /**
     * Dashboard [Context] is often [Context.getApplicationContext] (launcher theme without BG attrs).
     * Wrapping with [AppTheme] ensures `bgInRange` / `bgLow` / `highColor` resolve instead of falling
     * back to white — which made the hybrid glucose hero unreadable in light mode.
     */
    @ColorInt
    private fun resolveDashboardThemeColor(context: Context?, rh: ResourceHelper, @AttrRes attr: Int): Int {
        val base = context ?: return rh.gac(attr)
        val themed = ContextThemeWrapper(base, app.aaps.core.ui.R.style.AppTheme)
        return rh.gac(themed, attr)
    }

    fun displayMgdl(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long
    ): Double? =
        if (smoothing.preferDashboardGlucoseFromGlucoseStatus() && isFreshGlucoseStatus(glucoseStatus, now)) {
            glucoseStatus!!.glucose
        } else {
            lastBg?.recalculated
        }

    fun displayTimestamp(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long
    ): Long? =
        if (smoothing.preferDashboardGlucoseFromGlucoseStatus() && isFreshGlucoseStatus(glucoseStatus, now)) {
            glucoseStatus!!.date
        } else {
            lastBg?.timestamp
        }

    fun isDisplayActual(
        lastBg: InMemoryGlucoseValue?,
        glucoseStatus: GlucoseStatus?,
        smoothing: Smoothing,
        now: Long,
        maxAgeMs: Long
    ): Boolean {
        val ts = displayTimestamp(lastBg, glucoseStatus, smoothing, now) ?: return false
        return ts > now - maxAgeMs
    }

    fun isDisplayLow(displayMgdl: Double?, profileFunction: ProfileFunction, preferences: Preferences): Boolean {
        if (displayMgdl == null) return false
        val units = profileFunction.getUnits()
        val v = if (units == GlucoseUnit.MGDL) displayMgdl else displayMgdl * Constants.MGDL_TO_MMOLL
        return v < preferences.get(UnitDoubleKey.OverviewLowMark)
    }

    fun isDisplayHigh(displayMgdl: Double?, profileFunction: ProfileFunction, preferences: Preferences): Boolean {
        if (displayMgdl == null) return false
        val units = profileFunction.getUnits()
        val v = if (units == GlucoseUnit.MGDL) displayMgdl else displayMgdl * Constants.MGDL_TO_MMOLL
        return v > preferences.get(UnitDoubleKey.OverviewHighMark)
    }

    @ColorInt
    fun displayBgColor(
        context: Context?,
        displayMgdl: Double?,
        profileFunction: ProfileFunction,
        preferences: Preferences,
        rh: ResourceHelper
    ): Int =
        when {
            isDisplayLow(displayMgdl, profileFunction, preferences) ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.bgLow)
            isDisplayHigh(displayMgdl, profileFunction, preferences) ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.highColor)
            else ->
                resolveDashboardThemeColor(context, rh, app.aaps.core.ui.R.attr.bgInRange)
        }

    /**
     * Colour for the big glucose digits, smooth along the glucose value. In range: green. Above the high mark: yellow,
     * turning orange as it gets very high. Below the low mark: pale red, getting stronger the lower it goes.
     */
    @ColorInt
    fun gradientBgColor(displayMgdl: Double?, profileFunction: ProfileFunction, preferences: Preferences): Int {
        if (displayMgdl == null) return GRADIENT_IN_RANGE
        val toMgdl = if (profileFunction.getUnits() == GlucoseUnit.MGDL) 1.0 else 1.0 / Constants.MGDL_TO_MMOLL
        val lowMgdl = preferences.get(UnitDoubleKey.OverviewLowMark) * toMgdl
        val highMgdl = preferences.get(UnitDoubleKey.OverviewHighMark) * toMgdl
        return when {
            displayMgdl < lowMgdl -> {
                val deepest = 54.0
                val depth = ((lowMgdl - displayMgdl) / (lowMgdl - deepest).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
                blend(GRADIENT_LOW_PALE, GRADIENT_LOW_DEEP, depth)
            }
            displayMgdl > highMgdl -> {
                val veryHighSpan = 80.0
                val level = ((displayMgdl - highMgdl) / veryHighSpan).coerceIn(0.0, 1.0)
                blend(GRADIENT_HIGH_YELLOW, GRADIENT_HIGH_ORANGE, level)
            }
            else -> GRADIENT_IN_RANGE
        }
    }

    private const val GRADIENT_IN_RANGE = 0xFF22C55E.toInt()
    private const val GRADIENT_HIGH_YELLOW = 0xFFFACC15.toInt()
    private const val GRADIENT_HIGH_ORANGE = 0xFFF97316.toInt()
    private const val GRADIENT_LOW_PALE = 0xFFFCA5A5.toInt()
    private const val GRADIENT_LOW_DEEP = 0xFFE11D2E.toInt()

    private fun blend(from: Int, to: Int, t: Double): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun displayBgDescription(
        displayMgdl: Double?,
        profileFunction: ProfileFunction,
        preferences: Preferences,
        rh: ResourceHelper
    ): String =
        when {
            isDisplayLow(displayMgdl, profileFunction, preferences) ->
                rh.gs(app.aaps.core.ui.R.string.a11y_low)
            isDisplayHigh(displayMgdl, profileFunction, preferences) ->
                rh.gs(app.aaps.core.ui.R.string.a11y_high)
            displayMgdl == null -> ""
            else ->
                rh.gs(app.aaps.core.ui.R.string.a11y_inrange)
        }

    private fun isFreshGlucoseStatus(glucoseStatus: GlucoseStatus?, now: Long): Boolean {
        val gs = glucoseStatus ?: return false
        return gs.date >= now - GLUCOSE_STATUS_FRESH_MS
    }
}
