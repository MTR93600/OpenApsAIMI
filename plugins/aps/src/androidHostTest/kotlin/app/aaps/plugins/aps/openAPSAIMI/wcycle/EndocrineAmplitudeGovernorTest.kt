package app.aaps.plugins.aps.openAPSAIMI.wcycle

import com.google.common.truth.Truth.assertThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Test

class EndocrineAmplitudeGovernorTest {

    @Test
    fun from_disabledInfo_returnsDisabledBelief() {
        val prefs = mockPrefs()
        val belief = EndocrineAmplitudeGovernor.from(info = null, prefs = prefs)
        assertThat(belief.enabled).isFalse()
        assertThat(belief.applicationMode).isEqualTo(EndocrineApplicationMode.DISABLED)
        assertThat(belief.effectiveBasalAmp).isEqualTo(1.0)
    }

    @Test
    fun from_lutealWithSoftHypoLoad_dampensEffectiveBasalTowardUnity() {
        val prefs = mockPrefs()
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = true)
        val undamped = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.0,
            hypoGuardActive = false,
            hourOfDay = 12,
        )
        val dampened = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.20,
            hypoGuardActive = false,
            hourOfDay = 12,
        )
        assertThat(undamped.effectiveBasalAmp).isGreaterThan(1.15)
        assertThat(dampened.effectiveBasalAmp).isLessThan(undamped.effectiveBasalAmp)
        assertThat(dampened.effectiveBasalAmp).isGreaterThan(1.0)
        assertThat(dampened.dosePathOwner).isEqualTo(EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT)
    }

    @Test
    fun from_highHypoLoad_forcesHardUnity() {
        val prefs = mockPrefs()
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = true)
        val belief = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.50,
            hypoGuardActive = true,
            hourOfDay = 12,
        )
        assertThat(belief.effectiveBasalAmp).isEqualTo(1.0)
        assertThat(belief.effectiveSmbAmp).isEqualTo(1.0)
        assertThat(belief.hypoLoadDampen).isEqualTo(0.0)
        assertThat(belief.reasons.joinToString()).contains("hard_unity_hypo")
        assertThat(belief.dosePathOwner).isEqualTo(EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT)
    }

    @Test
    fun from_lutealDawnHour_foldsDawnIntoIntendedBasal() {
        val prefs = mockPrefs()
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = true)
        val belief = EndocrineAmplitudeGovernor.from(
            info = info,
            prefs = prefs,
            hypoLoad = 0.0,
            hourOfDay = 5,
        )
        assertThat(belief.dawnBias).isEqualTo(1.10)
        assertThat(belief.intendedBasalAmp).isWithin(0.001).of(1.25 * 1.10)
        assertThat(belief.reasons.joinToString()).contains("dawn_bias")
    }

    @Test
    fun productionAmp_returnsUnityUnlessApplied() {
        val prefs = mockPrefs()
        whenever(prefs.shadow()).thenReturn(true)
        val info = lutealInfo(basal = 1.25, smb = 1.12, applied = false)
        val belief = EndocrineAmplitudeGovernor.from(info = info, prefs = prefs, hypoLoad = 0.0)
        assertThat(belief.applicationMode).isEqualTo(EndocrineApplicationMode.SHADOW)
        assertThat(EndocrineAmplitudeGovernor.productionAmp(belief, EndocrineAmpAxis.BASAL)).isEqualTo(1.0)
    }

    private fun mockPrefs(): WCyclePreferences {
        val prefs = mock<WCyclePreferences>()
        whenever(prefs.trackingMode()).thenReturn(CycleTrackingMode.CALENDAR_VARIABLE)
        whenever(prefs.contraceptive()).thenReturn(ContraceptiveType.COPPER_IUD)
        whenever(prefs.thyroid()).thenReturn(ThyroidStatus.EUTHYROID)
        whenever(prefs.verneuil()).thenReturn(VerneuilStatus.ACTIVE)
        whenever(prefs.shadow()).thenReturn(false)
        whenever(prefs.requireConfirm()).thenReturn(false)
        whenever(prefs.clampMin()).thenReturn(0.7)
        whenever(prefs.clampMax()).thenReturn(1.5)
        return prefs
    }

    private fun lutealInfo(basal: Double, smb: Double, applied: Boolean): WCycleInfo =
        WCycleInfo(
            enabled = true,
            dayInCycle = 21,
            phase = CyclePhase.LUTEAL,
            baseBasalMultiplier = basal,
            baseSmbMultiplier = smb,
            learnedBasalMultiplier = 1.0,
            learnedSmbMultiplier = 1.0,
            basalMultiplier = if (applied) basal else 1.0,
            smbMultiplier = if (applied) smb else 1.0,
            icMultiplier = if (applied) 1.15 else 1.0,
            applied = applied,
            reason = "test",
        )
}
