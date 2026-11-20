package app.aaps.plugins.source.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.camera.CameraAccess
import app.aaps.core.ui.compose.camera.IosCodeCamera
import app.aaps.core.ui.compose.camera.openIosAppSettings
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.source.SourceStrings
import platform.AVFoundation.AVMetadataObjectTypeDataMatrixCode
import platform.AVFoundation.AVMetadataObjectTypeQRCode

/**
 * The applicator scanner on iPhone: the shared AVFoundation camera of `:core:ui`.
 *
 * Reads the GS1 Data Matrix printed on the applicator, and QR as well, the same two types as
 * Android. The text is checked by the shared parser in `OnePlusApplicatorScanDialog`, so another
 * code in front of the camera is ignored. iOS can give a GS1 Data Matrix without its separators;
 * the parser reads the Dexcom layout either way.
 *
 * The camera permission is asked by iOS the first time the scan opens, with the text of
 * `NSCameraUsageDescription`.
 */
object IosApplicatorScanner : OnePlusApplicatorScanner {

    @Composable
    override fun CameraView(onScanned: (String) -> Boolean, modifier: Modifier) {
        val currentOnScanned = rememberUpdatedState(onScanned)
        // Set once a code was taken, so the frames still arriving do not deliver it a second time.
        // Every callback comes on the main queue, so a plain holder is enough.
        val done = remember { BooleanArray(1) }
        IosCodeCamera(
            codeTypes = listOfNotNull(AVMetadataObjectTypeDataMatrixCode, AVMetadataObjectTypeQRCode),
            onScanned = { text ->
                if (!done[0] && currentOnScanned.value(text)) done[0] = true
            },
            modifier = modifier,
            noPicture = { access, messageModifier -> CameraMessage(access, messageModifier) },
        )
    }
}

/** Says why there is no picture, and that the code can still be typed. */
@Composable
private fun CameraMessage(access: CameraAccess, modifier: Modifier) {
    Box(modifier = modifier.padding(AapsSpacing.xxLarge), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Text(
                text = stringResource(
                    when (access) {
                        CameraAccess.Denied  -> SourceStrings.dexcom_oneplus_applicator_scan_camera_refused
                        CameraAccess.Blocked -> SourceStrings.dexcom_oneplus_applicator_scan_camera_blocked
                        else                 -> SourceStrings.dexcom_oneplus_applicator_scan_camera_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            // Once iOS has been told no, asking again shows no prompt; only the settings can mend
            // it. Restricted (parental controls, a management profile) cannot be changed there.
            if (access == CameraAccess.Denied) {
                Button(onClick = { openIosAppSettings() }) {
                    Text(stringResource(SourceStrings.dexcom_oneplus_applicator_scan_open_settings))
                }
            }
        }
    }
}
