package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSAIMI.steps.AIMIHealthConnectSyncServiceMTR
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic integrity check for the physio + activity data chain.
 * Detects missing DB vitals, incomplete HC permissions, or invalid merged snapshot; triggers recovery.
 */
@Singleton
class AIMIPhysioPipelineWatchdogMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val permissionsHandler: AIMIHealthConnectPermissionsHandlerMTR,
    private val healthConnectSync: AIMIHealthConnectSyncServiceMTR,
    private val healthRepo: HealthContextRepository,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {

    companion object {
        private const val TAG = "PhysioWatchdog"
        private const val DB_LOOKBACK_MS = 6 * 60 * 60 * 1000L

        @Volatile
        var instance: AIMIPhysioPipelineWatchdogMTR? = null
            private set
    }

    init {
        instance = this
    }

    /**
     * Runs diagnostics and best-effort recovery (HC sync + physio refresh). Safe on any thread.
     */
    fun runCheckAndRecover() {
        val now = System.currentTimeMillis()
        val start = now - DB_LOOKBACK_MS

        val hrCount = try {
            persistenceLayer.getHeartRatesFromTimeToTime(start, now).size
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] HR DB query failed: ${e.message}")
            -1
        }
        val scCount = try {
            persistenceLayer.getStepsCountFromTimeToTime(start, now).size
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Steps DB query failed: ${e.message}")
            -1
        }

        val hcPermsOk = runBlocking(Dispatchers.IO) {
            try {
                permissionsHandler.hasAllPermissions()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$TAG] HC permission check failed: ${e.message}")
                false
            }
        }

        try {
            healthRepo.fetchSnapshot()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] fetchSnapshot failed: ${e.message}")
        }

        val snap = healthRepo.getLastSnapshot()
        val mode = sp.getString(UnifiedActivityProviderMTR.PREF_KEY_SOURCE_MODE, UnifiedActivityProviderMTR.DEFAULT_MODE)
            ?: UnifiedActivityProviderMTR.DEFAULT_MODE
        val activityDisabled = mode == UnifiedActivityProviderMTR.MODE_DISABLED

        val issues = mutableListOf<String>()
        if (!hcPermsOk) issues += "HealthConnect permissions incomplete"
        if (hrCount == 0 && scCount == 0 && !activityDisabled) issues += "No HR/steps in DB (6h)"
        if (!snap.isValid && !activityDisabled) issues += "Merged snapshot invalid (low confidence)"

        if (issues.isEmpty()) {
            aapsLogger.info(
                LTag.APS,
                "[$TAG] OK | hcPerms=$hcPermsOk dbHr=$hrCount dbSc=$scCount snap.hr=${snap.hrNow} steps15=${snap.stepsLast15m} mode=$mode"
            )
            return
        }

        aapsLogger.warn(
            LTag.APS,
            "[$TAG] Recovering: ${issues.joinToString("; ")} | hcPerms=$hcPermsOk dbHr=$hrCount dbSc=$scCount snap.hr=${snap.hrNow} mode=$mode"
        )

        try {
            healthConnectSync.triggerManualSync()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] triggerManualSync failed: ${e.message}")
        }

        try {
            AIMIPhysioManagerMTR.instance?.performUpdate(daysBack = 1, runLLM = false)
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] performUpdate recover failed: ${e.message}")
        }
    }
}
