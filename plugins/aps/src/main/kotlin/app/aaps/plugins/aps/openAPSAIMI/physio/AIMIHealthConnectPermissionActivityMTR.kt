package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import app.aaps.plugins.aps.R
import kotlinx.coroutines.launch

/**
 * 🔐 AIMI Health Connect Permission Request Activity
 *
 * Handles the permission request flow with a clear UI.
 * Provides fallback options if permissions are denied.
 *
 * @author MTR & Lyra AI
 */
class AIMIHealthConnectPermissionActivityMTR : AppCompatActivity() {

    companion object {
        private const val TAG = "AIMI_HC_Activity"

        // USE CENTRALIZED PERMISSIONS (Single Source of Truth)
        val REQUIRED_PERMISSIONS = AIMIHealthConnectPermissions.ALL_REQUIRED_PERMISSIONS
    }

    private val healthConnectClient by lazy {
        try {
            HealthConnectClient.getOrCreate(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create HC client", e)
            null
        }
    }

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var btnRequestPerms: Button
    private lateinit var btnOpenSettings: Button

    /**
     * Permission launcher - handles result
     * RE-VERIFIES permissions from source of truth
     */
    private val requestPermissionLauncher: ActivityResultLauncher<Set<String>> =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { grantedFromUi ->
            Log.i(TAG, "HC: permission result callback grantedFromUi=${grantedFromUi.size}/${REQUIRED_PERMISSIONS.size}")

            val client = healthConnectClient
            if (client == null) {
                updateStatus(grantedFromUi)
                return@registerForActivityResult 
            }

            lifecycleScope.launch {
                val nowGranted = client.permissionController.getGrantedPermissions()
                Log.i(TAG, "HC: nowGranted=${nowGranted.size}/${REQUIRED_PERMISSIONS.size} -> $nowGranted")
                updateStatus(nowGranted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aimi_hc_permission)

        // Initialize Views
        tvStatus = findViewById(R.id.tv_status)
        btnRequestPerms = findViewById(R.id.btn_request_perms)
        btnOpenSettings = findViewById(R.id.btn_open_settings)
        
        // B5) Diagnostic logging
        Log.i(TAG, "HC: runtime packageName=$packageName appId=${applicationInfo.processName} sdk=${android.os.Build.VERSION.SDK_INT}")

        setupButtons()
        checkInitialStatus()
    }

    private fun setupButtons() {
        btnRequestPerms.setOnClickListener {
            // B2) Harden grant access
            val sdkStatus = HealthConnectClient.getSdkStatus(this)
            Log.i(TAG, "HC: Requesting EXACT permissions: $REQUIRED_PERMISSIONS")
            Log.i(TAG, "HC: grant click sdkStatus=$sdkStatus thread=${Thread.currentThread().name} pkg=$packageName")

            if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                tvStatus.append("\n❌ Health Connect not available (status=$sdkStatus)")
                
                if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                     // Redirect to Play Store for "Android Health Connect" or system update
                     tvStatus.append("\n⚠️ Update required")
                     try {
                         startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                     } catch (e: Exception) {
                         Log.e(TAG, "HC: Cannot open store", e)
                     }
                } else {
                    openHealthConnectSettings()
                }
                return@setOnClickListener
            }

            val client = healthConnectClient
            if (client == null) {
                Toast.makeText(this, "Health Connect unavailable", Toast.LENGTH_SHORT).show()
                tvStatus.append("\n❌ Client unavailable")
                return@setOnClickListener
            }

            tvStatus.append("\n⬇️ Requesting access...")
            try {
                requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            } catch (e: Exception) {
                Log.e(TAG, "HC: launch error", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                tvStatus.append("\n❌ Launch error: ${e.message}")
            }
        }

        btnOpenSettings.setOnClickListener {
            // Explicitly trigger the fallback logic
            openHealthConnectSettings()
        }
    }

    /**
     * B1) Safe Open Settings
     * Uses official Jetpack action (works on Android 14 framework & Android 13 APK)
     */
    private fun openHealthConnectSettings() {
        val action = HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
        Log.i(TAG, "HC: Opening settings with action='$action'")
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            Log.e(TAG, "HC: API settings intent failed, falling back to App Details", e)
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e2: Exception) {
                Log.e(TAG, "HC: All settings intents failed", e2)
                Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Initial check
     */
    private fun checkInitialStatus() {
        val client = healthConnectClient
        if (client == null) {
            tvStatus.text = "Status: Health Connect Unavailable"
            btnRequestPerms.isEnabled = false
            return
        }

        lifecycleScope.launch {
            try {
                val granted = client.permissionController.getGrantedPermissions()
                Log.i(TAG, "HC: Initial Status: ${granted.size}/${REQUIRED_PERMISSIONS.size} granted")
                updateStatus(granted)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking permissions", e)
                tvStatus.text = "Status: Error checking permissions"
            }
        }
    }

    private fun updateStatus(granted: Set<String>) {
        val hasAll = granted.containsAll(REQUIRED_PERMISSIONS)
        val missing = REQUIRED_PERMISSIONS - granted
        val missingCount = missing.size

        // B4) Pure HC status (no checkSelfPermission)
        tvStatus.append("\n\n📊 Status Update:")
        Log.i(TAG, "HC: updateStatus granted=${granted.size} missing=$missingCount")
        
        val sb = StringBuilder()
        REQUIRED_PERMISSIONS.forEach { perm ->
            val isGranted = granted.contains(perm)
            val icon = if (isGranted) "✅" else "❌"
            sb.append("\n $icon $perm")
        }
        Log.i(TAG, sb.toString())

        if (hasAll) {
            performTestRead()
            tvStatus.append("\n✅ All permissions confirmed!")
            btnRequestPerms.text = "Permissions Granted"
            btnRequestPerms.isEnabled = false
            Toast.makeText(this, "Success!", Toast.LENGTH_SHORT).show()
            android.os.Handler(mainLooper).postDelayed({ finish() }, 1500)
        } else {
            tvStatus.append("\n⚠️ Missing $missingCount permissions")
            btnRequestPerms.text = "GRANT ACCESS ($missingCount MISSING)"
            btnRequestPerms.isEnabled = true
        }
    }
    
    private fun performTestRead() {
        lifecycleScope.launch {
            try {
                Log.i(TAG, "HC: 🧪 Starting TEST READ (last 5 min)...")
                val start = Instant.now().minusSeconds(300)
                val end = Instant.now()
                val client = healthConnectClient
                if (client == null) {
                    Log.e(TAG, "HC: Client null during test read")
                    return@launch
                }
                
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val count = response.records.sumOf { it.count }
                Log.i(TAG, "HC: ✅ TEST READ SUCCESS: $count steps found")
                tvStatus.append("\n\n✅ TEST READ: Success! Found $count steps")
            } catch (e: Exception) {
                Log.e(TAG, "HC: ❌ TEST READ FAILED", e)
                tvStatus.append("\n\n❌ TEST READ FAILED: ${e.message}")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRationaleIntent(intent)
    }
    
    private fun handleRationaleIntent(intent: Intent?) {
        if (intent?.action == "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE") {
            Log.i(TAG, "ℹ️ Rationale Intent Received!")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Health Connect Access")
                .setMessage("AIMI needs access to your steps, heart rate, and sleep data to optimize your insulin delivery. Please grant these permissions in the next screen.")
                .setPositiveButton("OK") { _, _ -> }
                .show()
        }
    }
}
