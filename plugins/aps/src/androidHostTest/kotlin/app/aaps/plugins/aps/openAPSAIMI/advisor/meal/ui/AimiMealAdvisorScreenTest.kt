package app.aaps.plugins.aps.openAPSAIMI.advisor.meal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import android.graphics.Bitmap
import app.aaps.core.data.model.CA
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.AimiImage
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.EstimationResult
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.MacroRange
import app.aaps.plugins.aps.openAPSAIMI.advisor.meal.VisibleFoodItem
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.eq
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.junit.Assert.assertEquals
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AimiMealAdvisorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val preferences: Preferences = mock()
    private val profileFunction: ProfileFunction = mock()

    @Before
    fun setUp() {
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
        whenever(preferences.get(StringKey.AimiAdvisorProvider)).thenReturn("OPENAI")
    }

    @Test
    fun initialScreenShowsMealInputsAndAnalyzeAction() {
        showScreen()

        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_intro)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_photo_placeholder)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_analyze_button))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun selectingVisionProviderPersistsItsProviderId() {
        showScreen()

        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_provider_label)).performClick()
        compose.onNodeWithText(context.getString(R.string.aimi_prefs_provider_gemini)).performClick()

        verify(preferences).put(StringKey.AimiAdvisorProvider, "GEMINI")
    }

    @Test
    fun capturedEstimateCanBeEditedAndConfirmedAsCarbTreatment() {
        val persistence = mock<PersistenceLayer>()
        val captureClicked = AtomicBoolean(false)
        val analyzedDescription = AtomicReference<String>()
        val analyzedBytes = AtomicReference<List<Byte>>()
        val jpeg = createJpeg()
        val estimate = estimationResult()
        val cameraCapture: @Composable (
            onCaptured: (AimiImage) -> Unit,
            onError: (String) -> Unit,
            onCancel: () -> Unit,
        ) -> Unit = { onCaptured, _, onCancel ->
            Column {
                Button(onClick = {
                    captureClicked.set(true)
                    onCaptured(AimiImage(jpeg))
                }) { Text("Capture test photo") }
                Button(onClick = onCancel) { Text("Cancel test capture") }
            }
        }

        showScreen(
            persistenceLayer = persistence,
            cameraCapture = cameraCapture,
            analyzeImage = { image, description ->
                analyzedBytes.set(image.jpegBytes.toList())
                analyzedDescription.set(description)
                estimate
            },
        )

        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_description_hint))
            .performTextReplacement("with extra sauce")
        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_analyze_button))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("Capture test photo").performClick()
        compose.waitForIdle()
        assertEquals(true, captureClicked.get())
        compose.waitUntil(5_000) { analyzedBytes.get() != null }
        assertEquals(jpeg.toList(), analyzedBytes.get())
        assertEquals("with extra sauce", analyzedDescription.get())
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Test meal", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Test meal").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.aimi_meal_advisor_carbs_field_label))
            .performScrollTo()
            .performTextReplacement("45")
        compose.waitForIdle()

        val confirmText = context.getString(R.string.aimi_meal_advisor_confirm_button, 45, 4.5)
        compose.onNodeWithText(confirmText).performScrollTo().performClick()

        val carbs = org.mockito.kotlin.argumentCaptor<CA>()
        runBlocking {
            verify(persistence, org.mockito.kotlin.timeout(5_000)).insertOrUpdateCarbs(
                carbs.capture(),
                eq(Action.TREATMENT),
                eq(Sources.CarbDialog),
                eq("AIMI Meal Advisor: Test meal"),
            )
        }
        assertEquals(45.0, carbs.firstValue.amount, 0.0)
        verify(preferences).put(BooleanKey.OApsAIMIMealAdvisorTrigger, true)
        verify(preferences).put(DoubleKey.OApsAIMILastEstimatedCarbs, 45.0)
        verify(preferences).put(eq(DoubleKey.OApsAIMILastEstimatedCarbTime), org.mockito.kotlin.any())
    }

    private fun showScreen(
        persistenceLayer: PersistenceLayer = mock(),
        cameraCapture: @Composable (
            onCaptured: (AimiImage) -> Unit,
            onError: (String) -> Unit,
            onCancel: () -> Unit,
        ) -> Unit = { _, _, _ -> },
        analyzeImage: suspend (AimiImage, String) -> EstimationResult = { _, _ -> estimationResult() },
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                MaterialTheme {
                    AimiMealAdvisorScreen(
                        preferences = preferences,
                        persistenceLayer = persistenceLayer,
                        profileFunction = profileFunction,
                        aapsLogger = mock<AAPSLogger>(),
                        cameraCapture = cameraCapture,
                        analyzeImage = analyzeImage,
                        onBack = onBack,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun createJpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 70, output)
        output.toByteArray()
    }

    private fun estimationResult() = EstimationResult(
        description = "Test meal",
        visibleItems = listOf(VisibleFoodItem("Rice", "1 cup")),
        uncertainItems = emptyList(),
        carbs = MacroRange(40.0, 35.0, 45.0),
        protein = MacroRange(5.0, 4.0, 6.0),
        fat = MacroRange(2.0, 1.0, 3.0),
        fpuEquivalent = 0.2,
        glycemicIndex = "MEDIUM",
        absorptionSpeed = "MIXED",
        confidence = "MEDIUM",
        portionConfidence = "MEDIUM",
        hiddenCarbRisk = "LOW",
        needsManualConfirmation = false,
        insulinRelevantNotes = emptyList(),
        reasoning = "Visible food estimate",
        recommendedCarbsForDose = 36.0,
        recommendedCarbsReason = "Review the estimate",
    )
}
