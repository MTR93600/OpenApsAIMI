package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import com.google.common.truth.Truth.assertThat
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * The basal label reads the BG move of the next 30 minutes as basal work. The trainer can only censor a
 * window that also got insulin or carbs if the tick REPORTS those two facts, so the engine must pass
 * `bolusInsulinU` and `cobGrams` to `BasalNeuralLearner.updateLearning`.
 *
 * These were added with a `NaN` default and never passed, so every new CSV row said `NaN,NaN`, the parser
 * read both as absent, and every row was kept as "legacy, nothing proven". The tests below fail again if
 * the call site goes back to that state.
 */
class BasalLearningCausalCensoringWiringTest {

    private fun preferencesBase(): Preferences = mockk(relaxed = true) {
        every { get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false
        every { get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { get(BooleanKey.OApsAIMIUnifiedReactivityEnabled) } returns false
        every { get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { get(BooleanKey.OApsAIMIMealAdvisorTrigger) } returns false
        every { get(DoubleKey.OApsAIMIMaxSMB) } returns 0.5
        every { get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.0
        every { get(DoubleKey.OApsAIMITDD7) } returns 48.0
    }

    private fun profileStandard() = mockk<OapsProfileAimi>(relaxed = true) {
        every { TDD } returns 48.0
        every { current_basal } returns 1.0
        every { max_daily_basal } returns 48.0
        every { max_basal } returns 5.0
        every { max_iob } returns 20.0
        every { sens } returns 50.0
        every { target_bg } returns 100.0
        every { min_bg } returns 80.0
        every { max_bg } returns 180.0
        every { carb_ratio } returns 10.0
        every { dia } returns 5.0
        every { peakTime } returns 60.0
        every { enableUAM } returns false
        every { currentActivity } returns 0.0
        every { futureActivity } returns 0.0
        every { sensorLagActivity } returns 0.0
        every { historicActivity } returns 0.0
        every { lgsThreshold } returns 70
        every { variable_sens } returns 0.0
    }

    private fun newHarness(now: Long, learner: BasalNeuralLearner) = DetermineBasalAimiScenarioTestHarness(
        now = now,
        preferences = preferencesBase(),
        trajectoryGuard = mockk(relaxed = true),
        autodriveGater = mockk(relaxed = true),
        autodriveEngine = mockk(relaxed = true),
        physioAdapter = mockk(relaxed = true),
        basalNeuralLearner = learner
    )

    @Test
    fun `a tick with carbs and a recent bolus reports finite causal facts, not NaN`() {
        val now = System.currentTimeMillis()
        val learner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = newHarness(now, learner)
        coEvery { harness.persistenceLayer.getBolusesFromTime(any(), any()) } returns listOf(
            BS(
                timestamp = now - 2 * 60_000L,
                amount = 3.0,
                type = BS.Type.NORMAL,
                iCfg = ICfg(insulinLabel = "test", peak = 60, dia = 5.0, concentration = 1.0)
            )
        )

        // Same deterministic branch as the other scenario tests: high sensor noise → SAFETY early exit,
        // which runs the basal-learning hook exactly once.
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 120.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns now
                every { noise } returns 3.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 3.0
                every { lastBolusTime } returns now - 2 * 60_000L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns now - 10 * 60_000L
                every { mealCOB } returns 45.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "causal censoring wiring scenario"
        )

        val bolusSlot: CapturingSlot<Double> = slot()
        val cobSlot: CapturingSlot<Double> = slot()
        verify(exactly = 1) {
            learner.updateLearning(
                bgBefore = any(),
                bgAfter = any(),
                basalDelivered = any(),
                targetBg = any(),
                accel = any(),
                duraISFminutes = any(),
                duraISFaverage = any(),
                iob = any(),
                loopDeltaMgDl5m = any(),
                sensorNoise = any(),
                shortMinPredBg = any(),
                physioFeatures = any(),
                bolusInsulinU = capture(bolusSlot),
                cobGrams = capture(cobSlot),
            )
        }
        // The carbs of this tick must arrive as a real number: 45 g is over the trainer's carb threshold,
        // so this window can now be censored instead of being trained on.
        assertThat(cobSlot.captured).isWithin(1e-9).of(45.0)
        // The insulin figure must be a number as well. The exact amount is not asserted here because the
        // engine's bolus history is refreshed on a background snapshot; the SMB part is checked below.
        assertThat(bolusSlot.captured.isNaN()).isFalse()
        assertThat(bolusSlot.captured).isAtLeast(0.0)
    }

    @Test
    fun `the reported insulin carries the SMB of this tick`() {
        val now = System.currentTimeMillis()
        val harness = newHarness(now, mockk(relaxed = true))

        val withSmb = harness.engine.basalLearningBolusUnits(RT(runningDynamicIsf = false, units = 1.4))
        val withoutSmb = harness.engine.basalLearningBolusUnits(RT(runningDynamicIsf = false))

        assertThat(withSmb).isWithin(1e-9).of(1.4)
        assertThat(withoutSmb).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `carbs are reported when known and stay unknown when the tick never read them`() {
        val now = System.currentTimeMillis()
        val harness = newHarness(now, mockk(relaxed = true))

        // No tick has run, so the per-tick carb value is unset and RT is the only source.
        assertThat(harness.engine.basalLearningCobGrams(RT(runningDynamicIsf = false, COB = 30.0)))
            .isWithin(1e-9).of(30.0)
        // Nothing known: "not reported" must stay NaN, never a made-up zero.
        assertThat(harness.engine.basalLearningCobGrams(RT(runningDynamicIsf = false)).isNaN()).isTrue()
    }
}
