package app.aaps.plugins.aps.openAPSAIMI.physio

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * 🔐 AIMI Health Connect Permissions - Central Source of Truth
 * 
 * THIS is the ONLY place where Health Connect permissions are declared.
 * All components (Physio, Steps Sync, Activity Provider) MUST reference this file.
 * 
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
object AIMIHealthConnectPermissions {
    
    /**
     * Permissions required for Physiological Module (Sleep, HRV, RHR)
     * Note: BloodPressureRecord removed - insufficient data quality (1×/day) for real-time insulin algorithm
     */
    val PHYSIO_REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )
    
    /**
     * Permissions required for Steps & Activity Sync
     */
    val STEPS_REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class) // For HR fallback
    )
    
    /**
     * ALL permissions required by AIMI (union of all modules)
     * This is what the Permission Activity MUST request
     */
    val ALL_REQUIRED_PERMISSIONS = (PHYSIO_REQUIRED_PERMISSIONS + STEPS_REQUIRED_PERMISSIONS)
    
    /**
     * Human-readable names for logging/UI
     */
    val PERMISSION_NAMES = mapOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class) to "Sleep Sessions",
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) to "Heart Rate Variability (HRV)",
        HealthPermission.getReadPermission(HeartRateRecord::class) to "Heart Rate",
        HealthPermission.getReadPermission(StepsRecord::class) to "Steps"
    )
    
    /**
     * Checks which permissions are missing from a granted set
     */
    fun getMissingPermissions(grantedPermissions: Set<String>): Set<String> {
        return ALL_REQUIRED_PERMISSIONS.filter { it !in grantedPermissions }.toSet()
    }
    
    /**
     * Returns a human-readable summary of missing permissions
     */
    fun getMissingPermissionsSummary(grantedPermissions: Set<String>): String {
        val missing = getMissingPermissions(grantedPermissions)
        if (missing.isEmpty()) return "All permissions granted"
        
        return "Missing: " + missing.mapNotNull { PERMISSION_NAMES[it] }.joinToString(", ")
    }
}
