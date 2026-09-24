package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.ISF.ObservedSensitivityMeter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/** One 5-minute point of the 30-minute window, as the prompt shows it. */
data class ContextPoint(
    val minutesAgo: Int,
    val bgMgdl: Double,
    val deltaMgdl5m: Double,
    val iobU: Double,
    val commandIsfMgdl: Double,
    val workingTargetMgdl: Double?,
    val runningBasalUph: Double,
)

/**
 * The 30 minutes the profile checker is shown, and the only numbers its claims are checked against.
 *
 * Every value is computed here, in Kotlin, from the loop's own ticks plus the database for insulin
 * and carbs. The model is asked to copy six of them back; `AuditorProfileFactorValidator` compares
 * each one with the value in this object. That is the whole protection against an answer written
 * about a window that never happened.
 *
 * @param complete true when both ends of the window and at least five of the seven points exist. An
 *   incomplete window refuses every proposal.
 */
data class AuditorProfileContext(
    val contextBuiltAtMs: Long,
    val points: List<ContextPoint?>,
    val isfProfileStaticMgdl: Double?,
    val isfDynamicMgdl: Double?,
    val isfCommandMgdl: Double?,
    val isfCommandPreFloorMgdl: Double?,
    val isfWorkingMgdl: Double?,
    val isfCommandOverProfile: Double?,
    val isfOnProfileFloor: Boolean,
    val isfFloorMultiplier: Double?,
    val targetProfileMgdl: Double?,
    val tempTargetActive: Boolean,
    val targetWorkingMgdl: Double?,
    val bgStartMgdl: Double?,
    val bgEndMgdl: Double?,
    val bgMinMgdl: Double?,
    val bgMaxMgdl: Double?,
    val iobStartU: Double?,
    val iobEndU: Double?,
    val bolusU: Double,
    val netBasalU: Double,
    val insulinDeliveredU: Double,
    val insulinAbsorbedU: Double?,
    val impliedIsfMgdlPerU: Double?,
    val carbsG: Double,
    val cobNowG: Double,
    val mealModeName: String?,
    val mealCertaintyLevel: String?,
    val mealSupport: Boolean,
    val minBg75mMgdl: Double,
    val cgmNoise: Double,
    val completeness: Int,
    val complete: Boolean,
) {

    /**
     * The smallest sensitivity the engine used in this window, mg/dL per U.
     *
     * The resistance test compares the measured sensitivity against this one. Taking the smallest of
     * the three levels is the careful choice: it is the hardest value to beat, so weak evidence
     * cannot pass.
     */
    val engineIsfMgdl: Double?
        get() = listOfNotNull(isfCommandMgdl, isfDynamicMgdl, isfWorkingMgdl)
            .filter { it.isFinite() && it > 0.0 }
            .minOrNull()

    /**
     * The JSON the model is shown, and the same JSON that goes into the proposal record.
     *
     * It is kept under [AuditorProfileFactorLimits.CONTEXT_JSON_MAX_CHARS]. When the rows are too
     * long the per-row extras go first and the summary numbers stay, because the claims are checked
     * against the summary, never against a row.
     */
    fun toPromptJson(): JSONObject {
        var json = buildJson(withRunningBasal = true, withCommandIsf = true)
        if (json.toString().length <= AuditorProfileFactorLimits.CONTEXT_JSON_MAX_CHARS) return json
        json = buildJson(withRunningBasal = false, withCommandIsf = true)
        if (json.toString().length <= AuditorProfileFactorLimits.CONTEXT_JSON_MAX_CHARS) return json
        return buildJson(withRunningBasal = false, withCommandIsf = false)
    }

    private fun buildJson(withRunningBasal: Boolean, withCommandIsf: Boolean): JSONObject =
        JSONObject().apply {
            put("window_minutes", 30)
            put(
                "isf_mgdl_per_u",
                JSONObject().apply {
                    put("profile_static", numberOrNull(isfProfileStaticMgdl, 1))
                    put("dynamic", numberOrNull(isfDynamicMgdl, 1))
                    put("command", numberOrNull(isfCommandMgdl, 1))
                    put("command_pre_floor", numberOrNull(isfCommandPreFloorMgdl, 1))
                    put("dose_working", numberOrNull(isfWorkingMgdl, 1))
                    put("command_over_profile", numberOrNull(isfCommandOverProfile, 2))
                    put("on_profile_floor", isfOnProfileFloor)
                    put("floor_multiplier", numberOrNull(isfFloorMultiplier, 2))
                },
            )
            put(
                "target_mgdl",
                JSONObject().apply {
                    put("profile_or_temp", numberOrNull(targetProfileMgdl, 1))
                    put("temp_target_active", tempTargetActive)
                    put("working", numberOrNull(targetWorkingMgdl, 1))
                },
            )
            put(
                "series_5min",
                JSONArray().apply {
                    points.forEach { point ->
                        if (point == null) {
                            put(JSONObject.NULL)
                        } else {
                            put(
                                JSONObject().apply {
                                    put("min_ago", point.minutesAgo)
                                    put("bg", roundTo(point.bgMgdl, 1))
                                    put("delta", roundTo(point.deltaMgdl5m, 1))
                                    put("iob", roundTo(point.iobU, 2))
                                    if (withCommandIsf) put("command_isf", roundTo(point.commandIsfMgdl, 1))
                                    put("working_target", numberOrNull(point.workingTargetMgdl, 1))
                                    if (withRunningBasal) put("running_basal", roundTo(point.runningBasalUph, 2))
                                },
                            )
                        }
                    }
                },
            )
            put(
                "insulin_u",
                JSONObject().apply {
                    put("bolus", roundTo(bolusU, 2))
                    put("net_basal", roundTo(netBasalU, 2))
                    put("delivered", roundTo(insulinDeliveredU, 2))
                    put("absorbed", numberOrNull(insulinAbsorbedU, 2))
                },
            )
            put("implied_isf_mgdl_per_u", numberOrNull(impliedIsfMgdlPerU, 1))
            put("bg_start", numberOrNull(bgStartMgdl, 1))
            put("bg_end", numberOrNull(bgEndMgdl, 1))
            put("bg_min", numberOrNull(bgMinMgdl, 1))
            put("bg_max", numberOrNull(bgMaxMgdl, 1))
            put("iob_start", numberOrNull(iobStartU, 2))
            put("iob_end", numberOrNull(iobEndU, 2))
            put("carbs_g", roundTo(carbsG, 1))
            put("cob_g", roundTo(cobNowG, 1))
            put("meal_mode", mealModeName ?: JSONObject.NULL)
            put("meal_certainty", mealCertaintyLevel ?: JSONObject.NULL)
            put("bg_min_75min", roundTo(minBg75mMgdl, 1))
            put("cgm_noise", roundTo(cgmNoise, 1))
            put("points_present", completeness)
        }

    private fun numberOrNull(value: Double?, decimals: Int): Any =
        value?.takeIf { it.isFinite() }?.let { roundTo(it, decimals) } ?: JSONObject.NULL

    private fun roundTo(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return 0.0
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return round(value * scale) / scale
    }
}

