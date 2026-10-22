package app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.IDs
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.formatTemplate
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.EstimationResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.AimiImage
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.compose.ProviderDropdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose port of the parked `MealAdvisorActivity` + `MealAdvisorCameraActivity` ("Snap & Go").
 *
 * One screen, not two: this project's Compose preference mechanism is `ComposeScreenContent { onBack -> ... }`,
 * one screen per entry with no "startActivityForResult"-style contract to hand a photo between two screens.
 * The actual camera preview is supplied by the platform. The shared UI owns the capture -> estimate
 * -> edit -> confirm flow, while Android Camera2 and iOS AVFoundation own only capture.
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
    cameraCapture: @Composable (
        onCaptured: (AimiImage) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit,
    ) -> Unit,
    analyzeImage: suspend (AimiImage, String) -> EstimationResult,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCamera by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(preferences.get(StringKey.AimiAdvisorProvider).uppercase()) }
    var descriptionText by remember { mutableStateOf("") }
    var capturedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<EstimationResult?>(null) }
    var carbsText by remember { mutableStateOf("") }
    var proposedDoseUnits by remember { mutableStateOf(0.0) }
    var confirming by remember { mutableStateOf(false) }

    val analyzeErrorTemplate = stringResource(ApsStrings.aimi_meal_advisor_analyze_error_prefix)
    val confirmErrorTemplate = stringResource(ApsStrings.aimi_meal_advisor_confirm_error_prefix)
    val confirmedMessage = stringResource(ApsStrings.aimi_meal_advisor_confirmed_message)

    fun analyze(image: AimiImage) {
        analyzing = true
        scope.launch {
            try {
                val estimate = analyzeImage(image, descriptionText)
                result = estimate
                carbsText = estimate.recommendedCarbsForDose.toInt().toString()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "MEAL_ADVISOR_TRACE analyze failed", e)
                snackbarHostState.showSnackbar(formatTemplate(analyzeErrorTemplate, listOf(e.message ?: "")))
            } finally {
                analyzing = false
            }
        }
    }

    LaunchedEffect(carbsText) {
        val carbs = carbsText.toDoubleOrNull() ?: 0.0
        val profile = withContext(aapsIoDispatcher) { profileFunction.getProfile() }
        val ic = if (profile != null && profile.getIc() > 0.1) profile.getIc() else 10.0
        proposedDoseUnits = carbs / ic
    }

    fun onAnalyzeClick() {
        showCamera = true
    }

    fun onCaptured(image: AimiImage) {
        showCamera = false
        capturedImage = image.jpegBytes.decodeToImageBitmap()
        analyze(image)
    }

    fun onCameraError(message: String) {
        showCamera = false
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun confirmInjection() {
        val carbs = carbsText.toDoubleOrNull() ?: return
        if (carbs <= 0.0 || confirming) return
        confirming = true
        scope.launch(aapsIoDispatcher) {
            try {
                val ca = CA(
                    timestamp = aimiWallClockMs(),
                    isValid = true,
                    duration = 0,
                    amount = carbs,
                    notes = "AIMI Meal Advisor: ${result?.description ?: ""}",
                    ids = IDs(),
                )
                persistenceLayer.insertOrUpdateCarbs(ca, Action.TREATMENT, Sources.CarbDialog, ca.notes)

                preferences.put(BooleanKey.OApsAIMIMealAdvisorTrigger, true)
                preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, carbs)
                val nowMs = aimiWallClockMs()
                preferences.put(DoubleKey.OApsAIMILastEstimatedCarbTime, nowMs.toDouble())
                aapsLogger.debug(
                    LTag.APS,
                    "MEAL_ADVISOR_TRACE confirmInjection carbs=${carbs}g trigger=true estimateTimeMs=$nowMs",
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
                    snackbarHostState.showSnackbar(formatTemplate(confirmErrorTemplate, listOf(e.message ?: "")))
                }
            }
        }
    }

    if (showCamera) {
        cameraCapture(::onCaptured, ::onCameraError) { showCamera = false }
        return
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(ApsStrings.aimi_meal_advisor_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreUiStrings.back),
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
                    text = stringResource(ApsStrings.aimi_meal_advisor_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )

                ProviderDropdown(
                    selected = provider,
                    onSelect = {
                        provider = it
                        preferences.put(StringKey.AimiAdvisorProvider, it)
                    },
                )

                PhotoPreview(bitmap = capturedImage)

                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(ApsStrings.aimi_meal_advisor_description_hint)) },
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
                            if (analyzing) ApsStrings.aimi_meal_advisor_analyzing_button
                            else ApsStrings.aimi_meal_advisor_analyze_button
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
                            stringResource(
                                ApsStrings.aimi_meal_advisor_confirm_button,
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
private fun PhotoPreview(bitmap: ImageBitmap?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(ApsStrings.aimi_meal_advisor_photo_content_description),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(ApsStrings.aimi_meal_advisor_photo_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            text = stringResource(
                ApsStrings.aimi_meal_advisor_macro_summary,
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
            text = stringResource(ApsStrings.aimi_meal_advisor_identified_items_title),
            style = MaterialTheme.typography.titleSmall,
        )
        result.visibleItems.forEach { item ->
            Text(
                text = stringResource(ApsStrings.aimi_meal_advisor_item_row, item.name, item.amountInfo),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(
            text = stringResource(ApsStrings.aimi_meal_advisor_reasoning_prefix, result.reasoning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = carbsText,
            onValueChange = onCarbsChange,
            label = { Text(stringResource(ApsStrings.aimi_meal_advisor_carbs_field_label)) },
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
    val (labelRes, containerColor) = when (confidence.uppercase()) {
        "HIGH"   -> ApsStrings.aimi_meal_advisor_confidence_high to MaterialTheme.colorScheme.primaryContainer
        "MEDIUM" -> ApsStrings.aimi_meal_advisor_confidence_medium to MaterialTheme.colorScheme.secondaryContainer
        else     -> ApsStrings.aimi_meal_advisor_confidence_low to MaterialTheme.colorScheme.errorContainer
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
        result.hiddenCarbRisk == "HIGH" && result.needsManualConfirmation -> ApsStrings.aimi_meal_advisor_risk_high_and_manual
        result.hiddenCarbRisk == "HIGH"                                   -> ApsStrings.aimi_meal_advisor_risk_high
        else                                                              -> ApsStrings.aimi_meal_advisor_risk_manual
    }
    val notes = result.insulinRelevantNotes.joinToString(", ").ifBlank {
        stringResource(ApsStrings.aimi_meal_advisor_risk_notes_default)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text = stringResource(ApsStrings.aimi_meal_advisor_risk_banner, stringResource(prefixRes), notes),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(AapsSpacing.medium),
        )
    }
}
