package app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.IDs
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.EstimationResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.AimiImage
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.toAimiImage
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.compose.ProviderDropdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun MealAdvisorCameraCapture(
    aapsLogger: AAPSLogger,
    onCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { MealAdvisorCameraController(context, aapsLogger) }
    val cameraPermissionDeniedMessage = stringResource(R.string.aimi_meal_advisor_camera_permission_denied_message)
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            hasCameraPermission = true
        } else {
            onError(cameraPermissionDeniedMessage)
        }
    }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var wasStoppedForPause by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE  -> {
                    controller.stop()
                    wasStoppedForPause = true
                }
                // Only restart what our own ON_PAUSE actually stopped - Lifecycle replays ON_RESUME to
                // any observer added while already resumed, which would otherwise restart the camera a
                // second time right on screen entry, on top of the AndroidView factory's own start().
                Lifecycle.Event.ON_RESUME -> if (wasStoppedForPause) {
                    wasStoppedForPause = false
                    textureView?.let { controller.start(it, onCaptured = onCaptured, onError = onError) }
                }
                else                       -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    if (!hasCameraPermission) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    textureView = this
                    controller.start(this, onCaptured = onCaptured, onError = onError)
                }
            },
        )
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(AapsSpacing.medium),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                tint = Color.White,
            )
        }
        Button(
            onClick = { controller.capture(onError) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = AapsSpacing.xxLarge),
        ) {
            Text(stringResource(R.string.aimi_meal_advisor_capture_button))
        }
    }
}

/**
 * Direct Camera2 port of the parked `MealAdvisorCameraActivity`: forces the back camera (falls back to
 * the first available one), previews into the given [TextureView], and hands a decoded, correctly
 * rotated [Bitmap] back through [start]'s callback.
 *
 * Rotation fix: the parked Activity hardcoded `Matrix.postRotate(90f)`, which only looked right in one
 * physical device orientation. [jpegRotationDegrees] instead combines the camera's fixed
 * [CameraCharacteristics.SENSOR_ORIENTATION] with the display's *current* rotation, so a photo taken
 * with the device held any way round comes out right-side up. `CaptureRequest.JPEG_ORIENTATION` is left
 * untouched (device HALs vary in whether that field rotates pixel data or only an EXIF tag we don't
 * read) - all rotation here is done ourselves in software, once, so it can't double-apply.
 */
private class MealAdvisorCameraController(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var sensorOrientation: Int = 0
    private var isFrontFacing: Boolean = false

    fun start(textureView: TextureView, onCaptured: (Bitmap) -> Unit, onError: (String) -> Unit) {
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera(textureView, onCaptured, onError)
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(textureView, onCaptured, onError)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    fun stop() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE camera stop failed", e)
        }
        stopBackgroundThread()
    }

    fun capture(onError: (String) -> Unit) {
        val device = cameraDevice
        val reader = imageReader
        if (device == null || reader == null) {
            onError(context.getString(R.string.aimi_meal_advisor_camera_not_ready_message))
            return
        }
        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureSession?.stopRepeating()
            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE capture failed", e)
            postError(onError, e.message)
        }
    }

    private fun startBackgroundThread() {
        val thread = HandlerThread("MealAdvisorCameraBackground")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
        } catch (e: InterruptedException) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE background thread join interrupted", e)
        }
        backgroundThread = null
        backgroundHandler = null
    }

    private fun openCamera(textureView: TextureView, onCaptured: (Bitmap) -> Unit, onError: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError(onError, context.getString(R.string.aimi_meal_advisor_camera_permission_denied_message))
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            postError(onError, context.getString(R.string.aimi_meal_advisor_camera_not_ready_message))
            return
        }
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                postError(onError, context.getString(R.string.aimi_meal_advisor_camera_not_ready_message))
                return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            isFrontFacing = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT

            val outputSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: Size(1920, 1080)

            val reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage()
                if (image == null) return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                val bitmap = decodeAndRotate(bytes, onError)
                if (bitmap != null) mainHandler.post { onCaptured(bitmap) }
            }, backgroundHandler)
            imageReader = reader

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession(camera, textureView, outputSize, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    postError(onError, context.getString(R.string.aimi_meal_advisor_camera_error_message, error))
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE open camera failed", e)
            postError(onError, e.message)
        }
    }

    private fun createPreviewSession(camera: CameraDevice, textureView: TextureView, outputSize: Size, onError: (String) -> Unit) {
        try {
            val texture = textureView.surfaceTexture
            val reader = imageReader
            if (texture == null || reader == null) return
            texture.setDefaultBufferSize(outputSize.width, outputSize.height)
            val surface = Surface(texture)

            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)

            camera.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        try {
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        } catch (e: Exception) {
                            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE repeating request failed", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        postError(onError, context.getString(R.string.aimi_meal_advisor_camera_not_ready_message))
                    }
                },
                backgroundHandler,
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE preview session failed", e)
            postError(onError, e.message)
        }
    }

    private fun jpegRotationDegrees(): Int {
        val deviceDegrees = when (ContextCompat.getDisplayOrDefault(context).rotation) {
            Surface.ROTATION_0   -> 0
            Surface.ROTATION_90  -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else                 -> 0
        }
        return if (isFrontFacing) (sensorOrientation + deviceDegrees) % 360 else (sensorOrientation - deviceDegrees + 360) % 360
    }

    private fun decodeAndRotate(jpegBytes: ByteArray, onError: (String) -> Unit): Bitmap? {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            if (bitmap == null) {
                postError(onError, context.getString(R.string.aimi_meal_advisor_camera_not_ready_message))
                return null
            }
            val rotationDegrees = jpegRotationDegrees()
            if (rotationDegrees == 0) return bitmap
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE decode/rotate failed", e)
            postError(onError, e.message)
            null
        }
    }

    private fun postError(onError: (String) -> Unit, message: String?) {
        mainHandler.post { onError(message ?: context.getString(R.string.aimi_meal_advisor_camera_not_ready_message)) }
    }
}