/**
 * Turns a list of loop ticks into the 30-minute window the profile checker is shown.
 *
 * Pure on purpose: it takes the database totals as plain numbers, so the whole window can be built
 * and checked in a unit test without a database.
 */
object AuditorProfileContextBuilder {

    /**
     * @param ticks the ring plus the audited tick, oldest first. Ticks at 1 minute and at 5 minutes
     *   both work: each bucket keeps the last tick that falls in it.
     * @param nowMs the timestamp of the audited tick. Every age is measured from it.
     * @param bolusU boluses delivered in the window, from the database, not from any decided value.
     * @param carbsG carbs entered in the window, from the database.
     */
    fun build(
        ticks: List<AuditorTickFact>,
        nowMs: Long,
        bolusU: Double,
        carbsG: Double,
        mealModeName: String?,
        mealCertaintyLevel: String?,
        mealSupport: Boolean,
        minBg75mMgdl: Double,
        cgmNoise: Double,
    ): AuditorProfileContext {
        val windowTicks = inWindow(ticks, nowMs)
        val buckets = bucketFacts(ticks, nowMs)
        val points: List<ContextPoint?> = buckets.map { fact ->
            fact?.let {
                // The real age of the tick, not the age of the bucket it was slotted into. A tick
                // can sit up to half a bucket away from the bucket centre, and the model reasons
                // about rates from this label.
                ContextPoint(
                    minutesAgo = ((nowMs - it.timestampMs) / 60_000.0).roundToInt(),
                    bgMgdl = it.bgMgdl,
                    deltaMgdl5m = it.deltaMgdl5m,
                    iobU = it.iobU,
                    commandIsfMgdl = it.commandIsfRawMgdl,
                    workingTargetMgdl = it.workingTargetRawMgdl,
                    runningBasalUph = it.runningBasalUph,
                )
            }
        }

        val first = buckets.firstOrNull { it != null }
        val last = buckets.lastOrNull { it != null }
        val current = windowTicks.lastOrNull()
        val completeness = buckets.count { it != null }
        val complete = buckets.first() != null && buckets.last() != null &&
            completeness >= AuditorProfileFactorLimits.CONTEXT_MIN_POINTS

        // The basal is integrated from the same moment the boluses are read from, so the two halves
        // of `delivered` always cover exactly the same minutes.
        val netBasalU = netBasal(windowTicks.filter { first == null || it.timestampMs >= first.timestampMs })
        val deliveredU = bolusU + netBasalU
        val absorbedU = if (first != null && last != null) first.iobU - last.iobU + deliveredU else null
        val impliedIsf = if (
            first != null && last != null && absorbedU != null &&
            absorbedU >= ObservedSensitivityMeter.MIN_ABSORBED_U
        ) -(last.bgMgdl - first.bgMgdl) / absorbedU else null

        return AuditorProfileContext(
            contextBuiltAtMs = nowMs,
            points = points,
            isfProfileStaticMgdl = current?.profileIsfStaticMgdl,
            isfDynamicMgdl = current?.dynamicIsfRawMgdl,
            isfCommandMgdl = current?.commandIsfRawMgdl,
            isfCommandPreFloorMgdl = current?.commandIsfPreFloorMgdl,
            isfWorkingMgdl = current?.workingIsfRawMgdl,
            isfCommandOverProfile = commandOverProfile(current),
            isfOnProfileFloor = onProfileFloor(current),
            isfFloorMultiplier = current?.commandFloorMultiplier,
            targetProfileMgdl = current?.profileTargetMgdl,
            tempTargetActive = current?.tempTargetActive == true,
            targetWorkingMgdl = current?.workingTargetRawMgdl,
            bgStartMgdl = first?.bgMgdl,
            bgEndMgdl = last?.bgMgdl,
            bgMinMgdl = buckets.filterNotNull().minOfOrNull { it.bgMgdl },
            bgMaxMgdl = buckets.filterNotNull().maxOfOrNull { it.bgMgdl },
            iobStartU = first?.iobU,
            iobEndU = last?.iobU,
            bolusU = bolusU,
            netBasalU = netBasalU,
            insulinDeliveredU = deliveredU,
            insulinAbsorbedU = absorbedU,
            impliedIsfMgdlPerU = impliedIsf,
            carbsG = carbsG,
            cobNowG = current?.cobG ?: 0.0,
            mealModeName = mealModeName,
            mealCertaintyLevel = mealCertaintyLevel,
            mealSupport = mealSupport,
            minBg75mMgdl = minBg75mMgdl,
            cgmNoise = cgmNoise,
            completeness = completeness,
            complete = complete,
        )
    }

