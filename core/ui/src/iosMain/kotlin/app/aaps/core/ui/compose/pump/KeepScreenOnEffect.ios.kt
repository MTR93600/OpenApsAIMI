package app.aaps.core.ui.compose.pump

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/**
 * iOS: turns the idle timer off, so the phone does not lock while the wizard waits for the person.
 *
 * The app may already have it off for its own reason (a "keep screen on" setting), so only the value
 * this effect changed is given back.
 */
@Composable
actual fun KeepScreenOnEffect() {
    DisposableEffect(Unit) {
        val application = UIApplication.sharedApplication
        val wasDisabled = application.idleTimerDisabled
        application.idleTimerDisabled = true
        onDispose {
            if (!wasDisabled) application.idleTimerDisabled = false
        }
    }
}
