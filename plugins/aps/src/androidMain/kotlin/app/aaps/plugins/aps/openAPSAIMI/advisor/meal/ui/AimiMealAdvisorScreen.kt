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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.FoodRecognitionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val ADVISOR_PROVIDERS = listOf("OPENAI", "GEMINI", "DEEPSEEK", "CLAUDE")

/**
 * Compose port of the parked `MealAdvisorActivity` + `MealAdvisorCameraActivity` ("Snap & Go").
 *
 * One screen, not two: this project's Compose preference mechanism is `ComposeScreenContent { onBack -> ... }`,
 * one screen per entry with no "startActivityForResult"-style contract to hand a photo between two screens.
 * `showCamera` toggles between the input/result layout and a full-screen Camera2 capture layout instead.
 *
 * Camera2 (not CameraX): this repo has no CameraX dependency anywhere, and the parked Activity already
 * used Camera2 directly to force back-camera selection, so the capture logic below is a straight port of
 * that, not a new design.
 *
 * The confirm-flow preference keys ([BooleanKey.OApsAIMIMealAdvisorTrigger], [DoubleKey.OApsAIMILastEstimatedCarbs],
 * [DoubleKey.OApsAIMILastEstimatedCarbTime]) and the `MEAL_ADVISOR_TRACE` debug log prefix must not change:
 * the dosing loop reads them for a one-shot SMB bypass right after a logged meal.
 */
