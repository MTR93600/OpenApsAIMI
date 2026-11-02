package app.aaps.ios.shell.platform

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.source.DexcomBoyda
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.DataSyncSelectorXdrip
import app.aaps.core.interfaces.sync.XDripBroadcast
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/*
 * The glucose-source integrations iOS does not have, answered rather than left missing.
 *
 * On iOS glucose comes from a sensor driver in this app (the Dexcom ONE+ / G7 driver) or from
 * Nightscout (`NSClientSourcePlugin`). The sources that work by talking to another app - xDrip, the
 * Dexcom app, Glimp, Aidex and the rest - stay androidMain. That is not a porting gap: they reach
 * AAPS through Android broadcast intents, one app messaging another, and iOS has no equivalent
 * channel. Reaching those services from iOS would be a different design, not a translation of this
 * one.
 *
 * These bindings still have to exist, because **shared UI asks about them**:
 * `CalibrationDialogViewModel` decides whether to offer sending a calibration,
 * `ElementAvailability` and `TreatmentViewModel` decide which entries to show, and
 * `MaintenanceViewModel` reads the xDrip upload queue. Each of them asks "is this enabled?" and
 * hides the option when the answer is no - which is right, because none of them can exist here.
 *
 * Eversense is the same answer for a different reason: its native driver is Android BLE with JVM
 * BouncyCastle crypto, and there is no CoreBluetooth port. The Eversense source is not in the iOS
 * plugin list at all, and [IosEversenseCalibrationSource] keeps its quick launch entry hidden.
 *
 * So all five report themselves disabled, which is true, and every call is logged. They are answers
 * about what this platform is, not placeholders waiting to be finished.
 */

/** xDrip as a glucose source: it broadcasts readings to other apps, and iOS has no such channel. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosXDripSource(
    private val aapsLogger: AAPSLogger
) : XDripSource {

    override fun isEnabled(): Boolean {
        aapsLogger.notOnThisPlatform("XDripSource.isEnabled - no broadcast channel on iOS")
        return false
    }
}

/** Sending back to xDrip, the other direction of the same missing channel. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosXDripBroadcast(
    private val aapsLogger: AAPSLogger
) : XDripBroadcast {

    override fun isEnabled(): Boolean = false

    override fun sendCalibration(bg: Double): Boolean {
        aapsLogger.notOnThisPlatform("XDripBroadcast.sendCalibration")
        return false
    }

    override fun sendToXdrip(collection: String, dataPair: DataSyncSelector.DataPair, progress: String) =
        aapsLogger.notOnThisPlatform("XDripBroadcast.sendToXdrip")

    override fun sendToXdrip(collection: String, dataPairs: List<DataSyncSelector.DataPair>, progress: String) =
        aapsLogger.notOnThisPlatform("XDripBroadcast.sendToXdrip")
}

/** The upload queue for xDrip. Nothing is queued, because nothing can be sent. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosDataSyncSelectorXdrip(
    private val aapsLogger: AAPSLogger
) : DataSyncSelectorXdrip {

    override fun queueSize(): Long = 0

    override suspend fun resetToNextFullSync() = aapsLogger.notOnThisPlatform("DataSyncSelectorXdrip.resetToNextFullSync")

    override suspend fun doUpload() = aapsLogger.notOnThisPlatform("DataSyncSelectorXdrip.doUpload")

    override fun profileReceived(timestamp: Long) = aapsLogger.notOnThisPlatform("DataSyncSelectorXdrip.profileReceived")
}

/** The Dexcom app's own build of the receiver - an Android app, reached by an Android permission. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosDexcomBoyda(
    private val aapsLogger: AAPSLogger
) : DexcomBoyda {

    override fun isEnabled(): Boolean {
        aapsLogger.notOnThisPlatform("DexcomBoyda.isEnabled - the Dexcom app is Android only")
        return false
    }

    override fun requestPermissionIfNeeded() = aapsLogger.notOnThisPlatform("DexcomBoyda.requestPermissionIfNeeded")

    override fun dexcomPackages(): List<String> = emptyList()
}

/**
 * Eversense calibration over Bluetooth. The native Eversense driver is Android only, so it is never
 * the BG source here, and the quick launch entry that asks [isEnabled] is never shown.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosEversenseCalibrationSource(
    private val aapsLogger: AAPSLogger
) : EversenseCalibrationSource {

    override fun isEnabled(): Boolean = false

    override fun isConnected(): Boolean = false

    override fun isReadyToCalibrate(): Boolean = false

    override fun readinessMessage(): String {
        aapsLogger.notOnThisPlatform("EversenseCalibrationSource.readinessMessage - no Eversense driver on iOS")
        return ""
    }

    override suspend fun calibrate(bgMgDl: Int): Boolean {
        aapsLogger.notOnThisPlatform("EversenseCalibrationSource.calibrate - no Eversense driver on iOS")
        return false
    }
}
