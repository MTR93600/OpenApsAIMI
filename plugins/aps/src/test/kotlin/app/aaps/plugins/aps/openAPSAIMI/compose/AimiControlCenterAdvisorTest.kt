package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiControlCenterAdvisorTest {

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
    fun `very assertive meal draft recommends moderate physio guard`() {
        val draft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 4,
            stabilityLevel = 2,
            physioLevel = 0,
            autonomyMode = AimiAutonomyMode.Observation,
        )

        val recommendations = buildAimiControlCenterAdvisorRecommendations(
            preferences = preferences,
            draft = draft,
        )

        val recommendation = recommendations.first { it.id == "meal_physio_guard" }
        assertThat(recommendation.targetDraft.physioLevel).isEqualTo(1)
        assertThat(recommendation.affectedFamilies).containsExactly(AimiBehaviorFamilyId.Physio)
    }

    @Test
    fun `assertive meal draft recommends assisted autonomy`() {
        val draft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 3,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Recommendations,
        )

        val recommendations = buildAimiControlCenterAdvisorRecommendations(
            preferences = preferences,
            draft = draft,
        )

        val recommendation = recommendations.first { it.id == "meal_autonomy_alignment" }
        assertThat(recommendation.targetDraft.autonomyMode).isEqualTo(AimiAutonomyMode.AssistedApplication)
    }

    @Test
    fun `controlled autonomy recommends stronger meal capture than prudent setup`() {
        val draft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 1,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.ControlledAuthority,
        )

        val recommendations = buildAimiControlCenterAdvisorRecommendations(
            preferences = preferences,
            draft = draft,
        )

        val recommendation = recommendations.first { it.id == "autonomy_meal_alignment" }
        assertThat(recommendation.targetDraft.mealCaptureLevel).isEqualTo(3)
    }

    @Test
    fun `strong physio with disabled sources recommends stepping back to moderate`() {
        every { preferences.get(AimiStringKey.ActivitySourceMode) } returns UnifiedActivityProviderMTR.MODE_DISABLED
        every { preferences.get(AimiStringKey.OuraPersonalAccessToken) } returns ""

        val draft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 2,
            autonomyMode = AimiAutonomyMode.Observation,
        )

        val recommendations = buildAimiControlCenterAdvisorRecommendations(
            preferences = preferences,
            draft = draft,
        )

        val recommendation = recommendations.first { it.id == "physio_sources_alignment" }
        assertThat(recommendation.targetDraft.physioLevel).isEqualTo(1)
    }
}
