package app.aaps.ui.compose.dashboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GlassAimiRuntimeCardsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersPublishedTrajectoryAndPatientPresentationWithoutRecalculatingValues() {
        val data = AimiGlassDashboardData(
            trajectory = AimiGlassDashboardData.Trajectory(
                hybridTerminalMgdl = 126.0,
                iobTerminalMgdl = 103.0,
                cobTerminalMgdl = 117.0,
                uamTerminalMgdl = 120.0,
                publishedAtMs = 1_000L,
                isStale = false,
            ),
            physio = AimiGlassDashboardData.Physio(
                stepsLast15m = 17,
                stepsLast60m = 83,
                hrNowBpm = 72,
                hrAvg15mBpm = 69,
                rhrRestingBpm = 55,
                hrvRmssd = 31.5,
                activityState = "WALKING",
                sleepDebtMinutes = 24,
                asleepLiveConfidence = 0.8,
                asleepLiveSource = "HEALTH_CONNECT",
                snapshotAgeMs = 1200L,
                hrSampleAgeMs = 3000L,
                source = "Health Connect",
                confidence = 0.8,
                thermalHypothesis = "RECOVERY",
                thermalDeltaVsBaselineC = 0.3,
                thermalInflammationIndex = 0.2,
                thermalNarrative = "Thermal recovery",
                presentation = AimiGlassDashboardData.Physio.Presentation(
                    modeHeadline = "Mode (80%)",
                    intentSummary = "Dominant EXERCISE · 80% confidence across 1 context",
                    thermalSummary = "Thermal recovery (+0.3°C vs baseline)",
                    updatedSummary = "Updated just now",
                ),
            ),
        )

        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = data,
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        listOf(
            "Predicted (4h)",
            "Insulin-only BG",
            "Carbs-only BG",
            "126.0 mg/dL",
            "103.0 mg/dL",
            "117.0 mg/dL",
            "120.0 mg/dL",
            "Mode",
            "Mode (80%)",
            "Intent",
            "Dominant EXERCISE · 80% confidence across 1 context",
            "Thermal",
            "Thermal recovery (+0.3°C vs baseline)",
            "Updated just now",
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun missingRuntimeDataShowsExistingUnavailableMessagesRatherThanZeroTelemetry() {
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        compose.onNodeWithText("No recent prediction available.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No recent physio snapshot available.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open AIMI Context").assertDoesNotExist()
    }

    @Test
    fun missingTrajectoryEndpointsRemainExplicitlyUnavailable() {
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(
                        trajectory = AimiGlassDashboardData.Trajectory(
                            hybridTerminalMgdl = 126.0,
                            iobTerminalMgdl = null,
                            cobTerminalMgdl = null,
                            uamTerminalMgdl = null,
                            publishedAtMs = 1_000L,
                            isStale = false,
                        ),
                    ),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        compose.onNodeWithText("126.0 mg/dL").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("n/a").assertCountEquals(10)
    }

    @Test
    fun staleTrajectoryShowsUnavailableInsteadOfRetainedPredictions() {
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(
                        trajectory = AimiGlassDashboardData.Trajectory(
                            hybridTerminalMgdl = 126.0,
                            iobTerminalMgdl = 103.0,
                            cobTerminalMgdl = 117.0,
                            uamTerminalMgdl = 120.0,
                            publishedAtMs = 1_000L,
                            isStale = true,
                        ),
                    ),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        compose.onNodeWithText("No recent prediction available.").assertIsDisplayed()
        compose.onNodeWithText("126.0 mg/dL").assertDoesNotExist()
        compose.onNodeWithText("103.0 mg/dL").assertDoesNotExist()
    }

    @Test
    fun keyFactorsUsePublishedValuesAndKeepUnitsInLocalizedTemplates() {
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(
                        keyFactors = AimiGlassDashboardData.KeyFactors(
                            targetBgMgdl = 110.0,
                            maxIobUnits = 3.0,
                            maxSmbUnits = 1.2,
                            isfMgdlPerUnit = 45.0,
                            stableBgMinutes = 36.0,
                        ),
                        safety = AimiGlassDashboardData.Safety(
                            tdd7DaysPerHour = 0.75,
                            lowTirLastHourPercent = 1.0,
                            lowTirTodayPercent = 2.0,
                            stepsLast5Minutes = 42,
                        ),
                    ),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        listOf(
            "Key Factors",
            "14 · weekday",
            "Target BG",
            "110.0 mg/dL",
            "Max IOB",
            "3.0 U",
            "Max SMB",
            "1.2 U",
            "ISF",
            "45.0 mg/dL/U",
            "Stable BG duration",
            "36.0 min",
            "Safety & Alarms",
            "7-day average insulin per hour",
            "0.75 U/h",
            "Low glucose time (1h / today)",
            "1h 1.0% / today 2.0%",
            "Recent steps (5 min)",
            "42 steps",
        ).forEach { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun contextActionIsShownOnlyWhenPlatformProvidesARealDestination() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(
                        contextScreenAvailable = true,
                        physio = AimiGlassDashboardData.Physio(
                            stepsLast15m = 0,
                            stepsLast60m = 0,
                            hrNowBpm = 0,
                            hrAvg15mBpm = 0,
                            rhrRestingBpm = 0,
                            hrvRmssd = 0.0,
                            activityState = "IDLE",
                            sleepDebtMinutes = 0,
                            asleepLiveConfidence = 0.0,
                            asleepLiveSource = "NONE",
                            snapshotAgeMs = 0L,
                            hrSampleAgeMs = null,
                            source = "Unknown",
                            confidence = 0.0,
                            thermalHypothesis = "DATA_PENDING",
                            thermalDeltaVsBaselineC = 0.0,
                            thermalInflammationIndex = 0.0,
                            thermalNarrative = "",
                            presentation = AimiGlassDashboardData.Physio.Presentation(
                                modeHeadline = "Mode",
                                intentSummary = "Intent",
                                thermalSummary = "Thermal",
                                updatedSummary = "",
                            ),
                        ),
                    ),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "date-$it" },
                    onOpenAimiContext = { opened = true },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        compose.onNodeWithText("Open AIMI Context").performScrollTo().performClick()

        assertTrue(opened)
    }

    @Test
    fun trainingAndModelCardRendersLocalizedLabelsAndOnlyPublishedMetadata() {
        compose.setContent {
            MaterialTheme {
                GlassAimiRuntimeCards(
                    data = AimiGlassDashboardData(
                        training = AimiGlassDashboardData.Training(
                            basal = AimiGlassDashboardData.BasalTraining(
                                lastTrainedAtMs = 1_700_000_000_000L,
                                sampleCount = 245,
                                circuitOpen = true,
                                modelFile = AimiGlassDashboardData.ModelFile(true, 1_698_000_000_000L, 5_600L),
                            ),
                            smb = AimiGlassDashboardData.SmbTraining(
                                lastAttempt = AimiGlassDashboardData.SmbAttempt(
                                    atMs = 1_700_000_000_000L,
                                    outcome = AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES,
                                    totalRows = 520,
                                    samplesAfterFilter = 310,
                                    rowsRejectedByFilter = 210,
                                    detail = "spread below required threshold",
                                ),
                                waiting = null,
                                lastTrainedAtMs = 1_699_000_000_000L,
                                circuitOpen = false,
                                modelLoaded = true,
                                modelFile = AimiGlassDashboardData.ModelFile(true, 1_699_000_000_000L, 14_200L),
                            ),
                        ),
                    ),
                    strings = runtimeCardStrings,
                    formatGlucose = { "$it mg/dL" },
                    formatDateTime = { "localized-date-$it" },
                    onOpenAimiContext = {},
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }

        listOf(
            "ML Training",
            "Last trained",
            "localized-date-1698000000000",
            "Samples",
            "245",
            "Training paused (cooling down after recent failures)",
            "SMB training",
            "Model rejected by a quality check",
            "spread below required threshold",
            "Model Files",
            "Basal model",
            "SMB model",
            "Basal model size",
            "SMB model size",
            "localized-date-1699000000000",
            "5600 bytes",
            "14200 bytes",
        ).forEach { compose.onAllNodesWithText(it).assertCountEquals(1) }
    }

    private val runtimeCardStrings = GlassAimiRuntimeCardStrings(
        trajectoryTitle = "Trajectory",
        trajectoryHybridLabel = "Predicted (4h)",
        trajectoryIobLabel = "Insulin-only BG",
        trajectoryCobLabel = "Carbs-only BG",
        trajectoryUamLabel = "Unannounced meal BG",
        trajectoryUnavailable = "No recent prediction available.",
        keyFactorsTitle = "Key Factors",
        keyFactorsContextBadge = "14 · weekday",
        keyFactorsTargetLabel = "Target BG",
        keyFactorsMaxIobLabel = "Max IOB",
        keyFactorsMaxSmbLabel = "Max SMB",
        keyFactorsIsfLabel = "ISF",
        keyFactorsStableBgLabel = "Stable BG duration",
        formatInsulin = { "$it U" },
        formatIsf = { "$it mg/dL/U" },
        formatStableMinutes = { "$it min" },
        safetyTitle = "Safety & Alarms",
        safetyTddLabel = "7-day average insulin per hour",
        safetyTirLabel = "Low glucose time (1h / today)",
        safetyStepsLabel = "Recent steps (5 min)",
        safetyTirValue = { hour, today -> "1h $hour / today $today" },
        formatPercent = { "$it%" },
        formatInsulinPerHour = { "$it U/h" },
        formatSteps = { "$it steps" },
        openAimiContextLabel = "Open AIMI Context",
        physioTitle = "Physio",
        physioModeLabel = "Mode",
        physioIntentLabel = "Intent",
        physioThermalLabel = "Thermal",
        physioUnavailable = "No recent physio snapshot available.",
        valueUnavailable = "n/a",
        mlTrainingTitle = "ML Training",
        lastTrainedLabel = "Last trained",
        samplesLabel = "Samples",
        circuitOpen = "Training paused (cooling down after recent failures)",
        trainingNotStarted = "Training subsystem not started yet",
        neverTrained = "Never trained yet",
        smbTrainingLabel = "SMB training",
        smbNeverAttempted = "Never attempted yet",
        smbOutcomeTrained = "Trained",
        smbOutcomeNoCsv = "No data file yet",
        smbOutcomeRefusedHeader = "Data file refused: wrong columns",
        smbOutcomeTooFewSamples = "Not enough usable rows",
        smbOutcomeRejected = "Model rejected by a quality check",
        smbOutcomeError = "Training failed with an error",
        smbWaitingNotDue = "Waiting: next attempt not due yet",
        smbWaitingCircuitOpen = "Waiting: training paused after repeated failures",
        smbDetailLabel = "SMB training detail",
        smbRowsLabel = { kept, dropped -> "$kept rows kept, $dropped dropped" },
        modelFilesTitle = "Model Files",
        basalModelLabel = "Basal model",
        smbModelLabel = "SMB model",
        basalModelSizeLabel = "Basal model size",
        smbModelSizeLabel = "SMB model size",
        modelFileMissing = "Not created yet",
        modelFileSizeUnavailable = "n/a",
        formatFileSize = { "$it bytes" },
    )
}
