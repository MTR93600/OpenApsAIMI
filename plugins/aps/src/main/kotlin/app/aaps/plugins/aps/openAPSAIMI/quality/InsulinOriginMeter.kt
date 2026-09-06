package app.aaps.plugins.aps.openAPSAIMI.quality

import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Measures **where the decided insulin came from**: the model, or the floors that sit under it.
 *
 * A tick can end with the same amount of insulin for two very different reasons. Either the model
 * asked for it, or the model asked for nothing and a floor put it there anyway. The exported
 * `smb_binding_trace` says this for one tick, but one tick answers nothing: the question is what
 * share of a week of insulin the model actually decided. This class keeps that running share.
 *
 * ## Attribution rule
 *
 * For one sample, `raw = max(modelOutputU, autodriveFloorU)`. The floor share of the tick amount is
 * `finalU * (raw - modelOutputU) / raw`, and the model share is the rest. So:
 *
 *  - model 1.0, floor 0.0 → everything is model,
 *  - model 0.0, floor 1.5 → everything is floor,
 *  - model 0.6, floor 1.2 → half model, half floor,
 *  - both zero → nothing is attributed, and the amount lands in `otherOriginU`.
 *
 * ## The totals are tick-weighted, they are NOT delivered units
 *
 * Read this before using any `_u` number out of this class.
 *
 * `finalU` is the amount this tick **decided**, not the amount the pump enacted. This class sits in
 * the decision path and has no way to know what the pump did. The loop also re-decides far more
 * often than it delivers: with a one minute sensor the loop runs about every 60 seconds, and the
 * same micro-bolus is proposed again on every run for as long as it stays valid. Each of those runs
 * adds the full amount to the totals here.
 *
 * That is not a small error. On the night of 2026-09-05, on the 21:22 → 22:35 ramp, the ticks summed
 * to 53.53 U while IOB only rose by 15.82 U — 53 U in 73 minutes is physically impossible. Over the
 * whole night the old key `delivered_u` reported 66.03 U for a night of about 18 U.
 *
 * So the totals are named for what they are: **tick-weighted** sums. They are useful to compare two
 * quantities measured the same way, and useless as an insulin amount.
 *
 * ## Why the shares survive and the totals do not
 *
 * `modelOriginShare` and `floorOriginShare` are ratios of two sums carried over the *same* ticks. A
 * repeated tick adds its amount to the numerator and to the denominator in the same proportion, so
 * repeating a decision does not move the ratio. Doubling the loop cadence doubles both totals and
 * leaves both shares where they were. The shares still carry a weighting bias — a decision that
 * survives many ticks weighs more than one that survives a single tick — but they are not inflated
 * by the cadence, which is what makes them the readable part of this export.
 *
 * ## Window
 *
 * The window is defined in **time**, not in ticks: [windowMs], seven days by default. The previous
 * version kept a fixed 2016 samples and called that "one week of 5 minute ticks"; at one tick per
 * minute those same 2016 samples are 33 hours. [maxSamples] is only a memory cap, so a runaway
 * caller cannot grow the deque without bound. It is not the definition of the window.
 *
 * ## Strictly passive
 *
 * Nothing in the dosing chain may read this. To check that, run:
 *
 * ```
 * grep -rn "InsulinOriginMeter\|insulinOriginMeter" plugins/aps/src/main/kotlin/
 * ```
 *
 * It must return exactly two zones: this file, and the import plus the single field and feeding
 * block in `DetermineBasalAIMI2`. Any other line is a violation.
 *
 * ## No persistence
 *
 * Samples live in memory only. A process restart empties them. `tickCount` is the witness: when it
 * is small, the shares have simply not been rebuilt yet.
 *
 * @param windowMs how far back the window reaches, in milliseconds. Seven days by default.
 * @param maxSamples memory cap on the number of kept samples. Reached only when the loop ticks
 *   faster than every 30 seconds for a whole window.
 */
class InsulinOriginMeter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {

    companion object {

        /** Seven days. The window this meter is meant to answer on. */
        const val DEFAULT_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L

        /** Memory cap only: seven days at one sample every 30 seconds. */
        const val DEFAULT_MAX_SAMPLES: Int = 20_160
    }

