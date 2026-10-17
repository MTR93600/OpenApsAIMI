package app.aaps.plugins.aps.openAPSAIMI.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PermissionProvider
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.StepService
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStepsLifecycle
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * 🎛️ AIMI Steps Manager - MTR Central Controller
 *
 * Manages all steps data sources (Garmin, Wear OS, Health Connect, Phone Sensor).
 * Starts/stops sync services and provides unified status.
 *
 * Sources Priority (all write to DB):
 * 1. Garmin (if available) - Highest priority
 * 2. Wear OS (if available)
 * 3. Phone Sensor (always available) - Persistent backup
 * 4. Health Connect (Android 14+) - Fallback if others missing
 *
 * All consumers (DetermineBasalAIMI2, Dashboard) read from DB.
 *
 * @author MTR & Lyra AI - AIMI Steps Integration
 */
@ContributesBinding(AppScope::class, binding = binding<AimiStepsLifecycle>())
@ContributesIntoSet(AppScope::class, binding = binding<PermissionProvider>())
@SingleIn(AppScope::class)
class AIMIStepsManagerMTR @Inject constructor(
    private val context: Context,
    private val healthConnectSync: AIMIHealthConnectSyncServiceMTR,
    private val phoneStepsSync: AIMIPhoneStepsSyncServiceMTR,
    private val aapsLogger: AAPSLogger
) : AimiStepsLifecycle, PermissionProvider {

    companion object {
        private const val TAG = "StepsManager"
    }

    private var isStarted = false

    /** What is really true about the phone step sensor. Written on start, stop and retry. */
    @Volatile
    private var sensorState = StepSensorState.NOT_STARTED

    /**
     * Starts all steps sync services
     */
    override fun start() {
        if (isStarted) {
            aapsLogger.debug(LTag.APS, "[$TAG] Already started")
            return
        }

        aapsLogger.info(LTag.APS, "[$TAG] 🚀 Starting AIMI Steps Manager")

        try {
            // Open the phone step counter. Without this the sync below only ever reads zeros.
            registerStepSensor()
            phoneStepsSync.start()
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Phone sensor sync started")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Phone sensor sync failed to start", e)
        }

        try {
            // Start Health Connect sync (Android 14+ only)
            healthConnectSync.start()
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Health Connect sync started")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect sync failed to start", e)
        }

        isStarted = true

        aapsLogger.info(LTag.APS, "[$TAG] 📊 Steps Manager Status:")
        aapsLogger.info(LTag.APS, "[$TAG]   - Garmin: Via GarminPlugin (HTTP/CIQ)")
        aapsLogger.info(LTag.APS, "[$TAG]   - Wear OS: Via DataHandlerMobile")
        aapsLogger.info(LTag.APS, "[$TAG]   - Phone: sync every 5 min, step sensor ${sensorState.description}")
        aapsLogger.info(LTag.APS, "[$TAG]   - Health Connect: ${healthConnectSync.getSyncStatus()}")
    }

    /**
     * Stops all steps sync services
     */
    override fun stop() {
        if (!isStarted) {
            return
        }

        aapsLogger.info(LTag.APS, "[$TAG] 🛑 Stopping AIMI Steps Manager")

        phoneStepsSync.stop()
        unregisterStepSensor()
        healthConnectSync.stop()

        isStarted = false
    }

    /**
     * Gets comprehensive status of all sources
     */
    fun getSourcesStatus(): Map<String, String> {
        return mapOf(
            "Garmin" to "Via GarminPlugin (passive)",
            "WearOS" to "Via DataHandlerMobile (passive)",
            "PhoneSensor" to if (isStarted) "Sync running, step sensor ${sensorState.description}" else "Stopped",
            "HealthConnect" to healthConnectSync.getSyncStatus()
        )
    }

    /**
     * Triggers manual sync for all active sources
     */
    fun triggerManualSync() {
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Manual sync triggered for all sources")
        healthConnectSync.triggerManualSync()
        // Phone sensor syncs automatically via timer
    }

    /**
     * Logs status for debugging
     */
    fun logStatus() {
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.APS, "[$TAG] AIMI Steps Sources Status:")
        getSourcesStatus().forEach { (source, status) ->
            aapsLogger.info(LTag.APS, "[$TAG]   $source: $status")
        }
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
    }

    /**
     * The phone needs the `ACTIVITY_RECOGNITION` permission before the step counter delivers
     * events. The central permission list calls this on every pass, so it also retries the
     * registration once the user has granted the permission.
     */
    /**
     * Note that this does more than answer: it also retries the sensor registration once the
     * permission has arrived. That is a side effect in what looks like a query, so it is pulled
     * out into its own named call below rather than hidden in the condition.
     *
     * The reason is that `PermissionProvider` has no "permission granted" callback, and this
     * method is the only thing the host calls again after the user answers the sheet. Without the
     * retry the grant would do nothing until the next `start()`, so the person would allow the
     * permission and still get no steps. If `PermissionProvider` ever gains a grant hook, move the
     * retry there and make this a plain query again.
     */
    override fun requiredPermissions(): List<PermissionGroup> {
        if (!isStarted) return emptyList()
        retryRegistrationIfPermissionArrived()
        if (sensorState != StepSensorState.PERMISSION_MISSING) return emptyList()
        return listOf(
            PermissionGroup(
                permissions = listOf(Manifest.permission.ACTIVITY_RECOGNITION),
                rationaleTitle = TextRef.AndroidRes(R.string.aimi_permission_steps_title),
                rationaleDescription = TextRef.AndroidRes(R.string.aimi_permission_steps_description),
            )
        )
    }

    /**
     * Registers the sensor if the only thing that was missing is now granted. Does nothing in any
     * other state, so it is safe to call on every pass.
     */
    private fun retryRegistrationIfPermissionArrived() {
        if (sensorState == StepSensorState.PERMISSION_MISSING && hasActivityRecognition()) registerStepSensor()
    }

    private fun hasActivityRecognition(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /**
     * Registers [StepService] on `TYPE_STEP_COUNTER`. It must not be `TYPE_STEP_DETECTOR`:
     * [StepService] subtracts the last value from the new one, which only works for a counter
     * that grows. A detector sends 1.0 for each step, so the difference would always be 0.
     *
     * Every failure keeps [StepService] answering `null` (unknown), never 0.
     */
    private fun registerStepSensor() {
        if (sensorState == StepSensorState.REGISTERED) return
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val counter = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        // ACTIVITY_RECOGNITION is a runtime permission from API 29 only. Before that it is granted at install.
        val granted = hasActivityRecognition()
        when (planStepSensorRegistration(hasStepCounter = counter != null, permissionGranted = granted)) {
            StepSensorPlan.NO_STEP_COUNTER   -> {
                sensorState = StepSensorState.NO_STEP_COUNTER
                aapsLogger.warn(LTag.APS, "[$TAG] Phone has no step counter sensor. Phone steps stay unknown.")
            }

            StepSensorPlan.PERMISSION_MISSING -> {
                sensorState = StepSensorState.PERMISSION_MISSING
                aapsLogger.warn(LTag.APS, "[$TAG] ACTIVITY_RECOGNITION is not granted. Phone steps stay unknown until the user grants it.")
            }

            StepSensorPlan.REGISTER          -> {
                // A fresh start: forget the old baseline, so steps taken while we were not listening are not counted as one burst.
                StepService.resetForNewRegistration()
                val accepted = try {
                    sensorManager!!.registerListener(StepService, counter, SensorManager.SENSOR_DELAY_NORMAL)
                } catch (e: SecurityException) {
                    aapsLogger.error(LTag.APS, "[$TAG] Step sensor registration threw SecurityException", e)
                    false
                }
                if (accepted) {
                    sensorState = StepSensorState.REGISTERED
                    aapsLogger.info(LTag.APS, "[$TAG] Step counter registered. Steps stay unknown until the first sensor event.")
                } else {
                    sensorState = StepSensorState.REGISTRATION_REFUSED
                    aapsLogger.error(LTag.APS, "[$TAG] The system refused the step counter registration. Phone steps stay unknown.")
                }
            }
        }
    }

    private fun unregisterStepSensor() {
        if (sensorState == StepSensorState.REGISTERED) {
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(StepService)
            StepService.resetForNewRegistration()
        }
        sensorState = StepSensorState.NOT_STARTED
    }
}
