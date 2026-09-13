package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiBehaviorCausalAnalyzerTest {

    private val preferences = mockk<Preferences>(relaxed = true)

    @BeforeEach
    fun setUp() {
        every { preferences.get(any<BooleanPreferenceKey>()) } answers {
            firstArg<BooleanPreferenceKey>().defaultValue
        }
        every { preferences.get(any<DoublePreferenceKey>()) } answers {
            firstArg<DoublePreferenceKey>().defaultValue
        }
        every { preferences.get(any<StringPreferenceKey>()) } answers {
            firstArg<StringPreferenceKey>().defaultValue
        }
    }

    @Test
    fun `yoyo pattern prioritizes stability family`() {
        val metrics = AdvisorMetrics(
            periodLabel = "7d",
            tir70_180 = 0.52,
            tir70_140 = 0.28,
            timeBelow70 = 0.05,
            timeBelow54 = 0.01,
            timeAbove180 = 0.18,
            timeAbove250 = 0.06,
            meanBg = 171.0,
            variabilityCv = 0.39,
            gmi = 7.5,
            tdd = 41.0,
            basalPercent = 0.48,
            hypoEvents = 3,
            severeHypoEvents = 1,
            hyperEvents = 5,
            todayTir = null,
            todayTdd = null,
            dataSufficiency = AdvisorDataSufficiency.GOOD,
        )

        val insights = buildAimiBehaviorCausalInsights(
            preferences = preferences,
            metrics = metrics,
            familyBridgeSuggestions = buildAimiFamilyBridgeSuggestions(preferences, metrics),
        )

        val yoyo = insights.first { it.id == "yoyo_instability" }
        assertThat(yoyo.primaryFamily).isEqualTo(AimiBehaviorFamilyId.Stability)
    }

    @Test
    fun `meal latency pattern points to meal capture`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { preferences.get(DoubleKey.OApsAIMIMaxSMB) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.0

        val metrics = AdvisorMetrics(
            periodLabel = "7d",
            tir70_180 = 0.57,
            tir70_140 = 0.34,
            timeBelow70 = 0.02,
            timeBelow54 = 0.0,
            timeAbove180 = 0.24,
            timeAbove250 = 0.07,
            meanBg = 166.0,
            variabilityCv = 0.25,
            gmi = 7.2,
            tdd = 43.0,
            basalPercent = 0.46,
            hypoEvents = 1,
            severeHypoEvents = 0,
            hyperEvents = 6,
            todayTir = null,
            todayTdd = null,
            dataSufficiency = AdvisorDataSufficiency.GOOD,
        )

        val insights = buildAimiBehaviorCausalInsights(
            preferences = preferences,
            metrics = metrics,
            familyBridgeSuggestions = buildAimiFamilyBridgeSuggestions(preferences, metrics),
        )

        val mealLatency = insights.first { it.id == "meal_latency" }
        assertThat(mealLatency.primaryFamily).isEqualTo(AimiBehaviorFamilyId.MealCapture)
        assertThat(mealLatency.relatedSuggestionId).isEqualTo("meal_rise")
    }

    @Test
    fun `physio ambiguity appears when physio is low and meal posture assertive`() {
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease) } returns true
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive) } returns true
        every { preferences.get(DoubleKey.autodriveMaxBasal) } returns 9.0
        every { preferences.get(DoubleKey.meal_modes_MaxBasal) } returns 10.0
        every { preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.105
        every { preferences.get(DoubleKey.OApsAIMIautodrivePrebolus) } returns 2.8
        every { preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus) } returns 0.6
        every { preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl) } returns 10.0
        every { preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl) } returns 20.0
        every { preferences.get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { preferences.get(BooleanKey.AimiPhysioSleepDataEnable) } returns false
        every { preferences.get(BooleanKey.AimiPhysioHRVDataEnable) } returns false

        val metrics = AdvisorMetrics(
            periodLabel = "7d",
            tir70_180 = 0.60,
            tir70_140 = 0.36,
            timeBelow70 = 0.02,
            timeBelow54 = 0.0,
            timeAbove180 = 0.16,
            timeAbove250 = 0.04,
            meanBg = 158.0,
            variabilityCv = 0.31,
            gmi = 7.0,
            tdd = 39.0,
            basalPercent = 0.44,
            hypoEvents = 1,
            severeHypoEvents = 0,
            hyperEvents = 4,
            todayTir = null,
            todayTdd = null,
            dataSufficiency = AdvisorDataSufficiency.GOOD,
        )

        val insights = buildAimiBehaviorCausalInsights(
            preferences = preferences,
            metrics = metrics,
            familyBridgeSuggestions = emptyList(),
        )

        val physioInsight = insights.first { it.id == "physio_ambiguity" }
        assertThat(physioInsight.primaryFamily).isEqualTo(AimiBehaviorFamilyId.Physio)
    }
}
