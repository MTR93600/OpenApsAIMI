package app.aaps.plugins.aps.openAPSAIMI.sos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.aaps.plugins.aps.R

/**
 * 🚨 AIMI Emergency SOS Permission Request Activity
 *
 * Handles the permission request flow for Location and SMS with a clear UI.
 * Provides fallback options if permissions are denied.
 *
 * @author MTR & Lyra AI
 */
class AIMIEmergencySosPermissionActivityMTR : AppCompatActivity() {

    companion object {
        private const val TAG = "AIMI_SOS_Perms"
    }

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var btnRequestPerms: Button
    private lateinit var btnOpenSettings: Button

    private val requiredForegroundPermissions = arrayOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    // Request launcher for foreground permissions (SMS + Fine/Coarse Location)
    private val requestForegroundPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            val isGranted = it.value
            Log.i(TAG, "${it.key} granted: $isGranted")
            if (!isGranted) allGranted = false
        }
        
        if (allGranted) {
            checkBackgroundLocationPermission()
        } else {
            updateStatusText()
            Toast.makeText(this, "Foreground permissions denied.", Toast.LENGTH_SHORT).show()
        }
    }

    // Request launcher for background location (Android 10+)
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, "ACCESS_BACKGROUND_LOCATION granted: $isGranted")
        updateStatusText()
        if (isGranted) {
            Toast.makeText(this, "All SOS permissions granted!", Toast.LENGTH_LONG).show()
            android.os.Handler(mainLooper).postDelayed({ finish() }, 1500)
        } else {
            Toast.makeText(this, "Background location denied. SOS may not work when App is not in foreground.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aimi_sos_permission)

        tvStatus = findViewById(R.id.tv_status_sos)
        btnRequestPerms = findViewById(R.id.btn_request_perms_sos)
        btnOpenSettings = findViewById(R.id.btn_open_settings_sos)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }

    private fun setupButtons() {
        btnRequestPerms.setOnClickListener {
            // First check foreground permissions
            val missingParams = requiredForegroundPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (missingParams.isNotEmpty()) {
                Log.i(TAG, "Requesting foreground permissions: ${missingParams.joinToString()}")
                requestForegroundPermissionsLauncher.launch(missingParams)
            } else {
                Log.i(TAG, "Foreground permissions already granted. Checking background.")
                checkBackgroundLocationPermission()
            }
        }

        btnOpenSettings.setOnClickListener {
            openAppSettings()
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Requesting background location permission.")
                requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                Log.i(TAG, "Background location already granted.")
                updateStatusText()
            }
        } else {
            // Android 9 and below: background location is granted via FINE_LOCATION implicitly.
            updateStatusText()
        }
    }

    private fun updateStatusText() {
        var allGranted = true
        val sb = StringBuilder()
        sb.append("📊 Permission Status:\n")

        requiredForegroundPermissions.forEach { perm ->
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (!granted) allGranted = false
            sb.append(if (granted) "✅ " else "❌ ").append(perm.substringAfterLast(".")).append("\n")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) allGranted = false
            sb.append(if (bgGranted) "✅ " else "❌ ").append("ACCESS_BACKGROUND_LOCATION\n")
        }

        tvStatus.text = sb.toString()

        if (allGranted) {
            btnRequestPerms.text = "Permissions Granted"
            btnRequestPerms.isEnabled = false
        } else {
            btnRequestPerms.text = "Grant Access"
            btnRequestPerms.isEnabled = true
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening App Settings", e)
            Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
        }
    }
}
