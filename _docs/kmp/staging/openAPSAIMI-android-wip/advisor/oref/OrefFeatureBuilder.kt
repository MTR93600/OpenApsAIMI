package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.data.model.GV
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileSnapshot
import java.util.Calendar
import java.util.TimeZone

/**
 * Builds the raw feature vector for one CGM sample aligned with the nearest prior APS decision.
 */
object OrefFeatureBuilder {

    fun directionNum(arrow: TrendArrow): Double = when (arrow) {
        TrendArrow.DOUBLE_DOWN -> -2.0
        TrendArrow.TRIPLE_DOWN -> -2.0
        TrendArrow.SINGLE_DOWN -> -1.5
        TrendArrow.FORTY_FIVE_DOWN -> -1.0
        TrendArrow.FLAT -> 0.0
        TrendArrow.FORTY_FIVE_UP -> 1.0
        TrendArrow.SINGLE_UP -> 1.5
        TrendArrow.DOUBLE_UP -> 2.0
        TrendArrow.TRIPLE_UP -> 2.0
        TrendArrow.NONE -> 0.0
    }

    /**
     * @return feature array or null if this GV cannot be paired (no valid target / RT).
     */
    fun buildRow(
        gv: GV,
        aps: APSResult,
        profile: AimiProfileSnapshot,
    ): DoubleArray? {
        val rt = try {
            aps.rawData() as? RT
        } catch (_: Exception) {
            null
        } ?: return null

        val reason = rt.reason.toString()
        val parsed = OrefReasonParser.parse(reason)
        val hour = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = gv.timestamp
        }.get(Calendar.HOUR_OF_DAY)

        val target = rt.targetBG?.takeIf { it > 0 } ?: return null
        val isf = rt.variable_sens?.takeIf { it > 0 }
            ?: rt.isfMgdlForCarbs?.takeIf { it > 0 }
            ?: aps.variableSens?.takeIf { it > 0 }
            ?: return null

        val crFromProfile = aps.oapsProfileAimi?.carb_ratio
        val cr = crFromProfile?.takeIf { it > 0 }
            ?: OrefReasonParser.parseCrFromReason(reason)
            ?: profile.icRatio

        val sensRatio = rt.sensitivityRatio?.takeIf { it > 0 } ?: 1.0
        val threshold = target - 0.5 * (target - 40.0)

        val gs = aps.glucoseStatus
        val expectedDelta = when (gs) {
            is GlucoseStatusAIMI -> gs.shortAvgDelta
            else -> gs?.shortAvgDelta ?: gs?.delta ?: 0.0
        }
        val minDelta = when (gs) {
            is GlucoseStatusAIMI -> minOf(gs.delta, gs.shortAvgDelta, gs.longAvgDelta)
            else -> listOfNotNull(gs?.delta, gs?.shortAvgDelta, gs?.longAvgDelta).minOrNull() ?: 0.0
        }

        val iobTotal = aps.iob
        val bolusIob = if (iobTotal != null) (iobTotal.iob - iobTotal.basaliob).coerceAtLeast(0.0) else 0.0

        val (maxSmbCe, maxUamCe) = OrefReasonParser.parseMaxSmbMinutesFromConsole(rt.consoleError)
        val profileAimi = aps.oapsProfileAimi
        val maxSmb = maxSmbCe ?: profileAimi?.maxSMBBasalMinutes?.toDouble()
        val maxUam = maxUamCe ?: profileAimi?.maxUAMSMBBasalMinutes?.toDouble()

        val hasDyn = if (rt.runningDynamicIsf) 1.0 else 0.0
        val hasSmb = if (
            (rt.units ?: 0.0) > 0 ||
            reason.contains("Microbolusing", ignoreCase = true) ||
            reason.contains("microBolus", ignoreCase = true)
        ) 1.0 else 0.0
        val hasUam = if (
            reason.contains("UAMpredBG", ignoreCase = true) ||
            (reason.contains("UAM") && reason.contains("pred", ignoreCase = true)) ||
            profileAimi?.enableUAM == true
        ) 1.0 else 0.0

        val arr = DoubleArray(OrefModelFeatures.COUNT) { Double.NaN }
        fun set(name: String, v: Double?) {
            val idx = OrefModelFeatures.NAMES.indexOf(name)
            if (idx >= 0 && v != null && v.isFinite()) arr[idx] = v
        }

        set("cgm_mgdl", gv.value)
        set("sug_current_target", target)
        set("sug_ISF", isf)
        set("sug_CR", cr)
        set("sug_sensitivityRatio", sensRatio)
        set("sug_rate", rt.rate)
        set("sug_duration", rt.duration?.toDouble())
        set("sug_insulinReq", rt.insulinReq)
        set("sug_eventualBG", rt.eventualBG)
        set("sug_threshold", threshold)
        set("sug_expectedDelta", expectedDelta)
        set("sug_minDelta", minDelta)

        set("iob_iob", iobTotal?.iob)
        set("iob_basaliob", iobTotal?.basaliob)
        set("iob_bolusiob", bolusIob.takeIf { it > 0 })
        set("iob_activity", iobTotal?.activity)
        set("iob_netbasalinsulin", iobTotal?.netbasalinsulin)

        set("sug_COB", rt.COB ?: aps.mealData?.carbs)

        set("reason_Dev", parsed.dev)
        set("reason_BGI", parsed.bgi)
        set("reason_minGuardBG", parsed.minGuardBG)

        set("direction_num", directionNum(gv.trendArrow))
        set("hour", hour.toDouble())
        set("has_dynisf", hasDyn)
        set("has_smb", hasSmb)
        set("has_uam", hasUam)

        set("bg_above_target", gv.value - target)

        val profIsf = profile.isf.takeIf { it > 0 } ?: isf
        val isfRatio = isf / profIsf
        set("isf_ratio", isfRatio)

        set("sr_deviation", kotlin.math.abs(sensRatio - 1.0))
        set("dynisf_x_sr", hasDyn * sensRatio)
        set("dynisf_x_isf_ratio", hasDyn * isfRatio)

        set("maxSMBBasalMinutes", maxSmb)
        set("maxUAMSMBBasalMinutes", maxUam)
        set("sug_smb_units", rt.units)

        // iob_pct_max filled in second pass
        return arr
    }

    /** Second-pass features (needs max IOB across cohort). */
    fun applyDerivedMaxIob(features: DoubleArray, inferredMaxIob: Double) {
        val iobIdx = OrefModelFeatures.NAMES.indexOf("iob_iob")
        val pctIdx = OrefModelFeatures.NAMES.indexOf("iob_pct_max")
        if (iobIdx < 0 || pctIdx < 0) return
        val iob = features[iobIdx]
        if (!iob.isFinite()) return
        val denom = maxOf(inferredMaxIob, 1.0)
        features[pctIdx] = iob / denom
    }
}
