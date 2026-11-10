package app.aaps.plugins.configuration.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract

/**
 * Launches the system flow to request ignore-battery-optimizations for this package.
 * Used by [DaggerAppCompatActivityWithResult] and [app.aaps.plugins.configuration.AndroidPermissionImpl].
 */
class OptimizationPermissionContract : ActivityResultContract<Void?, Void?>() {

    override fun createIntent(context: Context, input: Void?): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent()
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Void? = null
}