    /**
     * The timestamp the window really starts at, or null when no tick falls in it.
     *
     * The boluses and the carbs of the window are read from the database between this moment and the
     * audited tick, so the collector asks for it before it queries.
     */
    fun windowStartMs(ticks: List<AuditorTickFact>, nowMs: Long): Long? =
        bucketFacts(ticks, nowMs).firstOrNull { it != null }?.timestampMs

    /**
     * The ticks of the window, oldest first.
     *
     * The edge is the oldest bucket CENTRE plus half a bucket, 32.5 minutes, not 35. That is exactly
     * the set of ticks the nearest-bucket rule below can place: a tick older than that would be
     * rounded past the oldest bucket and dropped anyway, and keeping it would let a 35-minute window
     * be reported to the model as a 30-minute one.
     */
    private fun inWindow(ticks: List<AuditorTickFact>, nowMs: Long): List<AuditorTickFact> {
        val bucketMs = AuditorProfileFactorLimits.CONTEXT_BUCKET_MS
        val windowMs = (AuditorProfileFactorLimits.CONTEXT_POINTS - 1) * bucketMs + bucketMs / 2
        return ticks.filter { it.timestampMs <= nowMs && it.timestampMs > nowMs - windowMs }
            .sortedBy { it.timestampMs }
    }

