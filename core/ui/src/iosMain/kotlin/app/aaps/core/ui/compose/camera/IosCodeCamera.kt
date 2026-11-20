@file:OptIn(ExperimentalForeignApi::class)

package app.aaps.core.ui.compose.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t

/**
 * What the camera can do for us right now. Kept apart from the UI so [cameraAccess] can be tested.
 */
enum class CameraAccess { Asking, Ready, Denied, Blocked, Unavailable }

/**
 * Turns the two things iOS tells us into one state.
 *
 * Pure on purpose: everything else in this file needs a real camera, this does not.
 *
 * [status] is an `AVAuthorizationStatus`, which Kotlin/Native sees as a plain [Long] and not as an
 * enum, so the `when` needs an `else`. An unknown future status is treated as "ask": a refusal then
 * lands on [CameraAccess.Denied] and corrects itself.
 */
fun cameraAccess(hasDevice: Boolean, status: Long): CameraAccess =
    if (!hasDevice) CameraAccess.Unavailable
    else when (status) {
        AVAuthorizationStatusAuthorized    -> CameraAccess.Ready
        AVAuthorizationStatusDenied        -> CameraAccess.Denied
        AVAuthorizationStatusRestricted    -> CameraAccess.Blocked
        AVAuthorizationStatusNotDetermined -> CameraAccess.Asking
        else                               -> CameraAccess.Asking
    }

/**
 * Hands the text of every code it sees to [onText].
 *
 * Held strongly by [CodeCapture] for as long as the picture is on screen. `metadataObjectsDelegate`
 * is declared `readonly, nullable` with no `weak`, so ARC should retain it - but this repo has
 * already been caught by the opposite: `IosNotificationDelegate` records that a locally created
 * delegate is collected and the callbacks simply stop, which looks exactly like iOS never calling
 * back.
 */
private class CodeMetadataDelegate(
    private val onText: State<(String) -> Unit>
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection
    ) {
        for (found in didOutputMetadataObjects) {
            // stringValue is null when the payload makes no string. Skip it, the way the Android
            // side simply swallows a frame it cannot decode.
            val text = (found as? AVMetadataMachineReadableCodeObject)?.stringValue
            if (!text.isNullOrEmpty()) onText.value(text)
        }
    }
}

/**
 * The view the preview layer is drawn into.
 *
 * The layer needs its frame set again whenever the view is laid out; `update` on [UIKitView] is not
 * the place for that, because it runs on state change and not on layout.
 */
private class CameraPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer
) : UIView(frame = CGRectZero.readValue()) {

    init {
        clipsToBounds = true
        layer.addSublayer(previewLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // Without this the first layout animates the resize, which looks like the picture sliding in.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.setFrame(bounds)
        CATransaction.commit()
    }
}

/**
 * The camera session, its delegate, its queue and its preview layer, as one thing with one
 * [dispose].
 *
 * Built by [open], which answers null when this device cannot give a picture or reads none of the
 * asked code types. Nothing here writes Compose state, so it is safe to build inside `remember`.
 */
private class CodeCapture(
    private val session: AVCaptureSession,
    private val output: AVCaptureMetadataOutput,
    private val queue: dispatch_queue_t,
    @Suppress("unused") private val delegate: CodeMetadataDelegate,
    val previewLayer: AVCaptureVideoPreviewLayer
) {

    fun dispose() {
        // Clear the delegate first and synchronously, so no code can arrive after this call even
        // while the session is still winding down.
        output.setMetadataObjectsDelegate(null, null)
        // Same serial queue the start went on, so the stop is ordered after it with no race.
        dispatch_async(queue) { session.stopRunning() }
        previewLayer.removeFromSuperlayer()
    }

    companion object {

        fun open(codeTypes: List<String>, onText: State<(String) -> Unit>): CodeCapture? {
            val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return null
            val input = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                AVCaptureDeviceInput.deviceInputWithDevice(device = device, error = error.ptr)
            } ?: return null

            val session = AVCaptureSession()
            val output = AVCaptureMetadataOutput()

            // The input and the output have to be paired before a code type may be asked for or
            // set: which types are available depends on the port the connection is made to.
            session.beginConfiguration()
            if (!session.canAddInput(input)) {
                session.commitConfiguration()
                return null
            }
            session.addInput(input)
            if (!session.canAddOutput(output)) {
                session.commitConfiguration()
                return null
            }
            session.addOutput(output)
            session.commitConfiguration()

            val delegate = CodeMetadataDelegate(onText)
            // The main queue is both allowed (not null, and serial) and what we want: the only work
            // per frame is handing one string to the caller, which goes on to touch Compose state.
            output.setMetadataObjectsDelegate(objectsDelegate = delegate, queue = dispatch_get_main_queue())

            // setMetadataObjectTypes throws NSInvalidArgumentException for a type this connection
            // does not offer, and Kotlin/Native does not turn an NSException into a Kotlin one - it
            // ends the process with no Kotlin stack. So only the offered ones are set. Leaving the
            // types empty is just as wrong the other way: the output then reports nothing at all,
            // and a live picture that decodes nothing reads as a bad code.
            val offered = output.availableMetadataObjectTypes
            val usable = codeTypes.filter { type -> offered.any { it == type } }
            if (usable.isEmpty()) {
                output.setMetadataObjectsDelegate(null, null)
                return null
            }
            output.metadataObjectTypes = usable

            val previewLayer = AVCaptureVideoPreviewLayer(session = session)
            previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill

            // startRunning blocks, so it must not be on the main thread.
            val queue = dispatch_queue_create("app.aaps.codescan", null)
            dispatch_async(queue) { session.startRunning() }

            return CodeCapture(session, output, queue, delegate, previewLayer)
        }
    }
}

