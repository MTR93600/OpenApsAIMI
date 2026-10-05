package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.concurrent.Volatile
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
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * HealthKit read session behind [HealthKitWindowPort].
 *
 * `apsEnabled` stays false while `IosClientConfig.APS` is false. The engine switch is read live
 * and is also false in production. Both have to be true before [requestReadAuthorization] runs,
 * and before any HealthKit query. The tick then still logs the empty-snapshot line.
 */
@OptIn(ExperimentalForeignApi::class)
private object IosHealthKitWindowPort : HealthKitWindowPort {

    private val store = HKHealthStore()

    override fun requestReadAuthorization() {
        val steps = quantityType(HKQuantityTypeIdentifierStepCount)
        val heart = quantityType(HKQuantityTypeIdentifierHeartRate)
        val resting = quantityType(HKQuantityTypeIdentifierRestingHeartRate)
        var failure: String? = null
        val done = dispatch_semaphore_create(0)
        store.requestAuthorizationToShareTypes(
            typesToShare = null,
            readTypes = setOf(steps, heart, resting),
        ) { _, error ->
            failure = error?.localizedDescription
            dispatch_semaphore_signal(done)
        }
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
        if (failure != null) throw HealthKitReadFailure(failure!!)
    }

    override fun stepsSum(nowMs: Long, minutes: Int): Int {
        val steps = quantityType(HKQuantityTypeIdentifierStepCount)
        var count = 0
        await(nowMs, minutes * 60 * 1000L) { predicate ->
            HKStatisticsQuery(steps, predicate, HKStatisticsOptionCumulativeSum) { _, statistics, error ->
                if (error == null) {
                    count = statistics?.sumQuantity()?.doubleValueForUnit(HKUnit.countUnit())?.toInt() ?: 0
                }
                this.error = error?.localizedDescription
                finish()
            }
        }
        return count
    }

    override fun heartRateSamples(nowMs: Long, minutes: Int): List<HR> {
        val heart = quantityType(HKQuantityTypeIdentifierHeartRate)
        val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
        var samples = emptyList<HR>()
        await(nowMs, minutes * 60 * 1000L) { predicate ->
            HKSampleQuery(heart, predicate, HKObjectQueryNoLimit, null) { _, raw, error ->
                if (error == null) {
                    samples = raw.orEmpty().mapNotNull { sample ->
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
                }
                this.error = error?.localizedDescription
                finish()
            }
        }
        return samples
    }

    override fun restingHeartRateBpm(nowMs: Long, hours: Int): Int {
        val resting = quantityType(HKQuantityTypeIdentifierRestingHeartRate)
        val bpm = HKUnit.countUnit().unitDividedByUnit(HKUnit.minuteUnit())
        var value = 0
        await(nowMs, hours * 60 * 60 * 1000L) { predicate ->
            HKStatisticsQuery(resting, predicate, HKStatisticsOptionDiscreteAverage) { _, statistics, error ->
                if (error == null) {
                    value = statistics?.averageQuantity()?.doubleValueForUnit(bpm)?.toInt() ?: 0
                }
                this.error = error?.localizedDescription
                finish()
            }
        }
        return value
    }

    private fun quantityType(identifier: String?): HKQuantityType {
        val name = identifier ?: throw HealthKitReadFailure("quantity type missing")
        return HKObjectType.quantityTypeForIdentifier(name)
            ?: throw HealthKitReadFailure("quantity type missing")
    }

    private fun await(nowMs: Long, lookbackMs: Long, start: QuerySlot.(platform.Foundation.NSPredicate) -> HKQuery) {
        val slot = QuerySlot()
        val predicate = HKQuery.predicateForSamplesWithStartDate(
            NSDate.dateWithTimeIntervalSince1970((nowMs - lookbackMs) / 1000.0),
            NSDate.dateWithTimeIntervalSince1970(nowMs / 1000.0),
            HKQueryOptionStrictStartDate,
        )
        store.executeQuery(slot.start(predicate))
        dispatch_semaphore_wait(slot.done, DISPATCH_TIME_FOREVER)
        if (slot.error != null) throw HealthKitReadFailure(slot.error!!)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class QuerySlot {
    val done = dispatch_semaphore_create(0)
    var error: String? = null

    fun finish() {
        dispatch_semaphore_signal(done)
    }
}

/**
 * False while `IosClientConfig.APS` is false. Activation is the only place that may copy that field
 * here. Together with [AimiCommonEngineSwitch] it gates the HealthKit prompt.
 */
@Volatile
internal var iosHealthKitApsEnabled: Boolean = false

/** Replaced only by a test on device. Production uses [IosHealthKitWindowPort]. */
@Volatile
internal var iosHealthKitReadPort: HealthKitWindowPort = IosHealthKitWindowPort

internal actual fun iosPlatformWearable(log: MutableList<String>): HealthContextSnapshot {
    val session = readIosHealthKitSession(
        log = log,
        port = iosHealthKitReadPort,
        apsEnabled = iosHealthKitApsEnabled,
        engineEnabled = AimiCommonEngineSwitch.enabled,
    )
    if (!session.windows.snapshot.isValid) log += IosNeutralLog.WEARABLE
    return session.windows.snapshot
}

/**
 * Opens the read session when both switches are on. While either is off, no authorization and no
 * HealthKit query: the snapshot stays the empty one the tick already logs.
 */
internal fun readIosHealthKitSession(
    log: MutableList<String>,
    port: HealthKitWindowPort,
    apsEnabled: Boolean,
    engineEnabled: Boolean,
): HealthKitSessionResult {
    if (!healthKitReadAuthorizationAllowed(apsEnabled, engineEnabled)) {
        return HealthKitSessionResult(
            windows = WearableSnapshotWindows(
                snapshot = HealthContextSnapshot(),
                hrAvg10 = 0,
                hrAvg60 = 0,
            ),
            heartRates = emptyList(),
            authorizationRequested = false,
        )
    }
    return openHealthKitReadSession(
        port = port,
        nowMs = aimiWallClockMs(),
        apsEnabled = apsEnabled,
        engineEnabled = engineEnabled,
        log = log,
    )
}