    /**
     * One tick per 5-minute bucket, oldest first, null where the loop has none.
     *
     * Each bucket keeps the LAST tick that falls in it, so a 1-minute loop and a 5-minute loop give
     * the same seven points.
     */
    private fun bucketFacts(ticks: List<AuditorTickFact>, nowMs: Long): List<AuditorTickFact?> {
        val bucketMs = AuditorProfileFactorLimits.CONTEXT_BUCKET_MS
        val pointCount = AuditorProfileFactorLimits.CONTEXT_POINTS
        val windowTicks = inWindow(ticks, nowMs)
        // Each tick goes to the bucket it is CLOSEST to, not to the one whose fixed edges happen to
        // contain it. A loop tick lands a few seconds off the five-minute grid — measured drift on the
        // packages is 0 to 2 minutes per tick — and with fixed edges two neighbouring ticks fall in one
        // bucket and leave the next one empty. That cost two of seven points on an ordinary 5-minute
        // window, and an incomplete window refuses every proposal, so the whole gesture would have
        // been flaky on exactly the cadence most people have.
        val buckets = arrayOfNulls<AuditorTickFact>(pointCount)
        for (tick in windowTicks) {
            val stepsAgo = ((nowMs - tick.timestampMs).toDouble() / bucketMs).roundToInt()
            val index = pointCount - 1 - stepsAgo
            if (index in 0 until pointCount) buckets[index] = tick
        }
        return buckets.toList()
    }

    /**
     * Insulin given by the pump above (or below) the profile basal, U.
     *
     * Each tick's running rate is held until the next tick, which is what the pump really did.
     */
    private fun netBasal(ticks: List<AuditorTickFact>): Double {
        var total = 0.0
        for (index in 1 until ticks.size) {
            val previous = ticks[index - 1]
            val hours = (ticks[index].timestampMs - previous.timestampMs) / 3_600_000.0
            if (hours <= 0.0) continue
            total += (previous.runningBasalUph - previous.profileBasalUph) * hours
        }
        return total
    }

    private fun commandOverProfile(fact: AuditorTickFact?): Double? {
        val profileIsf = fact?.profileIsfStaticMgdl ?: return null
        if (!profileIsf.isFinite() || profileIsf <= 0.0) return null
        return fact.commandIsfRawMgdl / profileIsf
    }

    private fun onProfileFloor(fact: AuditorTickFact?): Boolean {
        val preFloor = fact?.commandIsfPreFloorMgdl ?: return false
        if (!preFloor.isFinite() || !fact.commandIsfRawMgdl.isFinite()) return false
        return fact.commandIsfRawMgdl > preFloor + AuditorProfileFactorLimits.FLOOR_MATCH_TOLERANCE_MGDL
    }
}

/** True when the two numbers are the same inside [tolerance]. */
internal fun closeEnough(left: Double, right: Double, tolerance: Double): Boolean =
    abs(left - right) <= tolerance