/**
 * The camera picture, handing the text of every code of [codeTypes] it reads to [onScanned].
 *
 * [codeTypes] are `AVMetadataObjectType` values, for example `AVMetadataObjectTypeQRCode` or
 * `AVMetadataObjectTypeDataMatrixCode`. When there is no picture - permission not given, no camera,
 * none of the types offered - [noPicture] is drawn instead, so each caller says in its own words
 * what the person can do (pair with the PIN, type the code). It is not drawn while the system
 * prompt is on screen.
 *
 * Nothing is done when the app goes to the background and comes back. AVCaptureSession keeps a
 * `startRunning` request across that by itself, and only an explicit `stopRunning` gives it up -
 * so adding lifecycle handling here would be undoing something that already works.
 */
@Composable
fun IosCodeCamera(
    codeTypes: List<String>,
    onScanned: (String) -> Unit,
    modifier: Modifier,
    noPicture: @Composable (CameraAccess, Modifier) -> Unit,
) {
    val currentOnScanned = rememberUpdatedState(onScanned)
    val hasDevice = remember { AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) != null }
    var access by remember {
        mutableStateOf(cameraAccess(hasDevice, AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)))
    }

    LaunchedEffect(access) {
        if (access == CameraAccess.Asking) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { allowed ->
                // Apple calls this back on a queue of its choosing, so hop to the main one before
                // touching Compose state.
                dispatch_async(dispatch_get_main_queue()) {
                    access = if (allowed) CameraAccess.Ready else CameraAccess.Denied
                }
            }
        }
    }

    // Keyed on access so a caller's own "Try again" path - which takes the camera out of composition
    // and puts a fresh one back - cannot leave two sessions running at once.
    val capture = remember(access) {
        if (access == CameraAccess.Ready) CodeCapture.open(codeTypes, currentOnScanned) else null
    }
    DisposableEffect(capture) { onDispose { capture?.dispose() } }

    when {
        capture != null               -> UIKitView(
            factory = { CameraPreviewView(capture.previewLayer) },
            modifier = modifier,
            properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false)
        )

        // The system prompt is on screen; anything behind it is noise. Same as Android.
        access == CameraAccess.Asking -> Box(modifier)

        // Ready but no capture: the camera is there and allowed, but gave no picture or reads none
        // of the asked types. For the person that is the same as no camera.
        access == CameraAccess.Ready  -> noPicture(CameraAccess.Unavailable, modifier)

        else                          -> noPicture(access, modifier)
    }
}

/** Opens this app's page in the iOS settings, where the camera can be allowed again. */
fun openIosAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}
