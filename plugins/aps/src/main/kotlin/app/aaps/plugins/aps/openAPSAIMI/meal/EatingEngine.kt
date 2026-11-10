package app.aaps.plugins.aps.openAPSAIMI.meal

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The eating mode ("Я ем"). The user presses a button before or when he starts to eat. He does not enter carbohydrates.
 *
 * What the engine does:
 *  1. Gives a small first dose (the start dose), so the insulin starts to work before the glucose rises.
 *  2. Estimates the eaten carbohydrates in real time from the rise that insulin on board can not explain.
 *  3. Keeps one budget for the whole window: the doses of the normal SMB logic are cut so that all insulin given in the
 *     window does not pass the estimate (and a hard limit), so the button and AIMI never add up to a double dose.
 *  4. Brakes the dose when the rise slows down, and lets it go again when a new rise starts.
 *  5. After the meal it checks the result (peak, lowest value) and corrects a multiplier of the estimate, slowly and
 *     only inside a safe range.
 *
 * All state is kept in SharedPreferences, so the screens and the algorithm see the same values.
 */
class EatingEngine(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Everything the algorithm gives to the engine on one tick. Glucose values are in mg/dL. */
    data class Input(
        val nowMs: Long,
        val bgMgdl: Double,
        /** Change over the last 5 minutes. */
        val deltaMgdl5: Double,
        /** Average 5-minute change over the last 15 minutes. */
        val shortAvgMgdl5: Double,
        val isfMgdlPerU: Double,
        /** Insulin activity, units per minute. */
        val iobActivityUPerMin: Double,
        val uPerXe: Double,
        val startDoseU: Double,
        val maxWindowU: Double,
        val stopForecastMgdl: Double,
        val eventualBgMgdl: Double,
        /** All insulin (SMB and manual) given since the window started. */
        val deliveredU: Double,
        val learningFrozen: Boolean,
        /** The largest single bolus since the window started: the start dose counts as given only when one bolus reached it. */
        val largestBolusU: Double = 0.0,
    )

    /** What the engine tells the algorithm. */
    data class Output(
        val ageMin: Double,
        val estimateG: Double,
        val neededU: Double,
        val budgetRemainingU: Double,
        val startRemainingU: Double,
        /** The part of the planned dose (by the chosen size) that is due now and not given yet. 0 without a chosen size. */
        val planDueU: Double = 0.0,
        /** Insulin that the whole meal calls for (the larger of the estimate, the plan and the start dose), and the share of the window left. */
        val mealU: Double = 0.0,
        val remainingFrac: Double = 0.0,
        val brake: Double,
        /** Carbohydrates counted so far (grams, never goes down) and the part already covered by the insulin given. */
        val countedG: Double = 0.0,
        val coveredG: Double = 0.0,
        /** All insulin given since the window started (the same number the budget uses). */
        val deliveredU: Double = 0.0,
    ) {
        /** The SMB that may go out: cut by the brake and by the budget. The start dose is not braked. */
        fun allowedSmbU(requestedU: Double): Double {
            if (requestedU <= 0.0) return 0.0
            val braked = requestedU * brake
            val startPart = min(requestedU, startRemainingU)
            return min(max(braked, startPart), budgetRemainingU).coerceAtLeast(0.0)
        }
    }

    // ---- buttons ----

    fun isActive(): Boolean = sp.getBoolean(ACTIVE, false)

    /**
     * Starts the window by itself (no button): a confirmed meal rise was seen. Size is Auto, no fat flag, and the start
     * dose is only half of the setting, because a false start must stay cheap. Returns false when a window is running.
     */
    fun autoStart(nowMs: Long, bgMgdl: Double, fullStartDose: Boolean = false, afterHypo: Boolean = false): Boolean {
        if (isActive()) return false
        sp.edit().putBoolean(FAT, false).apply()
        if (!start(nowMs, bgMgdl, SIZE_AUTO)) return false
        // A fast rise gets the whole start dose; a slow one or a meal right after a hypo gets half.
        // After a hypo the first RESCUE_G grams are the rescue carbs: no insulin is given for them.
        sp.edit()
            .putBoolean(AUTO_START, !fullStartDose || afterHypo)
            .putFloat(RESCUE, if (afterHypo) RESCUE_G.toFloat() else 0f)
            .apply()
        return true
    }

    /**
     * A new meal rise while the window runs (a second course, a dessert, eating again): the window is made longer, so
     * the new carbs are counted and get insulin. The start dose is not given again, and everything given so far stays
     * counted. Returns true when the window was made longer.
     */
    fun extendForNewRise(nowMs: Long): Boolean {
        if (!isActive()) return false
        val start = sp.getLong(START_MS, 0L)
        if (nowMs - start < EXTEND_MIN_AGE_MIN * 60_000.0) return false
        val newEnd = nowMs + (if (fat()) EXTEND_FAT_MIN else EXTEND_MIN).toLong() * 60_000L
        if (newEnd <= windowEndMs() + 5 * 60_000L) return false
        sp.edit().putLong(EXTEND_TO, newEnd).putLong(END_MS, newEnd).apply()
        return true
    }

    /** End of the window: the normal length from the start, or later when a new rise made it longer. */
    fun windowEndMs(): Long {
        val start = sp.getLong(START_MS, 0L)
        val normal = start + (windowMin(size()) * 60_000.0).toLong()
        return max(normal, sp.getLong(EXTEND_TO, 0L))
    }

    /**
     * Time left (ms) in which a new start of the mode counts as the same meal: the start dose is not given again and the
     * insulin given so far stays counted. 0 when no meal was started in the last 30 minutes.
     */
    fun restartGraceLeftMs(nowMs: Long): Long {
        val start = sp.getLong(START_MS, 0L)
        if (start <= 0L) return 0L
        return (start + RESTART_GRACE_MS - nowMs).coerceAtLeast(0L)
    }

    fun autoStarted(): Boolean = sp.getBoolean(AUTO_START, false)

    /** The switch of the automatic start (Assistant). Off by default: only the button starts the mode. */
    fun autoStartEnabled(): Boolean = sp.getBoolean(AUTO_START_ENABLED, false)

    fun setAutoStartEnabled(enabled: Boolean) { sp.edit().putBoolean(AUTO_START_ENABLED, enabled).apply() }

    /**
     * "Fast rise (meal)" from the Assistant, in mg/dL per 5 minutes. Default 5 mg/dL (0.28 mmol/L).
     * Used for: the steady rise check (auto start, exit after a hypo), the full basal range without a meal mode and
     * the full High BG Max SMB in eating mode.
     */
    fun fastRiseMgdl5(): Double =
        sp.getFloat(FAST_RISE_MGDL5, FAST_RISE_DEFAULT_MGDL5.toFloat()).toDouble().coerceIn(FAST_RISE_MIN_MGDL5, FAST_RISE_MAX_MGDL5)

    fun setFastRiseMgdl5(value: Double) {
        sp.edit().putFloat(FAST_RISE_MGDL5, value.coerceIn(FAST_RISE_MIN_MGDL5, FAST_RISE_MAX_MGDL5).toFloat()).apply()
    }

    /** Starts the window. Returns false when a window is already running. */
    fun start(nowMs: Long, bgMgdl: Double, size: Int): Boolean {
        if (isActive()) return false
        // Started again within 30 minutes of the last start (cancelled by mistake, another size): it is the same meal.
        // The old start time is kept, so the start dose already given and all insulin since then stay counted.
        val previousStart = sp.getLong(START_MS, 0L)
        val sameMeal = previousStart > 0L && nowMs - previousStart in 0 until RESTART_GRACE_MS
        val startMs = if (sameMeal) previousStart else nowMs
        if (sp.getBoolean(PENDING, false) && !sameMeal) finalizeMeal(nowMs, partial = true, frozen = true)
        val legacyFat = size == SIZE_FAT
        sp.edit()
            .putBoolean(ACTIVE, true)
            .putBoolean(PENDING, true)
            .putLong(START_MS, startMs)
            .putLong(EXTEND_TO, 0L)
            .putFloat(RESCUE, 0f)
            .putInt(SIZE, if (legacyFat) SIZE_AUTO else size.coerceIn(0, SIZE_AUTO))
            .putBoolean(FAT, fat() || legacyFat)
            .putBoolean(AUTO_START, false)
            .putFloat(START_BG, bgMgdl.toFloat())
            .putFloat(ABSORBED_G, 0f)
            .putLong(LAST_TICK, 0L)
            .putFloat(EST_G, 0f)
            .putFloat(EST_MAX_G, 0f)
            .putFloat(COUNTED_G, 0f)
            .putFloat(COVERED_G, 0f)
            .putFloat(NEED_U, 0f)
            .putFloat(DELIV_U, 0f)
            .putFloat(BRAKE, 1f)
            .putFloat(PEAK, 0f)
            .putFloat(MIN, 1000f)
            .putFloat(PEAK_ACT, 0f)
            .putFloat(NEED_MAX_U, 0f)
            .putFloat(PEAK_ACT_MIN, 0f)
            .putFloat(EST_MAX_MIN, 0f)
            .apply()
        sp.edit().putLong(END_MS, windowEndMs()).apply()
        return true
    }

    /** The user changed his mind: the window is dropped and nothing is learned from it. */
    fun cancel() {
        if (sp.getBoolean(PENDING, false)) {
            // The window is saved in the statistics as "cancelled" (shown in red) and is never used for learning.
            finalizeMeal(System.currentTimeMillis(), partial = true, frozen = true, cancelled = true)
        } else {
            sp.edit().putBoolean(ACTIVE, false).apply()
        }
    }

    fun setSize(size: Int) {
        sp.edit().putInt(SIZE, if (size == SIZE_FAT) SIZE_AUTO else size.coerceIn(0, SIZE_AUTO)).apply()
    }

    fun size(): Int = sp.getInt(SIZE, SIZE_AUTO).let { if (it == SIZE_FAT) SIZE_AUTO else it }

    fun fat(): Boolean = sp.getBoolean(FAT, false) || sp.getInt(SIZE, SIZE_AUTO) == SIZE_FAT

    fun setFat(enabled: Boolean) { sp.edit().putBoolean(FAT, enabled).apply() }

    /** The user tells how many bread units he really ate: the last meal gets a true value for learning. */
    fun setRealXe(xe: Double) {
        sp.edit().putFloat(REAL_XE_PENDING, xe.toFloat()).apply()
        val meals = readMeals()
        if (meals.length() > 0) {
            val last = meals.getJSONObject(meals.length() - 1)
            if (last.optDouble("realXe", -1.0) < 0.0) {
                last.put("realXe", xe)
                saveMeals(meals)
            }
        }
    }

    /** Length of the window in minutes: 3 hours, and 5 hours for a fat meal. */
    fun windowMin(size: Int): Double = if (fat() || size == SIZE_FAT) WINDOW_MIN_FAT else WINDOW_MIN

    private fun trackMs(): Long = (windowEndMs() - sp.getLong(START_MS, 0L)) + 20L * 60_000L

    // ---- tick ----

    /** Called once per algorithm tick. Returns null when no window is active. */
    fun tick(i: Input): Output? {
        if (isActive() && sp.getFloat(START_BG, 0f) <= 0f) sp.edit().putFloat(START_BG, i.bgMgdl.toFloat()).apply()
        trackExtremes(i)
        finalizeIfDue(i)
        if (!isActive()) return null
        val start = sp.getLong(START_MS, 0L)
        val ageMin = (i.nowMs - start) / 60_000.0
        val sizeNow = size()
        val fat = fat()
        val endMs = windowEndMs()
        if (ageMin < 0.0 || i.nowMs >= endMs) {
            sp.edit().putBoolean(ACTIVE, false).apply()
            return null
        }
        if (i.bgMgdl < CLOSE_LOW_MGDL) {
            // A low glucose ends the window; the result is still checked later.
            sp.edit().putBoolean(ACTIVE, false).apply()
            return null
        }

        // 1) carbohydrates absorbed so far, from the rise that the insulin does not explain
        val gPerU = 10.0 / i.uPerXe.coerceAtLeast(0.1)
        val csf = (i.isfMgdlPerU / gPerU).coerceAtLeast(1.0) // mg/dL per gram
        val insulinEffect5 = (-i.iobActivityUPerMin * i.isfMgdlPerU * 5.0).coerceIn(-30.0, 0.0)
        // The deviation is kept with its sign: small steady absorption (slow and fat food) is not thrown away as noise,
        // and the sensor noise up and down cancels itself. A fall faster than the insulin explains takes back only what
        // was counted before (the count never goes under 0) and at most NOISE_MGDL_5 per 5 minutes, so one dip of the
        // sensor does not wipe the meal.
        val rawDeviation5 = i.deltaMgdl5 - insulinEffect5
        val deviation5 = if (rawDeviation5 >= 0.0) rawDeviation5 else rawDeviation5.coerceAtLeast(-NOISE_MGDL_5)
        val rateGPerMin = deviation5 / 5.0 / csf
        val lastTick = sp.getLong(LAST_TICK, 0L)
        val dtMin = if (lastTick == 0L) 0.0 else ((i.nowMs - lastTick) / 60_000.0).coerceIn(0.0, 5.0)
        val absorbed = (sp.getFloat(ABSORBED_G, 0f) + (rateGPerMin * dtMin).toFloat()).coerceAtLeast(0f)

        // 2) estimate: absorbed plus what is still coming while the rise goes on
        val rising = i.deltaMgdl5 > 0.0 && (i.shortAvgMgdl5 <= 0.5 || i.deltaMgdl5 >= i.shortAvgMgdl5 * 0.9)
        val projected = if (rising) rateGPerMin.coerceAtLeast(0.0) * (if (fat) PROJECT_MIN_FAT else PROJECT_MIN) else 0.0
        val k = multiplier()
        // After a hypo the first grams are the rescue carbs: they only bring the glucose back and get no insulin.
        val rescueG = sp.getFloat(RESCUE, 0f).toDouble()
        val mealAbsorbed = (absorbed - rescueG).coerceAtLeast(0.0)
        val estimateG = (mealAbsorbed + projected) * k
        val neededU = estimateG / gPerU

        // 3) one budget for the window
        val sizeShare = SIZE_SHARE[sizeNow]
        val capU = i.maxWindowU * sizeShare
        // A fat meal starts with half of the start dose; the rest follows the slow rise.
        // The start dose is fixed: it is the setting, for every meal type.
        val startDoseU = if (sp.getBoolean(AUTO_START, false)) i.startDoseU * AUTO_START_SHARE else i.startDoseU
        // The limit of the window is a fuse, not a wall: while the glucose is high and still rising, the dose follows the
        // estimate and the other safety steps decide.
        val escape = i.bgMgdl >= ESCAPE_MGDL && i.deltaMgdl5 > 0.0
        // The plan: with a chosen size (small, medium, large) the insulin of the meal is planned before the glucose rises.
        // The planned share grows with time: over 90 minutes, and for a fat meal from minute 20 over 220 minutes.
        val planTotalU = if (sizeNow in 0..2) capU else 0.0
        val planRamp = if (fat) ((ageMin - PLAN_FAT_DELAY_MIN) / PLAN_FAT_RAMP_MIN).coerceIn(0.0, 1.0)
        else (ageMin / PLAN_RAMP_MIN).coerceIn(0.0, 1.0)
        // The faster the glucose rises, the more of the plan is due at once (the time ramp is the lower limit).
        val planRiseShare = if (i.deltaMgdl5 > PLAN_RISE_FROM) ((i.deltaMgdl5 - PLAN_RISE_FROM) / (PLAN_RISE_FULL - PLAN_RISE_FROM)).coerceIn(0.0, 1.0) else 0.0
        val planNowU = planTotalU * max(planRamp, planRiseShare)
        val totalU = if (escape) max(max(neededU, startDoseU), planNowU) else min(max(max(neededU, startDoseU), planNowU), capU)
        val remaining = (totalU - i.deliveredU).coerceAtLeast(0.0)
        // The start dose is fixed and goes out whole, as one bolus. Small SMB and temp basal before it do not cut it
        // (9 Nov 11:00: 1.5 U set, 0.33 U of small doses before, so only 1.1 U was asked and 1.0 U given).
        // It counts as given when one bolus reached it (minus the pump step rounding).
        val startRemaining = if (ageMin <= START_PHASE_MIN && i.bgMgdl >= START_MIN_BG_MGDL &&
            i.largestBolusU < startDoseU - START_DONE_TOLERANCE_U
        ) {
            startDoseU
        } else {
            0.0
        }

        // 4) brake when the rise slows down; a new rise (delta back at the average) opens it again
        var brake = 1.0
        if (i.deltaMgdl5 <= 0.0) {
            brake = 0.0
        } else if (i.shortAvgMgdl5 > 0.5 && i.deltaMgdl5 < i.shortAvgMgdl5 * 0.9) {
            brake = (i.deltaMgdl5 / i.shortAvgMgdl5).coerceIn(0.2, 1.0)
        }
        if (brake < 1.0 && i.eventualBgMgdl.isFinite() && i.eventualBgMgdl < i.stopForecastMgdl) brake = 0.0

        val countedG = mealAbsorbed * k
        val coveredG = i.deliveredU * gPerU
        sp.edit()
            .putFloat(ABSORBED_G, absorbed)
            .putLong(LAST_TICK, i.nowMs)
            .putFloat(EST_G, estimateG.toFloat())
            // Minute of the last growth of the counted carbs (the count never goes down).
            .putFloat(EST_MAX_MIN, if (countedG.toFloat() > sp.getFloat(COUNTED_G, 0f) + 0.05f) ageMin.toFloat() else sp.getFloat(EST_MAX_MIN, 0f))
            .putFloat(EST_MAX_G, max(sp.getFloat(EST_MAX_G, 0f), estimateG.toFloat()))
            .putFloat(COUNTED_G, countedG.toFloat())
            .putFloat(COVERED_G, coveredG.toFloat())
            .putFloat(NEED_U, neededU.toFloat())
            // Insulin that the counted carbs need. The count never goes down, so this is the total for the meal.
            .putFloat(NEED_MAX_U, max(sp.getFloat(NEED_MAX_U, 0f), (countedG / gPerU).toFloat()))
            .putFloat(DELIV_U, i.deliveredU.toFloat())
            .putFloat(BRAKE, brake.toFloat())
            .apply()

        return Output(
            ageMin = ageMin,
            estimateG = estimateG,
            neededU = neededU,
            budgetRemainingU = remaining,
            startRemainingU = min(startRemaining, remaining),
            planDueU = min((planNowU - i.deliveredU).coerceAtLeast(0.0), remaining),
            mealU = max(max(neededU, startDoseU), planNowU),
            remainingFrac = ((endMs - i.nowMs) / (endMs - start).toDouble().coerceAtLeast(1.0)).coerceIn(0.0, 1.0),
            brake = brake,
            countedG = countedG,
            coveredG = coveredG,
            deliveredU = i.deliveredU,
        )
    }

    private fun trackExtremes(i: Input) {
        if (!sp.getBoolean(PENDING, false)) return
        val start = sp.getLong(START_MS, 0L)
        if (i.nowMs < start || i.nowMs > start + trackMs()) return
        val e = sp.edit()
        e.putFloat(LAST_BG, i.bgMgdl.toFloat())
        if (i.bgMgdl > sp.getFloat(PEAK, 0f)) e.putFloat(PEAK, i.bgMgdl.toFloat())
        if (i.bgMgdl < sp.getFloat(MIN, 1000f)) e.putFloat(MIN, i.bgMgdl.toFloat())
        // Peak of the insulin action (units per hour) and the minute when it was reached.
        val actUph = (i.iobActivityUPerMin * 60.0).toFloat()
        if (actUph > sp.getFloat(PEAK_ACT, 0f)) {
            e.putFloat(PEAK_ACT, actUph)
            e.putFloat(PEAK_ACT_MIN, ((i.nowMs - start) / 60_000.0).toFloat())
        }
        e.apply()
    }

    private fun finalizeIfDue(i: Input) {
        if (!sp.getBoolean(PENDING, false)) return
        val start = sp.getLong(START_MS, 0L)
        if (i.nowMs >= start + trackMs()) finalizeMeal(i.nowMs, partial = false, frozen = i.learningFrozen)
    }

    // ---- result and learning ----

    private fun finalizeMeal(nowMs: Long, partial: Boolean, frozen: Boolean, cancelled: Boolean = false) {
        val start = sp.getLong(START_MS, 0L)
        val peak = sp.getFloat(PEAK, 0f).toDouble()
        val low = sp.getFloat(MIN, 0f).toDouble()
        val estG = max(sp.getFloat(EST_MAX_G, 0f), max(sp.getFloat(EST_G, 0f), sp.getFloat(COUNTED_G, 0f))).toDouble()
        val fat = fat()
        val deliv = sp.getFloat(DELIV_U, 0f).toDouble()
        val k = multiplier()
        val count = sp.getInt(LEARN_COUNT, 0)
        val realXe = sp.getFloat(REAL_XE_PENDING, -1f).toDouble()

        // verdict: 0 = fine, 1 = too little insulin, -1 = too much insulin
        var verdict = 0
        if (low < LOW_MGDL) verdict = -1 else if (peak > HIGH_MGDL && low >= LOW_OK_MGDL) verdict = 1
        var newK = k
        var note = "ok"
        if (cancelled) {
            note = "cancelled"
        } else if (partial) {
            note = "partial"
        } else if (fat) {
            note = "fat"
        } else if (frozen) {
            note = "frozen"
        } else if (count < WARMUP_MEALS) {
            note = "warmup"
        } else {
            if (realXe > 0.0 && estG > 0.0) {
                val ratio = (realXe * 10.0) / (estG / k)
                newK = k + 0.3 * (ratio - k)
                note = "real_xe"
            } else if (verdict == -1) {
                newK = k * (if (low < SEVERE_LOW_MGDL) 0.88 else 0.92)
                note = "too_much"
            } else if (verdict == 1) {
                newK = k * 1.06
                note = "too_little"
            }
            newK = newK.coerceIn(max(K_MIN, k * 0.9), min(K_MAX, k * 1.1)).coerceIn(K_MIN, K_MAX)
        }

        val meals = readMeals()
        meals.put(
            JSONObject()
                .put("startMs", start)
                .put("estG", estG)
                .put("estXe", estG / 10.0)
                .put("size", size())
                .put("estMaxMin", sp.getFloat(EST_MAX_MIN, 0f).toDouble())
                .put("countedXe", sp.getFloat(COUNTED_G, 0f) / 10.0)
                .put("peakActUph", sp.getFloat(PEAK_ACT, 0f).toDouble())
                .put("peakActMin", sp.getFloat(PEAK_ACT_MIN, 0f).toDouble())
                .put("deliveredU", deliv)
                .put("peakMgdl", peak)
                .put("minMgdl", low)
                .put("endMgdl", sp.getFloat(LAST_BG, 0f).toDouble())
                .put("realXe", if (realXe > 0.0) realXe else -1.0)
                .put("k", k)
                .put("newK", newK)
                .put("verdict", verdict)
                .put("note", note)
        )
        saveMeals(meals)
        sp.edit()
            .putBoolean(PENDING, false)
            .putBoolean(ACTIVE, false)
            // The next press starts in the automatic size again.
            .putInt(SIZE, SIZE_AUTO)
            .putBoolean(FAT, false)
            .putFloat(K, newK.toFloat())
            .putInt(LEARN_COUNT, if (partial || frozen) count else count + 1)
            .remove(REAL_XE_PENDING)
            .apply()
        pendingLog.add(
            JSONObject().put("event", "meal_result").put("t", nowMs).put("startMs", start)
                .put("estG", estG).put("deliveredU", deliv).put("peakMgdl", peak).put("minMgdl", low)
                .put("k", k).put("newK", newK).put("note", note).toString()
        )
    }

    /**
     * The start dose. When there are at least [START_MIN_MEALS] meals of the SAME size button (small, medium, big, fat or
     * auto) that matched the expectation (see [isMatched]), it is a third of their typical (median) insulin. Only meals
     * of the same size are compared, because a 10 g meal and a 70 g meal need very different insulin. Otherwise the
     * value from the settings is used. The result stays between [START_DOSE_MIN_U] and the limit of the window.
     * Old records have no size and are not used here.
     */
    fun startDoseU(settingU: Double, maxWindowU: Double, sizeIndex: Int = size()): Double {
        // Fixed: always the setting. No learning from past meals.
        return settingU.coerceAtMost(maxWindowU)
    }

    /**
     * Median insulin per bread unit over the finished meals that ended well (no low, no high peak), and how many meals
     * that is. Returns null when there are fewer than 3 such meals. Fat meals and partial meals are left out.
     */
    fun goodMealUPerXe(): Pair<Double, Int>? {
        val values = meals()
            .filter { isMatched(it) && it.optString("note") != "fat" }
            .filter { it.optDouble("estXe", 0.0) >= 0.5 && it.optDouble("deliveredU", 0.0) > 0.0 }
            .map { it.optDouble("deliveredU") / it.optDouble("estXe") }
            .sorted()
        if (values.size < 3) return null
        val median = if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
        return median to values.size
    }

    data class HourlyIcSummary(val hour: Int, val medianGramsPerU: Double?, val count: Int)

    /** Successful, non-fat meal evidence grouped by local start hour; read-only Assistant input. */
    fun goodMealIcByHour(): List<HourlyIcSummary> {
        val byHour = Array(24) { ArrayList<Double>() }
        meals()
            .filter { isMatched(it) && it.optString("note") != "fat" }
            .forEach { meal ->
                val xe = meal.optDouble("estXe", 0.0)
                val insulin = meal.optDouble("deliveredU", 0.0)
                val start = meal.optLong("startMs", 0L)
                if (xe < 0.5 || insulin <= 0.0 || start <= 0L) return@forEach
                val hour = java.util.Calendar.getInstance().apply { timeInMillis = start }.get(java.util.Calendar.HOUR_OF_DAY)
                byHour[hour].add(xe * 10.0 / insulin)
            }
        return byHour.mapIndexed { hour, values ->
            val sorted = values.sorted()
            val median = when {
                sorted.isEmpty() -> null
                sorted.size % 2 == 1 -> sorted[sorted.size / 2]
                else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
            HourlyIcSummary(hour, median, values.size)
        }
    }

    /** True when [startDoseU] for this size comes from the history and not from the setting. */
    fun startDoseFromHistory(sizeIndex: Int = size()): Boolean =
        meals().count { isMatched(it) && it.optInt("size", -1) == sizeIndex && it.optDouble("deliveredU", 0.0) > 0.0 } >= START_MIN_MEALS

    /** True for a meal that the user cancelled or that was cut short: its numbers must not be trusted. */
    fun isCancelled(m: JSONObject): Boolean = m.optString("note") == "cancelled" || m.optString("note") == "partial"

    /**
     * True when the result matched the expectation: the meal was finished, glucose did not go too low (a little below
     * the target is fine), the peak was not high and at the end glucose was not left high. Only such meals are used to
     * learn the start dose and the insulin per bread unit.
     */
    fun isMatched(m: JSONObject): Boolean {
        if (isCancelled(m)) return false
        if (m.optInt("verdict", 0) != 0) return false
        val end = m.optDouble("endMgdl", 0.0)
        return end <= 0.0 || end <= MATCH_END_MAX_MGDL
    }

    fun multiplier(): Double = sp.getFloat(K, 1f).toDouble().coerceIn(K_MIN, K_MAX)
    fun learnedMeals(): Int = sp.getInt(LEARN_COUNT, 0)
    fun resetLearning() {
        sp.edit().putFloat(K, 1f).putInt(LEARN_COUNT, 0).apply()
    }

    // ---- data for the screens ----

    data class Snapshot(
        val active: Boolean,
        val startMs: Long,
        val size: Int,
        val startBgMgdl: Double,
        val estimateG: Double,
        val neededU: Double,
        val deliveredU: Double,
        val brake: Double,
        val peakMgdl: Double,
        val minMgdl: Double,
        val pending: Boolean,
        val fat: Boolean,
        val countedG: Double = 0.0,
        val coveredG: Double = 0.0,
        /** Highest carbohydrate estimate of this meal (it does not go down when the estimate falls). */
        val peakCarbsG: Double = 0.0,
        val peakCarbsMin: Double = 0.0,
        val peakActivityUph: Double = 0.0,
        val peakActivityMin: Double = 0.0,
        /** The most insulin that the estimate found needed in this meal. */
        val peakNeedU: Double = 0.0,
    )

    fun snapshot() = Snapshot(
        active = isActive(),
        startMs = sp.getLong(START_MS, 0L),
        size = sp.getInt(SIZE, SIZE_AUTO),
        startBgMgdl = sp.getFloat(START_BG, 0f).toDouble(),
        estimateG = sp.getFloat(EST_G, 0f).toDouble(),
        neededU = sp.getFloat(NEED_U, 0f).toDouble(),
        deliveredU = sp.getFloat(DELIV_U, 0f).toDouble(),
        brake = sp.getFloat(BRAKE, 1f).toDouble(),
        peakMgdl = sp.getFloat(PEAK, 0f).toDouble(),
        minMgdl = sp.getFloat(MIN, 0f).toDouble(),
        pending = sp.getBoolean(PENDING, false),
        fat = fat(),
        countedG = sp.getFloat(COUNTED_G, 0f).toDouble(),
        coveredG = sp.getFloat(COVERED_G, 0f).toDouble(),
        peakCarbsG = sp.getFloat(COUNTED_G, 0f).toDouble(),
        peakCarbsMin = sp.getFloat(EST_MAX_MIN, 0f).toDouble(),
        peakActivityUph = sp.getFloat(PEAK_ACT, 0f).toDouble(),
        peakActivityMin = sp.getFloat(PEAK_ACT_MIN, 0f).toDouble(),
        peakNeedU = sp.getFloat(NEED_MAX_U, 0f).toDouble(),
    )

    /** Finished meals, the newest last. Each has: startMs, estG, estXe, deliveredU, peakMgdl, minMgdl, realXe (-1 if not given), k, newK, verdict, note. */
    fun meals(): List<JSONObject> {
        val a = readMeals()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun readMeals(): JSONArray = try {
        JSONArray(sp.getString(MEALS, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun saveMeals(a: JSONArray) {
        val trimmed = JSONArray()
        val from = max(0, a.length() - MAX_MEALS)
        for (i in from until a.length()) trimmed.put(a.get(i))
        sp.edit().putString(MEALS, trimmed.toString()).apply()
        mealsDirty = true
    }

    private var mealsDirty = false

    // ---- log file for later statistics ----

    private val pendingLog = mutableListOf<String>()

    /** One line per tick while the window is active (and the meal result lines). Called by the algorithm. */
    fun logTick(i: Input, o: Output?) {
        if (o == null) return
        pendingLog.add(
            String.format(
                Locale.US,
                "{\"event\":\"tick\",\"t\":%d,\"ageMin\":%.1f,\"bgMgdl\":%.0f,\"delta5\":%.1f,\"estG\":%.1f,\"neededU\":%.2f,\"deliveredU\":%.2f,\"remainingU\":%.2f,\"brake\":%.2f,\"k\":%.2f}",
                i.nowMs, o.ageMin, i.bgMgdl, i.deltaMgdl5, o.estimateG, o.neededU, i.deliveredU, o.budgetRemainingU, o.brake, multiplier()
            )
        )
    }

    /**
     * Writes the tick lines to the log file. The log keeps only the last `LOG_KEEP_MS` (3 days): once a day the older
     * lines are cut away, so the file does not grow without limit.
     */
    fun flushLog(file: File?) {
        if (file == null) return
        try {
            val now = System.currentTimeMillis()
            if (file.exists() && now - sp.getLong(LOG_TRIM_MS, 0L) > 24L * 3_600_000L) {
                val from = now - LOG_KEEP_MS
                val kept = file.readLines().filter { line ->
                    val t = TIME_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                    t == null || t >= from
                }
                file.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
                sp.edit().putLong(LOG_TRIM_MS, now).apply()
            }
            if (pendingLog.isNotEmpty()) file.appendText(pendingLog.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
            // the log is only for statistics; a failed write must not stop the algorithm
        }
        pendingLog.clear()
    }

    /**
     * Keeps a copy of the finished meals in a file next to the log, so they are not lost when the app data is cleared.
     * When the app has no meals but the file has, the meals are restored from it.
     */
    fun syncMealsFile(file: File?) {
        if (file == null) return
        try {
            val inApp = sp.getString(MEALS, "[]").orEmpty()
            if ((inApp == "[]" || inApp.isEmpty()) && file.exists() && file.length() > 2L) {
                val restored = JSONArray(file.readText())
                if (restored.length() > 0) {
                    sp.edit().putString(MEALS, restored.toString()).apply()
                    return
                }
            }
            if (mealsDirty || !file.exists()) {
                file.writeText(inApp)
                mealsDirty = false
            }
        } catch (_: Exception) {
            // a failed copy must not stop the algorithm
        }
    }

    companion object {
        const val PREFS = "aimi_eating"
        const val ACTIVE = "active"
        const val START_MS = "start_ms"
        private const val PENDING = "pending"
        private const val SIZE = "size"
        const val FAT = "fat"
        private const val AUTO_START = "auto_start"
        private const val AUTO_START_ENABLED = "auto_start_enabled"
        private const val AUTO_START_SHARE = 0.5
        private const val FAST_RISE_MGDL5 = "fast_rise_mgdl5"
        const val FAST_RISE_DEFAULT_MGDL5 = 5.0
        const val FAST_RISE_MIN_MGDL5 = 3.0
        const val FAST_RISE_MAX_MGDL5 = 10.0
        /** Rescue carbs after a hypo (grams) that get no insulin, and the key that holds them for the window. */
        private const val RESCUE = "rescue_g"
        private const val RESCUE_G = 15.0
        /** A new start within this time of the last one is the same meal (30 minutes). */
        const val RESTART_GRACE_MS = 30L * 60_000L
        /** A new rise makes the window longer: from minute 45, by 2 hours (3 hours for fat food) from now. */
        private const val EXTEND_TO = "extend_to_ms"
        const val END_MS = "end_ms"
        private const val EXTEND_MIN_AGE_MIN = 45.0
        private const val EXTEND_MIN = 120.0
        private const val EXTEND_FAT_MIN = 180.0
        private const val START_BG = "start_bg"
        private const val ABSORBED_G = "absorbed_g"
        private const val LAST_TICK = "last_tick"
        private const val EST_G = "est_g"
        private const val NEED_U = "need_u"
        private const val DELIV_U = "deliv_u"
        private const val BRAKE = "brake"
        private const val PEAK = "peak"
        private const val MIN = "min"
        private const val K = "k"
        private const val LEARN_COUNT = "learn_count"
        private const val REAL_XE_PENDING = "real_xe_pending"
        private const val MEALS = "meals"
        private const val EST_MAX_G = "est_max_g"
        private const val COUNTED_G = "counted_g"
        private const val EST_MAX_MIN = "est_max_min"
        private const val NEED_MAX_U = "need_max_u"
        private const val LAST_BG = "last_bg"
        private const val PEAK_ACT = "peak_act"
        private const val PEAK_ACT_MIN = "peak_act_min"
        private const val COVERED_G = "covered_g"

        /** Size index of the fat meal (the three normal sizes are 0..2). */
        const val SIZE_FAT = 3

        /** No size chosen: the algorithm follows the acceleration of the rise; the window limit is the full setting. */
        const val SIZE_AUTO = 4

        const val WINDOW_MIN = 180.0
        const val WINDOW_MIN_FAT = 300.0
        private const val ESCAPE_MGDL = 150.0 // 8.3 mmol/L
        private const val FAT_START_SHARE = 0.5
        private const val PROJECT_MIN_FAT = 40.0
        private const val START_PHASE_MIN = 30.0
        private const val START_MIN_BG_MGDL = 90.0
        /** The start dose counts as given when one bolus is at most this much smaller (pump step rounding, U200: 0.2 U). */
        private const val START_DONE_TOLERANCE_U = 0.25
        private const val CLOSE_LOW_MGDL = 72.0
        private const val NOISE_MGDL_5 = 1.5
        private const val PROJECT_MIN = 20.0
        private const val PLAN_RAMP_MIN = 60.0
        private const val PLAN_FAT_DELAY_MIN = 0.0
        private const val PLAN_FAT_RAMP_MIN = 240.0
        // A fast rise (soda, sweets) pulls the plan forward: from this rise per 5 minutes the first share is due, and at the
        // second value the whole plan is due now. mg/dL per 5 minutes.
        private const val PLAN_RISE_FROM = 4.0
        private const val PLAN_RISE_FULL = 12.0
        private val SIZE_SHARE = doubleArrayOf(0.4, 0.7, 1.0, 1.0, 1.0)

        private const val HIGH_MGDL = 162.0 // 9.0 mmol/L
        private const val LOW_OK_MGDL = 90.0 // 5.0 mmol/L
        private const val MATCH_END_MAX_MGDL = 144.0 // 8.0 mmol/L
        private const val LOW_MGDL = 81.0 // 4.5 mmol/L
        private const val SEVERE_LOW_MGDL = 70.0 // 3.9 mmol/L
        private const val K_MIN = 0.7
        private const val K_MAX = 1.4
        private const val WARMUP_MEALS = 10
        private const val MAX_MEALS = 60
        private const val LOG_KEEP_MS = 3L * 24L * 3_600_000L
        private const val LOG_TRIM_MS = "log_trim_ms"
        private val TIME_REGEX = Regex("\"t\":(\\d+)")
        private const val START_SHARE = 1.0 / 3.0
        private const val START_MIN_MEALS = 3
        private const val START_HISTORY_MEALS = 20
        private const val START_DOSE_MIN_U = 0.3
    }
}
