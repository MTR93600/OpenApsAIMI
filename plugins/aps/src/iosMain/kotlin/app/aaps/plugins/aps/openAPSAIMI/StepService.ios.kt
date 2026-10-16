package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.steps.IosStepCache
import app.aaps.plugins.aps.openAPSAIMI.steps.IosStepDataStatus
import kotlin.concurrent.Volatile

/**
 * iOS step counts, read from a cache that `IosStepsLifecycle` fills from HealthKit.
 *
 * The getters never wait for HealthKit. They only read the in-memory cache, like the Android
 * version reads its live sensor buckets. The windows are the same as on Android: the 5, 10 and 15
 * minute getters answer one 5 minute bucket each, and the 30, 60 and 180 minute getters add up
 * 6, 12 and 34 buckets.
 *
 * **When there is no data, the getters return 0, and that 0 is not a measurement.** That is the
 * case before the first refresh, when HealthKit is not available or a query failed, and when the
 * last good refresh is older than ten minutes. The common `expect` signature is a plain `Int`, so
 * it cannot say "unknown". Read [dataStatus] to tell the two apart.
 *
 * Do not read this 0 as "neutral". The engine treats 0 steps as a direction: in
 * `BasalDecisionEngine` the test `recentSteps5Minutes < 100` is one of the conditions that raises
 * basal to 1.5 times the profile, the lowering branch `recentSteps5Minutes > 100` can never be
 * reached, and `AimiHypoSmbSafety.adjustedDiaMinutes` lengthens the DIA by 20 percent when steps
 * are under 100 and the heart rate is over 95, while its `steps > 350` branch that shortens the
 * DIA can never be taken. Making the engine tell "no data" from "no steps" is a
 * separate decision that is not made here.
 */
actual object StepService {

    @Volatile
    internal var cache: IosStepCache = IosStepCache { aimiWallClockMs() }

    /**
     * Asks for a refresh without waiting for it. Set by `IosStepsLifecycle` while it runs.
     * Called by a getter when the cache is not usable, so the next tick has an answer.
     */
    @Volatile
    internal var refreshRequest: (() -> Unit)? = null

    /** Whether the getters answer real readings. `false` means every getter's 0 is a placeholder. */
    val dataStatus: IosStepDataStatus
        get() = cache.status()

    actual fun getRecentStepCount5Min(): Int = read { singleBucket(1) }
    actual fun getRecentStepCount10Min(): Int = read { singleBucket(2) }
    actual fun getRecentStepCount15Min(): Int = read { singleBucket(3) }
    actual fun getRecentStepCount30Min(): Int = read { windowSum(6) }
    actual fun getRecentStepCount60Min(): Int = read { windowSum(12) }
    actual fun getRecentStepCount180Min(): Int = read { windowSum(34) }

    private inline fun read(block: IosStepCache.() -> Int): Int {
        val current = cache
        if (!current.hasData()) refreshRequest?.invoke()
        return current.block()
    }
}
