package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.DoubleKey
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AimiControlCenterSupportTest {

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
    fun `unchanged draft produces no pending changes`() {
        val currentDraft = readAimiControlCenterDraft(preferences)

        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft,
        )

        assertThat(pending.hasChanges).isFalse()
        assertThat(pending.changedFamilyCount).isEqualTo(0)
        assertThat(pending.changedSettingsCount).isEqualTo(0)
    }

    @Test
    fun `moving one family only previews that family legacy keys`() {
        val currentDraft = readAimiControlCenterDraft(preferences)
        val targetDraft = currentDraft.copy(
            protectionLevel = (currentDraft.protectionLevel + 1).coerceAtMost(4),
        )

        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )

        assertThat(pending.changedFamilyCount).isEqualTo(1)
        assertThat(pending.familyPlans.single().familyId).isEqualTo(AimiBehaviorFamilyId.Protection)
        assertThat(pending.changedSettingsCount).isAtLeast(1)
    }

    @Test
    fun `controlled autonomy apply writes expected autonomy booleans`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease) } returns false
        every { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority) } returns false
        every { preferences.get(BooleanKey.OApsAIMIautoDriveAuthoritative) } returns false

        val currentDraft = readAimiControlCenterDraft(preferences)
        val targetDraft = currentDraft.copy(autonomyMode = AimiAutonomyMode.ControlledAuthority)
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = targetDraft,
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(BooleanKey.OApsAIMIautoDriveActive, true) }
        verify { preferences.put(BooleanKey.OApsAIMIHyperTrajectoryRelease, true) }
        verify { preferences.put(BooleanKey.OApsAIMIRecursiveBeliefAuthority, true) }
        verify { preferences.put(BooleanKey.OApsAIMIautoDriveAuthoritative, true) }
    }

    @Test
    fun `protective writeback moves pkpd guard factors toward protection without forcing the absolute floor`() {
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.90
        every { preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold) } returns 0.90

        val currentDraft = readAimiControlCenterDraft(preferences)
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(protectionLevel = 0),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.75) }
        verify { preferences.put(DoubleKey.OApsAIMIRedCarpetRestoreThreshold, 0.75) }
    }

    @Test
    fun `meal capture increase lifts basal ceilings incrementally from the current state`() {
        every { preferences.get(DoubleKey.autodriveMaxBasal) } returns 4.0
        every { preferences.get(DoubleKey.meal_modes_MaxBasal) } returns 5.0

        val currentDraft = readAimiControlCenterDraft(preferences)
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(mealCaptureLevel = 4),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.autodriveMaxBasal, 7.5) }
        verify { preferences.put(DoubleKey.meal_modes_MaxBasal, 8.5) }
    }

    @Test
    fun `meal draft reflects owned meal settings even when autonomy is off`() {
        every { preferences.get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease) } returns true
        every { preferences.get(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive) } returns true
        every { preferences.get(DoubleKey.autodriveMaxBasal) } returns 9.0
        every { preferences.get(DoubleKey.meal_modes_MaxBasal) } returns 10.0
        every { preferences.get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.105
        every { preferences.get(DoubleKey.OApsAIMIautodrivePrebolus) } returns 2.8
        every { preferences.get(DoubleKey.OApsAIMIautodrivesmallPrebolus) } returns 0.6
        every { preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl) } returns 10.0
        every { preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl) } returns 20.0

        val draft = readAimiControlCenterDraft(preferences)

        assertThat(draft.mealCaptureLevel).isAtLeast(3)
    }

    @Test
    fun `protection move steps from current value instead of forcing absolute preset`() {
        every { preferences.get(DoubleKey.OApsAIMIMaxSMB) } returns 1.4

        val currentDraft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Observation,
        )
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(protectionLevel = 3),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMIMaxSMB, 1.8) }
    }

    @Test
    fun `protection increase never lowers expert high bg max smb above ladder ceiling`() {
        every { preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 4.0
        every { preferences.get(DoubleKey.OApsAIMIMaxSMB) } returns 2.20
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.93

        val currentDraft = AimiControlCenterDraft(
            protectionLevel = 3,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Observation,
        )
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(protectionLevel = 4),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMIHighBGMaxSMB, 4.8) }
        verify { preferences.put(DoubleKey.OApsAIMIMaxSMB, 2.40) }
        verify { preferences.put(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 1.0) }
    }

    @Test
    fun `protection decrease never raises expert pkpd relief below ladder floor`() {
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.55

        val currentDraft = AimiControlCenterDraft(
            protectionLevel = 3,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Observation,
        )
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(protectionLevel = 2),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor, 0.50) }
    }

    @Test
    fun `stability smoother writeback uses pkpd band floor not legacy remapped values`() {
        // Nearest ladder stop to 0.81 is index 2; moving left (smoother) must strengthen damping
        // (lower floor) inside [0.70, 0.92] — never write ≤0.55 which effectiveStoredValue neuters.
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.81

        val currentDraft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Observation,
        )
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(stabilityLevel = 0),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMISmbTailDamping, 0.70) }
    }

    @Test
    fun `stability more reactive writeback raises tail floor within pkpd light band`() {
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.81

        val currentDraft = AimiControlCenterDraft(
            protectionLevel = 2,
            mealCaptureLevel = 2,
            stabilityLevel = 2,
            physioLevel = 1,
            autonomyMode = AimiAutonomyMode.Observation,
        )
        val pending = buildAimiControlCenterPendingChanges(
            preferences = preferences,
            currentDraft = currentDraft,
            targetDraft = currentDraft.copy(stabilityLevel = 4),
        )

        applyAimiControlCenterPendingChanges(preferences, pending)

        verify { preferences.put(DoubleKey.OApsAIMISmbTailDamping, 0.92) }
    }
}
