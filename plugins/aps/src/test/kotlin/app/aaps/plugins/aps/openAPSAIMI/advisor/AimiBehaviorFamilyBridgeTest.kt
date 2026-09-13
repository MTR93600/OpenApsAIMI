package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiBehaviorFamilyBridgeTest {

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
    fun `meal rise bridge points to meal capture and autonomy`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled) } returns true
        every { preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIMaxSMB) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMILunchFactor) } returns 50.0
        every { preferences.get(DoubleKey.OApsAIMIDinnerFactor) } returns 50.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.65
        every { preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU) } returns 0.0
        every { preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold) } returns 0.65
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85

        val metrics = AdvisorMetrics(
            periodLabel = "7d",
            tir70_180 = 0.58,
            tir70_140 = 0.34,
            timeBelow70 = 0.02,
            timeBelow54 = 0.0,
            timeAbove180 = 0.26,
            timeAbove250 = 0.08,
            meanBg = 168.0,
            variabilityCv = 0.24,
            gmi = 7.2,
            tdd = 42.0,
            basalPercent = 0.46,
            hypoEvents = 1,
            severeHypoEvents = 0,
            hyperEvents = 6,
            todayTir = null,
            todayTdd = null,
            dataSufficiency = AdvisorDataSufficiency.GOOD,
        )

        val suggestions = buildAimiFamilyBridgeSuggestions(preferences, metrics)

        val mealRise = suggestions.first { it.id == "meal_rise" }
        assertThat(mealRise.affectedFamilies).contains(AimiBehaviorFamilyId.MealCapture)
        assertThat(mealRise.targetDraft.autonomyMode).isEqualTo(AimiAutonomyMode.AssistedApplication)
    }
}
