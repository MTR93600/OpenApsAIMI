package app.aaps.plugins.configuration.activities

import android.content.Context
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.locale.LocaleHelper
import dagger.android.support.DaggerAppCompatActivity
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

/**
 * Base activity for [app.aaps.plugins.configuration.setupwizard.SetupWizardActivity].
 * Registers activity-result launchers used by [app.aaps.plugins.configuration.AndroidPermissionImpl]
 * (multi-permission + battery optimization). Cloud/setup flows live in the main Compose host.
 */
open class DaggerAppCompatActivityWithResult : DaggerAppCompatActivity() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var aapsLogger: AAPSLogger

    private val compositeDisposable = CompositeDisposable()

    var requestMultiplePermissions: ActivityResultLauncher<Array<String>>? = null
    var callForBatteryOptimization: ActivityResultLauncher<Void?>? = null
    var onPermissionResultDenied: ((List<String>) -> Unit)? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMultiplePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val denied = mutableListOf<String>()
            permissions.entries.forEach {
                aapsLogger.info(LTag.CORE, "Permission ${it.key} ${it.value}")
                if (!it.value) denied.add(it.key)
            }
            if (denied.isNotEmpty()) onPermissionResultDenied?.invoke(denied)
            updateButtons()
        }

        callForBatteryOptimization = registerForActivityResult(OptimizationPermissionContract()) {
            updateButtons()
        }
    }

    override fun onDestroy() {
        compositeDisposable.clear()
        requestMultiplePermissions = null
        callForBatteryOptimization = null
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    open fun updateButtons() {}
}
