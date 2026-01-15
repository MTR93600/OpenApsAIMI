package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔐 AIMI Health Connect Permissions Handler (MTR Clean Architecture)
 * 
 * Responsibilities:
 * - Check SDK availability (Android 14+)
 * - Build permission set
 * - Check granted permissions (IO thread safe)
 * - Create permission request Intent (for Activity Result API)
 * - Never crashes, always returns safe fallback
 * 
 * Usage Pattern:
 * ```kotlin
 * // 1. Check availability
 * if (!handler.isHealthConnectAvailable()) { /* show error */ }
 * 
 * // 2. Check permissions (coroutine)
 * val hasAll = handler.hasAllPermissions()
 * 
 * // 3. Request if needed (from Activity/Fragment)
 * val intent = handler.createPermissionRequestIntent()
 * permissionLauncher.launch(intent)
 * 
 * // 4. After user grants, re-check
 * val nowGranted = handler.hasAllPermissions()
 * ```
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIHealthConnectPermissionsHandlerMTR @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "AIMI_HC"
        
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class)
        )
    }
    
    /**
     * Cached HC client (lazy init, safe)
     */
    private val healthConnectClient: HealthConnectClient? by lazy {
        try {
            if (getSdkStatus() == HealthConnectClient.SDK_AVAILABLE) {
                HealthConnectClient.getOrCreate(context)
            } else {
                aapsLogger.warn(LTag.APS, "[$TAG] Health Connect unavailable on this device")
                null
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to create HC client", e)
            null
        }
    }
    
    /**
     * Check Health Connect SDK availability
     * SAFE: Can be called from any thread
     */
    fun getSdkStatus(): Int {
        return try {
            HealthConnectClient.getSdkStatus(context)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] SDK status check failed", e)
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }
    
    /**
     * Quick availability check
     */
    fun isHealthConnectAvailable(): Boolean {
        return getSdkStatus() == HealthConnectClient.SDK_AVAILABLE
    }
    
    /**
     * Check if all required permissions are granted
     * MUST be called from IO dispatcher (coroutine)
     * SAFE: Never throws, returns false on any error
     */
    suspend fun hasAllPermissions(): Boolean = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext false
        
        try {
            val granted = client.permissionController.getGrantedPermissions()
            val hasAll = granted.containsAll(REQUIRED_PERMISSIONS)
            
            if (!hasAll) {
                val missing = REQUIRED_PERMISSIONS - granted
                aapsLogger.info(LTag.APS, "[$TAG] permsGranted=${granted.size}/${REQUIRED_PERMISSIONS.size} missing=${missing.size}")
            } else {
                aapsLogger.debug(LTag.APS, "[$TAG] permsGranted=ALL ok=true")
            }
            
            hasAll
        } catch (e: SecurityException) {
            aapsLogger.warn(LTag.APS, "[$TAG] Permission check denied (SecurityException)")
            false
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Permission check error=${e.javaClass.simpleName} msg=${e.message}")
            false
        }
    }
    
    /**
     * Get currently granted permissions
     * SAFE: Returns empty set on error
     */
    suspend fun getGrantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        val client = healthConnectClient ?: return@withContext emptySet()
        
        try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to get granted permissions", e)
            emptySet()
        }
    }
    
    /**
     * Create intent to open Health Connect settings
     * Android 14+: Opens "Manage permissions" for THIS app
     * Android 13-: Opens generic Health Connect settings
     */
    fun createHealthConnectSettingsIntent(): Intent {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // Android 14+: Open manage permissions for this app specifically
                Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                    putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                // Android 13-: Open generic settings or store
                Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Settings intent creation error", e)
            // Fallback to app details
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }
    
    /**
     * Detailed Health Connect State for UI/Diagnostics
     */
    enum class HealthConnectState {
        NotSupported,      // SDK not compatible (Android < 9 or restricted)
        Unavailable,       // SDK < 34 and app not installed
        UpdateRequired,    // Provider update needed
        NeedsPermissions,  // Permissions denied/revoked
        Ready,             // All good
        Error              // Calculation error
    }

    /**
     * Determine the current functional state
     */
    suspend fun determineState(): HealthConnectState = withContext(Dispatchers.IO) {
        val sdkStatus = try {
            getSdkStatus()
        } catch (e: Exception) {
            return@withContext HealthConnectState.Error
        }

        when (sdkStatus) {
            HealthConnectClient.SDK_UNAVAILABLE -> return@withContext HealthConnectState.Unavailable
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return@withContext HealthConnectState.UpdateRequired
            HealthConnectClient.SDK_AVAILABLE -> {
                // SDK is there, check permissions
                if (hasAllPermissions()) {
                    HealthConnectState.Ready
                } else {
                    HealthConnectState.NeedsPermissions
                }
            }
            else -> HealthConnectState.NotSupported
        }
    }

    /**
     * Compact log for debugging/audit
     * Format: "AIMI HC: sdkStatus=Available permsGranted=4/4 ok=true state=Ready"
     */
    suspend fun getStatusLog(): String = withContext(Dispatchers.IO) {
        val state = determineState()
        val granted = getGrantedPermissions()
        val ok = granted.containsAll(REQUIRED_PERMISSIONS)
        
        "AIMI HC: state=$state perms=${granted.size}/${REQUIRED_PERMISSIONS.size} ok=$ok"
    }
}