@Composable
fun AimiMealAdvisorScreen(
    preferences: Preferences,
    persistenceLayer: PersistenceLayer,
    profileFunction: ProfileFunction,
    aapsLogger: AAPSLogger,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val recognitionService = remember { FoodRecognitionService(context, preferences) }

    var showCamera by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(preferences.get(StringKey.AimiAdvisorProvider).uppercase(Locale.ROOT)) }
    var descriptionText by remember { mutableStateOf("") }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<EstimationResult?>(null) }
    var carbsText by remember { mutableStateOf("") }
    var proposedDoseUnits by remember { mutableStateOf(0.0) }
    var confirming by remember { mutableStateOf(false) }

    val cameraPermissionDeniedMessage = stringResource(R.string.aimi_meal_advisor_camera_permission_denied_message)
    val analyzeErrorPrefix = stringResource(R.string.aimi_meal_advisor_analyze_error_prefix)
    val confirmErrorPrefix = stringResource(R.string.aimi_meal_advisor_confirm_error_prefix)
    val confirmedMessage = stringResource(R.string.aimi_meal_advisor_confirmed_message)
    val confirmButtonTemplate = stringResource(R.string.aimi_meal_advisor_confirm_button)

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCamera = true
        } else {
            scope.launch { snackbarHostState.showSnackbar(cameraPermissionDeniedMessage) }
        }
    }

    fun analyze(bitmap: Bitmap) {
        analyzing = true
        scope.launch {
            try {
                val estimate = recognitionService.estimateCarbsFromImage(bitmap, descriptionText)
                result = estimate
                carbsText = estimate.recommendedCarbsForDose.toInt().toString()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE analyze failed", e)
                snackbarHostState.showSnackbar(String.format(Locale.getDefault(), analyzeErrorPrefix, e.message ?: ""))
            } finally {
                analyzing = false
            }
        }
    }

    LaunchedEffect(carbsText) {
        val carbs = carbsText.toDoubleOrNull() ?: 0.0
        val profile = withContext(Dispatchers.IO) { profileFunction.getProfile() }
        val ic = if (profile != null && profile.getIc() > 0.1) profile.getIc() else 10.0
        proposedDoseUnits = carbs / ic
    }

    fun onAnalyzeClick() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            showCamera = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun onCaptured(bitmap: Bitmap) {
        showCamera = false
        capturedBitmap = bitmap
        analyze(bitmap)
    }

    fun onCameraError(message: String) {
        showCamera = false
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun confirmInjection() {
        val carbs = carbsText.toDoubleOrNull() ?: return
        if (carbs <= 0.0 || confirming) return
        confirming = true
        scope.launch(Dispatchers.IO) {
            try {
                val ca = CA(
                    timestamp = System.currentTimeMillis(),
                    isValid = true,
                    duration = 0,
                    amount = carbs,
                    notes = "AIMI Meal Advisor: ${result?.description ?: ""}",
                    ids = IDs(),
                )
                persistenceLayer.insertOrUpdateCarbs(ca, Action.TREATMENT, Sources.CarbDialog, ca.notes)

                preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, true)
                preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, carbs)
                val nowMs = System.currentTimeMillis()
                preferences.put(DoubleKey.OApsAIMILastEstimatedCarbTime, nowMs.toDouble())
                aapsLogger.debug(
                    LTag.APS,
                    "MEAL_ADVISOR_TRACE confirmInjection carbs=${"%.1f".format(Locale.ROOT, carbs)}g trigger=true estimateTimeMs=$nowMs",
                )

                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar(confirmedMessage)
                    delay(900)
                    onBack()
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE confirmInjection failed", e)
                withContext(Dispatchers.Main) {
                    confirming = false
                    snackbarHostState.showSnackbar(String.format(Locale.getDefault(), confirmErrorPrefix, e.message ?: ""))
                }
            }
        }
    }

    if (showCamera) {
        MealAdvisorCameraCapture(
            aapsLogger = aapsLogger,
            onCaptured = ::onCaptured,
            onError = ::onCameraError,
            onCancel = { showCamera = false },
        )
        return
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_meal_advisor_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = AapsSpacing.large)
                    .padding(bottom = AapsSpacing.xxLarge)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.aimi_meal_advisor_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )

                ProviderDropdown(
                    selected = provider,
                    onSelect = {
                        provider = it
                        preferences.put(StringKey.AimiAdvisorProvider, it)
                    },
                )

                PhotoPreview(bitmap = capturedBitmap)

                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.aimi_meal_advisor_description_hint)) },
                    minLines = 1,
                    maxLines = 3,
                )

                Button(
                    onClick = { onAnalyzeClick() },
                    enabled = !analyzing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (analyzing) R.string.aimi_meal_advisor_analyzing_button
                            else R.string.aimi_meal_advisor_analyze_button
                        )
                    )
                }
                if (analyzing) {
                    CircularProgressIndicator(modifier = Modifier.padding(AapsSpacing.small))
                }

                val currentResult = result
                if (currentResult != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.medium))
                    MealAdvisorResultSection(
                        result = currentResult,
                        carbsText = carbsText,
                        onCarbsChange = { carbsText = it },
                    )
                    Button(
                        onClick = { confirmInjection() },
                        enabled = !confirming && (carbsText.toDoubleOrNull() ?: 0.0) > 0.0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            String.format(
                                Locale.getDefault(),
                                confirmButtonTemplate,
                                carbsText.toDoubleOrNull()?.toInt() ?: 0,
                                proposedDoseUnits,
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoPreview(bitmap: Bitmap?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.aimi_meal_advisor_photo_content_description),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.aimi_meal_advisor_photo_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val displayNames = mapOf(
        "OPENAI" to stringResource(R.string.aimi_prefs_provider_openai),
        "GEMINI" to stringResource(R.string.aimi_prefs_provider_gemini),
        "DEEPSEEK" to stringResource(R.string.aimi_prefs_provider_deepseek),
        "CLAUDE" to stringResource(R.string.aimi_prefs_provider_claude),
    )
    val selectedText = displayNames[selected] ?: displayNames.getValue("OPENAI")

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.aimi_meal_advisor_provider_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ADVISOR_PROVIDERS.forEach { providerId ->
                DropdownMenuItem(
                    text = { Text(displayNames.getValue(providerId)) },
                    onClick = {
                        onSelect(providerId)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun MealAdvisorResultSection(
    result: EstimationResult,
    carbsText: String,
    onCarbsChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        ConfidenceBadge(confidence = result.confidence)

        Text(text = result.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)

        Text(
            text = String.format(
                Locale.getDefault(),
                stringResource(R.string.aimi_meal_advisor_macro_summary),
                result.protein.estimate,
                result.fat.estimate,
                result.fpuEquivalent,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (result.hiddenCarbRisk == "HIGH" || result.needsManualConfirmation) {
            RiskBanner(result = result)
        }

        Text(
            text = stringResource(R.string.aimi_meal_advisor_identified_items_title),
            style = MaterialTheme.typography.titleSmall,
        )
        result.visibleItems.forEach { item ->
            Text(
                text = String.format(Locale.getDefault(), stringResource(R.string.aimi_meal_advisor_item_row), item.name, item.amountInfo),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = String.format(Locale.getDefault(), stringResource(R.string.aimi_meal_advisor_reasoning_prefix), result.reasoning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = carbsText,
            onValueChange = onCarbsChange,
            label = { Text(stringResource(R.string.aimi_meal_advisor_carbs_field_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = result.recommendedCarbsReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfidenceBadge(confidence: String) {
    val (labelRes, containerColor) = when (confidence.uppercase(Locale.ROOT)) {
        "HIGH"   -> R.string.aimi_meal_advisor_confidence_high to MaterialTheme.colorScheme.primaryContainer
        "MEDIUM" -> R.string.aimi_meal_advisor_confidence_medium to MaterialTheme.colorScheme.secondaryContainer
        else     -> R.string.aimi_meal_advisor_confidence_low to MaterialTheme.colorScheme.errorContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small),
        )
    }
}

@Composable
private fun RiskBanner(result: EstimationResult) {
    val prefixRes = when {
        result.hiddenCarbRisk == "HIGH" && result.needsManualConfirmation -> R.string.aimi_meal_advisor_risk_high_and_manual
        result.hiddenCarbRisk == "HIGH"                                   -> R.string.aimi_meal_advisor_risk_high
        else                                                              -> R.string.aimi_meal_advisor_risk_manual
    }
    val notes = result.insulinRelevantNotes.joinToString(", ").ifBlank {
        stringResource(R.string.aimi_meal_advisor_risk_notes_default)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text = String.format(Locale.getDefault(), stringResource(R.string.aimi_meal_advisor_risk_banner), stringResource(prefixRes), notes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(AapsSpacing.medium),
        )
    }
}

@Composable
private fun MealAdvisorCameraCapture(
    aapsLogger: AAPSLogger,
    onCaptured: (Bitmap) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { MealAdvisorCameraController(context, aapsLogger) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var wasStoppedForPause by remember { mutableStateOf(false) }

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