    /**
     * One tick, as the binding trace saw it.
     *
     * @param timestampMs tick time.
     * @param finalU insulin this tick decided, U. Not what the pump enacted — see the class doc.
     * @param modelOutputU what the model asked for before the floors, U. `null` when unknown.
     * @param mpcOutputU what the MPC asked for, U. Carried for the export, not used in the split.
     * @param autodriveFloorU the floor that was in force, U. `null` when there was none.
     * @param bindingStage last stage name of the binding trace, for reading back.
     * @param originOwner which channel owned the amount.
     */
    data class Sample(
        val timestampMs: Long,
        val finalU: Double,
        val modelOutputU: Double?,
        val mpcOutputU: Double?,
        val autodriveFloorU: Double?,
        val bindingStage: String?,
        val originOwner: String,
    )

    /**
     * The running answer over the window.
     *
     * Every `U` field below is a **tick-weighted** sum, not an amount of insulin. See the class doc.
     *
     * @param tickCount how many samples the window holds.
     * @param tickWeightedU sum of the decided amounts over those ticks, U per tick summed.
     * @param modelOriginU part of it the model asked for, same weighting.
     * @param floorOriginU part of it a floor added on top of the model, same weighting.
     * @param otherOriginU part of it neither could explain, same weighting. Grows when insulin is
     *   decided while both the model output and the floor are zero or unknown.
     * @param modelZeroTickWeightedU tick-weighted amount on ticks where the model asked for nothing.
     * @param modelZeroTickCount how many ticks those were.
     * @param modelOriginShare `modelOriginU / tickWeightedU`, `null` while nothing was decided. Null,
     *   never `0.0`: zero would read as "the model decided nothing", which is a different statement.
     *   This ratio is cadence-proof, unlike the totals above.
     * @param floorOriginShare `floorOriginU / tickWeightedU`, same rule.
     * @param windowStartMs time of the oldest kept sample, so a reading can be aged.
     * @param windowSpanMs newest kept sample minus oldest, so the real reach of the window is
     *   visible instead of assumed.
     * @param tickGapMedianMs median gap between two kept samples, so the loop cadence is visible in
     *   the export. `null` below two samples.
     */
    data class Reading(
        val tickCount: Int,
        val tickWeightedU: Double,
        val modelOriginU: Double,
        val floorOriginU: Double,
        val otherOriginU: Double,
        val modelZeroTickWeightedU: Double,
        val modelZeroTickCount: Int,
        val modelOriginShare: Double?,
        val floorOriginShare: Double?,
        val windowStartMs: Long?,
        val windowSpanMs: Long?,
        val tickGapMedianMs: Long?,
    ) {

        fun toJsonObject(): JSONObject =
            JSONObject().apply {
                put("tick_count", tickCount)
                put("tick_weighted_u", tickWeightedU)
                put("model_origin_u", modelOriginU)
                put("floor_origin_u", floorOriginU)
                put("other_origin_u", otherOriginU)
                put("model_zero_tick_weighted_u", modelZeroTickWeightedU)
                put("model_zero_tick_count", modelZeroTickCount)
                put("model_origin_share", modelOriginShare ?: JSONObject.NULL)
                put("floor_origin_share", floorOriginShare ?: JSONObject.NULL)
                put("window_start_ms", windowStartMs ?: JSONObject.NULL)
                put("window_span_ms", windowSpanMs ?: JSONObject.NULL)
                put("tick_gap_median_ms", tickGapMedianMs ?: JSONObject.NULL)
                // v2 renamed the totals. `delivered_u` and `model_zero_delivered_u` are gone on
                // purpose: they claimed to be delivered insulin and were tick-weighted sums.
                put("source", "insulin_origin_meter_v2")
            }
    }

    /** One kept sample, already split, so [read] never has to walk the window for the sums. */
    private data class Entry(
        val timestampMs: Long,
        val tickU: Double,
        val modelU: Double,
        val floorU: Double,
        val otherU: Double,
        val modelZeroU: Double,
        val modelZero: Boolean,
    )

    private val entries = ArrayDeque<Entry>()

    private var tickWeightedSum = 0.0
    private var modelSum = 0.0
    private var floorSum = 0.0
    private var otherSum = 0.0
    private var modelZeroSum = 0.0
    private var modelZeroTicks = 0

    /** Below this, the model is treated as having asked for nothing. Half a pump step. */
    private val modelZeroThresholdU = 0.01

