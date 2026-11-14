package app.aaps.pump.omnipod.dashctl.ios.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/**
 * Keeps the iPhone from locking while the pod wizard shows, the iOS counterpart of Android
 * `KeepScreenOnEffect` in `OmnipodWizardScreen`. Part 1 scans, pairs and primes for about a minute;
 * if the phone locks in the middle, the app goes to the background and the activation then depends on
 * Bluetooth in the background, which Android avoids by keeping the screen on.
 *
 * `idleTimerDisabled` holds only while the app is on screen, and the user can still lock the phone by
 * hand. When the wizard closes, the value from before is put back ([DashScreenAwake]), so the
 * "Keep screen on" setting of the overview is not switched off by leaving the wizard.
 */
@Composable
internal fun DashKeepScreenOn() {
    DisposableEffect(Unit) {
        val awake = DashScreenAwake(
            read = { UIApplication.sharedApplication.idleTimerDisabled },
            write = { UIApplication.sharedApplication.idleTimerDisabled = it }
        )
        awake.hold()
        onDispose { awake.release() }
    }
}

/**
 * Holds the screen awake and puts back what was there before. [read] and [write] are the iOS idle
 * timer flag on the phone, and plain values in a test.
 */
internal class DashScreenAwake(private val read: () -> Boolean, private val write: (Boolean) -> Unit) {

    private var before: Boolean? = null

    /** Switches the auto-lock off. A second call changes nothing. */
    fun hold() {
        if (before != null) return
        before = read()
        write(true)
    }

    /** Puts back the value from before [hold]. Without a hold it changes nothing. */
    fun release() {
        val previous = before ?: return
        before = null
        write(previous)
    }
}
