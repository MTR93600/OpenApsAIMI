package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectQueryNoLimit
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantitySample
import platform.HealthKit.HKQuantityType
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierRestingHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKQuery
import platform.HealthKit.HKQueryOptionStrictStartDate
import platform.HealthKit.HKSampleQuery
import platform.HealthKit.HKStatisticsOptionCumulativeSum
import platform.HealthKit.HKStatisticsOptionDiscreteAverage
import platform.HealthKit.HKStatisticsQuery
import platform.HealthKit.HKUnit
import platform.HealthKit.countUnit
import platform.HealthKit.minuteUnit
import platform.HealthKit.predicateForSamplesWithStartDate
import platform.HealthKit.unitDividedByUnit

/**
 * HealthKit fill of [HealthContextSnapshot]. Queries run off the tick. The tick reads the last
 * result, the same way Android reads a cache filled by an async refresh.
 *
 * A platform [Exception] or an NSError logs `WEARABLE snapshot failed … — snapshot empty` and
 * the cache becomes an empty snapshot. An [Error] propagates. Heart rate alone does not set
 * `isValid`, so the scaffold line stays until HRV or sleep is also present.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)
private object IosHealthKitWearable {

    private val cached = AtomicReference(
        WearableSnapshotWindows(snapshot = HealthContextSnapshot(), hrAvg10 = 0, hrAvg60 = 0),
    )
    private val failureLine = AtomicReference<String?>(null)
    private val refreshInFlight = AtomicBoolean(false)

    fun read(log: MutableList<String>): HealthContextSnapshot {
        refresh(aimiWallClockMs())
        failureLine.exchange(null)?.let { log += it }
        val snapshot = cached.load().snapshot
        if (!snapshot.isValid) log += IosNeutralLog.WEARABLE
        return snapshot
    }

    private fun refresh(nowMs: Long) {
        if (!refreshInFlight.compareAndSet(expectedValue = false, newValue = true)) return
        try {
            val store = HKHealthStore()
            val steps = HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierStepCount)
            val heart = HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierHeartRate)
            val resting = HKObjectType.quantityTypeForIdentifier(HKQuantityTypeIdentifierRestingHeartRate)
            if (steps == null || heart == null || resting == null) {
                fail("quantity type missing")
                return
            }
            val gather = Gather(nowMs)
            val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
            queryStepSum(store, steps, gather, nowMs, 5, gather.steps5)
            queryStepSum(store, steps, gather, nowMs, 15, gather.steps15)
            queryStepSum(store, steps, gather, nowMs, 60, gather.steps60)
            val heartQuery = HKSampleQuery(
                heart,
                predicate(nowMs - 60 * 60 * 1000L, nowMs),
                HKObjectQueryNoLimit,
                null,
            ) { _, samples, error ->
                val parsed = samples.orEmpty().mapNotNull { sample ->
                    val quantitySample = sample as? HKQuantitySample ?: return@mapNotNull null
                    val endMs = (quantitySample.endDate.timeIntervalSince1970 * 1000.0).toLong()
                    val startMs = (quantitySample.startDate.timeIntervalSince1970 * 1000.0).toLong()
                    HR(
                        duration = (endMs - startMs).coerceAtLeast(0L),
                        timestamp = endMs,
                        beatsPerMinute = quantitySample.quantity.doubleValueForUnit(bpm),
                        device = "HealthKit",
                    )
                }
                gather.arrive(error?.localizedDescription) { heartRates.store(parsed) }
            }
            store.executeQuery(heartQuery)
            val restingQuery = HKStatisticsQuery(
                resting,
                predicate(nowMs - 24 * 60 * 60 * 1000L, nowMs),
                HKStatisticsOptionDiscreteAverage,
            ) { _, statistics, error ->
                val value = statistics?.averageQuantity()?.doubleValueForUnit(bpm)?.toInt() ?: 0
                gather.arrive(error?.localizedDescription) { this.resting.store(value) }
            }
            store.executeQuery(restingQuery)
        } catch (e: Exception) {
            val errorType = e::class.simpleName ?: "Exception"
            failureLine.store(
                "WEARABLE snapshot failed ($errorType): ${e.message.orEmpty()} — snapshot empty",
            )
            cached.store(WearableSnapshotWindows(snapshot = HealthContextSnapshot(), hrAvg10 = 0, hrAvg60 = 0))
            refreshInFlight.store(false)
        }
    }

    private fun queryStepSum(
        store: HKHealthStore,
        steps: HKQuantityType,
        gather: Gather,
        nowMs: Long,
        minutes: Int,
        target: AtomicInt,
    ) {
        val query = HKStatisticsQuery(
            steps,
            predicate(nowMs - minutes * 60 * 1000L, nowMs),
            HKStatisticsOptionCumulativeSum,
        ) { _, statistics, error ->
            val count = statistics?.sumQuantity()?.doubleValueForUnit(HKUnit.countUnit())?.toInt() ?: 0
            gather.arrive(error?.localizedDescription) { target.store(count) }
        }
        store.executeQuery(query)
    }

    private fun predicate(startMs: Long, endMs: Long) = HKQuery.predicateForSamplesWithStartDate(
        NSDate.dateWithTimeIntervalSince1970(startMs / 1000.0),
        NSDate.dateWithTimeIntervalSince1970(endMs / 1000.0),
        HKQueryOptionStrictStartDate,
    )

    private fun fail(message: String) {
        failureLine.store("WEARABLE snapshot failed (NSError): $message — snapshot empty")
        cached.store(WearableSnapshotWindows(snapshot = HealthContextSnapshot(), hrAvg10 = 0, hrAvg60 = 0))
        refreshInFlight.store(false)
    }

    private class Gather(val nowMs: Long) {
        val steps5 = AtomicInt(0)
        val steps15 = AtomicInt(0)
        val steps60 = AtomicInt(0)
        val heartRates = AtomicReference(emptyList<HR>())
        val resting = AtomicInt(0)
        private val left = AtomicInt(5)
        private val failed = AtomicBoolean(false)

        fun arrive(error: String?, fill: Gather.() -> Unit) {
            if (failed.load()) return
            if (error != null) {
                if (failed.compareAndSet(expectedValue = false, newValue = true)) fail(error)
                return
            }
            fill()
            if (left.fetchAndAdd(-1) == 1) {
                cached.store(
                    snapshotFromWearableWindows(
                        stepsLast5m = steps5.load(),
                        stepsLast15m = steps15.load(),
                        stepsLast60m = steps60.load(),
                        heartRates = heartRates.load(),
                        restingBpm = resting.load(),
                        nowMs = nowMs,
                    ),
                )
                refreshInFlight.store(false)
            }
        }
    }
}

internal actual fun iosPlatformWearable(log: MutableList<String>): HealthContextSnapshot =
    IosHealthKitWearable.read(log)
