package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.DexcomOnePlusStatusActions
import app.aaps.plugins.source.compose.DexcomOnePlusStatusOverview
import app.aaps.plugins.source.compose.DexcomOnePlusStatusViewModel
import app.aaps.plugins.source.logs.DriverLogFilter
import javax.inject.Inject

/**
 * Daily status of the native Dexcom ONE+ / G7 source, as a window of its own.
 *
 * The screen is shared code, see [DexcomOnePlusStatusOverview]; the plugin tab shows the same one
 * behind its sensor button. This Activity stays for the places that cannot open the plugin tab:
 * the session notification, and the start and warm-up screens, which are Activities themselves.
 * The status is no longer an entry of the settings, so it is not reachable there twice.
 */
class DexcomOnePlusStatusActivity : MetroAppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Other apps may not draw over this screen: an overlay could hide what a tap really does, here on a
        // sensor (start, calibration, pre-soak). Same as ComposeMainActivity. Needs HIDE_OVERLAY_WINDOWS.
        window.setHideOverlayWindows(true)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    val viewModel: DexcomOnePlusStatusViewModel = metroViewModel()
                    val actions = remember {
                        DexcomOnePlusStatusActions(
                            onOpenStart = { startActivity(Intent(this, DexcomOnePlusStartActivity::class.java)) },
                            onOpenWarmup = { startActivity(Intent(this, DexcomOnePlusWarmupActivity::class.java)) },
                            // The full driver log window, with the Libre 3 lines one tap away.
                            onOpenLog = {
                                startActivity(
                                    Intent(this, CgmDriverLogActivity::class.java)
                                        .putExtra(CgmDriverLogActivity.EXTRA_FILTER, DriverLogFilter.DEXCOM_ONE_PLUS.name)
                                )
                            },
                        )
                    }
                    CgmScaffold(
                        title = stringResource(R.string.dexcom_oneplus_status_title),
                        onNavigate = { finish() },
                    ) {
                        DexcomOnePlusStatusOverview(viewModel = viewModel, actions = actions)
                    }
                }
            }
        }
    }
}
