package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.permissions.AimiPermissionState
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.HealthKit.HKAuthorizationRequestStatusShouldRequest
import platform.HealthKit.HKAuthorizationRequestStatusUnnecessary
import platform.HealthKit.HKCategoryTypeIdentifierSleepAnalysis
import platform.HealthKit.HKHealthStore
import platform.HealthKit.HKObjectType
import platform.HealthKit.HKQuantityTypeIdentifierAppleSleepingWristTemperature
import platform.HealthKit.HKQuantityTypeIdentifierBasalBodyTemperature
import platform.HealthKit.HKQuantityTypeIdentifierHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierHeartRateVariabilitySDNN
import platform.HealthKit.HKQuantityTypeIdentifierRestingHeartRate
import platform.HealthKit.HKQuantityTypeIdentifierStepCount
import platform.HealthKit.HKSampleType
import kotlin.coroutines.resume

/**
 * Every Apple Health type AIMI reads, asked for in one sheet.
 *
 * Nothing more than that: the sheet must not ask for data the app no longer reads. The heartbeat
 * series type was dropped here when HRV moved to the SDNN quantity. [IosHealthKitPhysioRepository]
 * and the Apple Health access screen ([IosHealthKitAccess]) both ask for this list, so the sheet is
 * the same whichever asks first.
 */
internal fun iosHealthKitReadTypes(): Set<HKObjectType> {
    val types = mutableSetOf<HKObjectType>()
    listOf(
        HKQuantityTypeIdentifierStepCount,
        HKQuantityTypeIdentifierHeartRate,
        HKQuantityTypeIdentifierRestingHeartRate,
        HKQuantityTypeIdentifierHeartRateVariabilitySDNN,
        HKQuantityTypeIdentifierAppleSleepingWristTemperature,
        HKQuantityTypeIdentifierBasalBodyTemperature,
    ).forEach { identifier ->
        identifier?.let { HKObjectType.quantityTypeForIdentifier(it) }?.let { types += it }
    }
    HKCategoryTypeIdentifierSleepAnalysis?.let { identifier ->
        HKObjectType.categoryTypeForIdentifier(identifier)?.let { types += it }
    }
    return types
}

/**
 * What the Apple Health access screen can learn and do.
 *
 * HealthKit never tells an app whether a read type was allowed: a refused type just reads as empty.
 * What it does tell is whether the permission sheet would still be shown. So the state is one of:
 * - [AimiPermissionState.NOT_ASKED]: the sheet was never answered; [ask] shows it.
 * - [AimiPermissionState.ANSWERED]: the sheet was answered. iOS will not show it again; the user
 *   changes the choice in the Health app.
 * - [AimiPermissionState.UNAVAILABLE]: this device has no Apple Health (for example an iPad).
 *
 * The store is built on first use, never in the constructor, for the same reason as in
 * [IosHealthKitPhysioRepository]: touching HealthKit outside a running app throws.
 */
internal class IosHealthKitAccess(private val aapsLogger: AAPSLogger) {

    private val store: HKHealthStore? by lazy {
        runCatching { if (HKHealthStore.isHealthDataAvailable()) HKHealthStore() else null }.getOrNull()
    }

    suspend fun state(): AimiPermissionState {
        val healthStore = store ?: return AimiPermissionState.UNAVAILABLE
        val types = iosHealthKitReadTypes()
        if (types.isEmpty()) return AimiPermissionState.UNAVAILABLE
        return suspendCancellableCoroutine { continuation ->
            healthStore.getRequestStatusForAuthorizationToShareTypes(
                typesToShare = emptySet<HKSampleType>(),
                readTypes = types,
            ) { status, error ->
                if (error != null) aapsLogger.warn(LTag.APS, "Apple Health request status failed: ${error.localizedDescription}")
                continuation.resume(
                    when (status) {
                        HKAuthorizationRequestStatusUnnecessary   -> AimiPermissionState.ANSWERED
                        HKAuthorizationRequestStatusShouldRequest -> AimiPermissionState.NOT_ASKED
                        // Unknown: asking again is harmless. iOS shows no sheet when it was answered.
                        else                                      -> AimiPermissionState.NOT_ASKED
                    }
                )
            }
        }
    }

    /** Shows the Apple Health sheet. iOS shows it only when it was never answered. */
    suspend fun ask() {
        val healthStore = store ?: return
        val types = iosHealthKitReadTypes()
        if (types.isEmpty()) return
        suspendCancellableCoroutine { continuation ->
            healthStore.requestAuthorizationToShareTypes(typesToShare = null, readTypes = types) { answered, error ->
                if (error != null) aapsLogger.warn(LTag.APS, "Apple Health authorization failed: ${error.localizedDescription}")
                else aapsLogger.debug(LTag.APS, "Apple Health authorization answered=$answered")
                continuation.resume(Unit)
            }
        }
    }
}
