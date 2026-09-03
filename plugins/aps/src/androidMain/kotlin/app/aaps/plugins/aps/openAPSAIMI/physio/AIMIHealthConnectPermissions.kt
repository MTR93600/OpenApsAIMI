package app.aaps.plugins.aps.openAPSAIMI.physio

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * 🔐 AIMI Health Connect Permissions - Central Source of Truth
 *
 * THIS is the ONLY place where Health Connect permissions are declared.
 * All components (Physio, Steps Sync, Activity Provider) MUST reference this file.
 *
 * Thermal permissions (skin + basal body temperature) are **optional**: many devices /
 * Health Connect builds do not expose them in the grant dialog. Core loop permissions
 * must not stay blocked when thermal is unavailable or denied.
 *
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
object AIMIHealthConnectPermissions {

    /**
     * Core physiological permissions (sleep, HRV, heart rate).
     */
    val PHYSIO_CORE_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    )

    /**
     * Optional thermal rhythm (Garmin / Oura). Not required to unblock physio pipeline.
     */
    val THERMAL_OPTIONAL_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(SkinTemperatureRecord::class),
        HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class),
    )

    /**
     * Permissions required for Physiological Module — core only (no thermal gate).
     */
    val PHYSIO_REQUIRED_PERMISSIONS: Set<String> = PHYSIO_CORE_PERMISSIONS

    /**
     * Permissions required for Steps & Activity Sync
     */
    val STEPS_REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    /**
     * Permissions required for AIMI Health Connect to be considered operational.
     */
    val ALL_REQUIRED_PERMISSIONS: Set<String> = PHYSIO_CORE_PERMISSIONS + STEPS_REQUIRED_PERMISSIONS

    /**
     * Human-readable names for logging/UI
     */
    val PERMISSION_NAMES = mapOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class) to "Sleep Sessions",
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) to "Heart Rate Variability (HRV)",
        HealthPermission.getReadPermission(HeartRateRecord::class) to "Heart Rate",
        HealthPermission.getReadPermission(RestingHeartRateRecord::class) to "Resting Heart Rate",
        HealthPermission.getReadPermission(StepsRecord::class) to "Steps",
        HealthPermission.getReadPermission(SkinTemperatureRecord::class) to "Skin Temperature",
        HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class) to "Basal Body Temperature",
    )

    /**
     * Permissions to show in the Health Connect request sheet for this device.
     * Thermal types are included only when the platform advertises skin temperature support.
     */
    suspend fun resolveRequestPermissions(client: HealthConnectClient): Set<String> {
        val request = ALL_REQUIRED_PERMISSIONS.toMutableSet()
        if (isSkinTemperatureSupported(client)) {
            request += THERMAL_OPTIONAL_PERMISSIONS
        } else {
            request += HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class)
        }
        return request
    }

    suspend fun isSkinTemperatureSupported(client: HealthConnectClient): Boolean =
        try {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (_: Exception) {
            false
        }

    fun displayName(permission: String): String =
        PERMISSION_NAMES[permission] ?: permission.substringAfterLast('.')

    /**
     * Checks which **core** permissions are missing from a granted set.
     */
    fun getMissingPermissions(grantedPermissions: Set<String>): Set<String> =
        ALL_REQUIRED_PERMISSIONS.filter { it !in grantedPermissions }.toSet()

    fun getMissingOptionalThermalPermissions(grantedPermissions: Set<String>): Set<String> =
        THERMAL_OPTIONAL_PERMISSIONS.filter { it !in grantedPermissions }.toSet()

    fun hasCorePermissions(grantedPermissions: Set<String>): Boolean =
        grantedPermissions.containsAll(ALL_REQUIRED_PERMISSIONS)

    /**
     * Returns a human-readable summary of missing core permissions.
     */
    fun getMissingPermissionsSummary(grantedPermissions: Set<String>): String {
        val missing = getMissingPermissions(grantedPermissions)
        if (missing.isEmpty()) return "All core permissions granted"

        return "Missing: " + missing.map { displayName(it) }.joinToString(", ")
    }

    fun getMissingOptionalThermalSummary(grantedPermissions: Set<String>): String {
        val missing = getMissingOptionalThermalPermissions(grantedPermissions)
        if (missing.isEmpty()) return "Thermal permissions granted"
        return "Optional thermal not granted: " + missing.map { displayName(it) }.joinToString(", ")
    }
}
