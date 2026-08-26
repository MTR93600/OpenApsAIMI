package app.aaps.plugins.source

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Asks for the Bluetooth permissions the Libre 3 start needs.
 *
 * NFC is not here on purpose: it is granted when the app is installed, so it never has to be
 * asked for while the user is standing there with a sensor on their arm.
 */
object Libre3BlePermissionHelper {

    const val REQUEST_CODE: Int = 44701

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(activity: Activity): Array<String> =
        requiredPermissions()
            .filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
            .toTypedArray()

    fun hasAll(activity: Activity): Boolean = missingPermissions(activity).isEmpty()

    fun requestMissing(activity: Activity) {
        val missing = missingPermissions(activity)
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(activity, missing, REQUEST_CODE)
    }
}
