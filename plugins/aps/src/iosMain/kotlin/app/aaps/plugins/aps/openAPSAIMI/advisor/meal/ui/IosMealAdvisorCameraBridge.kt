package app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.AimiImage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSData
import platform.UIKit.UIViewController

/**
 * Swift owns the AVFoundation session and preview; Compose owns the user-facing capture/cancel
 * controls and the capture -> estimate transition. No platform image type leaves this boundary.
 */
interface IosMealAdvisorCameraController {
    val viewController: UIViewController
    fun capture()
    fun dispose()
}

interface IosMealAdvisorCameraFactory {
    fun create(
        permissionDeniedMessage: String,
        cameraNotReadyMessage: String,
        onCaptured: (NSData) -> Unit,
        onError: (String) -> Unit,
    ): IosMealAdvisorCameraController
}

private object IosMealAdvisorCameraFactoryRegistry {
    var factory: IosMealAdvisorCameraFactory? = null
}

/** Installed by the Swift app entry point before the Compose graph is created. */
fun installIosMealAdvisorCameraFactory(factory: IosMealAdvisorCameraFactory) {
    IosMealAdvisorCameraFactoryRegistry.factory = factory
}

@Composable
internal fun IosMealAdvisorCameraCapture(
    onCaptured: (AimiImage) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val permissionDeniedMessage = stringResource(ApsStrings.aimi_meal_advisor_camera_permission_denied_message)
    val notReadyMessage = stringResource(ApsStrings.aimi_meal_advisor_camera_not_ready_message)
    val factory = IosMealAdvisorCameraFactoryRegistry.factory
    if (factory == null) {
        // Missing the registered Swift implementation is an explicit, visible failure—not a
        // no-op camera screen.
        LaunchedEffect(Unit) { onError(notReadyMessage) }
        return
    }
    val controller = remember(factory) {
        factory.create(
            permissionDeniedMessage = permissionDeniedMessage,
            cameraNotReadyMessage = notReadyMessage,
            onCaptured = { data -> onCaptured(AimiImage(data.copyBytes())) },
            onError = onError,
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    val captureText = stringResource(ApsStrings.aimi_meal_advisor_capture_button)
    val backText = stringResource(CoreUiStrings.back)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        UIKitViewController(
            factory = { controller.viewController },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = backText,
                tint = Color.White,
            )
        }
        Button(
            onClick = controller::capture,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        ) {
            Text(captureText, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.copyBytes(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    val pointer = bytes ?: return ByteArray(0)
    return pointer.reinterpret<ByteVar>().readBytes(count)
}