    /** Guard against dividing by a raw amount that is zero in all but name. */
    private val epsilon = 1e-9

    /** Records one tick. Amortised O(1): one push, and on average one pop per push. */
    fun observe(s: Sample) {
        val tickU = s.finalU.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val model = s.modelOutputU?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val floor = s.autodriveFloorU?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val raw = maxOf(model, floor)

        val floorPart: Double
        val modelPart: Double
        val otherPart: Double
        if (tickU <= 0.0) {
            floorPart = 0.0
            modelPart = 0.0
            otherPart = 0.0
        } else if (raw > epsilon) {
            floorPart = tickU * ((raw - model) / raw)
            modelPart = tickU - floorPart
            otherPart = 0.0
        } else {
            // Insulin was decided while neither the model nor a floor claimed it. Never silently
            // credit that to the model: it is exactly the case this meter exists to expose.
            floorPart = 0.0
            modelPart = 0.0
            otherPart = tickU
        }

        val modelZero = (s.modelOutputU ?: 0.0) <= modelZeroThresholdU
        val entry = Entry(
            timestampMs = s.timestampMs,
            tickU = tickU,
            modelU = modelPart,
            floorU = floorPart,
            otherU = otherPart,
            modelZeroU = if (modelZero) tickU else 0.0,
            modelZero = modelZero,
        )
        entries.addLast(entry)
        tickWeightedSum += entry.tickU
        modelSum += entry.modelU
        floorSum += entry.floorU
        otherSum += entry.otherU
        modelZeroSum += entry.modelZeroU
        if (entry.modelZero) modelZeroTicks++

        purge(s.timestampMs)
    }

    /**
     * Reads the running answer.
     *
     * The sums are kept by [observe], so they cost nothing here. The median gap does walk the
     * window, which is why it is computed on read and not on every sample.
     *
     * @param nowMs current time. Used to age the window out even when no new sample arrives, so a
     *   loop that stopped ticking does not keep reporting a week-old share as current.
     */
    fun read(nowMs: Long): Reading {
        purge(nowMs)
        val tickWeighted = tickWeightedSum.coerceAtLeast(0.0)
        val hasAmount = tickWeighted > epsilon
        val oldest = entries.peekFirst()?.timestampMs
        val newest = entries.peekLast()?.timestampMs
        return Reading(
            tickCount = entries.size,
            tickWeightedU = tickWeighted,
            modelOriginU = modelSum,
            floorOriginU = floorSum,
            otherOriginU = otherSum,
            modelZeroTickWeightedU = modelZeroSum,
            modelZeroTickCount = modelZeroTicks,
            modelOriginShare = if (hasAmount) modelSum / tickWeighted else null,
            floorOriginShare = if (hasAmount) floorSum / tickWeighted else null,
            windowStartMs = oldest,
            windowSpanMs = if (oldest != null && newest != null) newest - oldest else null,
            tickGapMedianMs = medianGapMs(),
        )
    }

    /**
     * Drops what is older than [windowMs], then what is over the memory cap.
     *
     * The reference time is the later of [referenceMs] and the newest kept sample. A clock that
     * jumps backwards must not empty a window that was correctly filled.
     */
    private fun purge(referenceMs: Long) {
        val newest = entries.peekLast()?.timestampMs ?: return
        val reference = maxOf(referenceMs, newest)
        val cutoff = reference - windowMs
        while (true) {
            val old = entries.peekFirst() ?: return
            if (old.timestampMs >= cutoff && entries.size <= maxSamples) return
            drop()
        }
    }

    private fun drop() {
        val old = entries.removeFirst()
        tickWeightedSum -= old.tickU
        modelSum -= old.modelU
        floorSum -= old.floorU
        otherSum -= old.otherU
        modelZeroSum -= old.modelZeroU
        if (old.modelZero) modelZeroTicks--
    }

    /** Median gap between kept samples, or `null` below two samples. O(n log n) on read only. */
    private fun medianGapMs(): Long? {
        if (entries.size < 2) return null
        val gaps = LongArray(entries.size - 1)
        var index = 0
        var previous: Long? = null
        for (entry in entries) {
            previous?.let { gaps[index++] = entry.timestampMs - it }
            previous = entry.timestampMs
        }
        gaps.sort()
        val middle = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2
    }
}
