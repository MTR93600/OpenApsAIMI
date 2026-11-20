package app.aaps.plugins.sync.nsclientV3.clientcontrol.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.camera.CameraAccess
import app.aaps.core.ui.compose.camera.IosCodeCamera
import app.aaps.core.ui.compose.camera.openIosAppSettings
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import platform.AVFoundation.AVMetadataObjectTypeQRCode

/**
 * The camera picture, handing every QR code it reads to [onScanned].
 *
 * The camera itself is the shared [IosCodeCamera] of `:core:ui`, which the sensor applicator scan
 * uses too. Only the text shown when there is no picture belongs to this screen.
 */
@Composable
internal actual fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier) {
    IosCodeCamera(
        codeTypes = listOfNotNull(AVMetadataObjectTypeQRCode),
        onScanned = onScanned,
        modifier = modifier,
        noPicture = { access, messageModifier -> CameraMessage(access, messageModifier) },
    )
}

/** Says why there is no picture, and offers the one thing that can help where there is one. */
@Composable
private fun CameraMessage(access: CameraAccess, modifier: Modifier) {
    Box(modifier = modifier.padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
        ) {
            Text(
                text = stringResource(
                    when (access) {
                        CameraAccess.Denied  -> SyncStrings.client_setup_scan_camera_denied
                        CameraAccess.Blocked -> SyncStrings.client_setup_scan_camera_blocked
                        else                 -> SyncStrings.client_setup_scan_camera_unavailable
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            // Only where settings can actually fix it. Once iOS has been told no, asking again
            // returns false without showing a prompt, so a "Try again" button here would be a
            // control that visibly does nothing. Restricted is parental controls or a management
            // profile, which the user cannot change at all.
            if (access == CameraAccess.Denied) {
                Button(onClick = { openIosAppSettings() }) {
                    Text(stringResource(SyncStrings.client_setup_scan_open_settings))
                }
            }
        }
    }
}
