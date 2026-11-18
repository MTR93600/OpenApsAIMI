package app.aaps.core.ui.compose.pump

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/**
 * iOS: turns the idle timer off, so the phone does not lock while a pump wizard waits for the person.
 * If the phone locked, the app would go to the background and the pairing or priming would then
 * depend on Bluetooth in the background.
 *
 * `idleTimerDisabled` holds only while the app is on screen, and the person can still lock the phone
 * by hand. The value from before is put back when the wizard closes ([ScreenAwake]).
 */
@Composable
actual fun KeepScreenOnEffect() {
    DisposableEffect(Unit) {
        val awake = ScreenAwake(
            read = { UIApplication.sharedApplication.idleTimerDisabled },
            write = { UIApplication.sharedApplication.idleTimerDisabled = it }
        )
        awake.hold()
        onDispose { awake.release() }
    }
}
