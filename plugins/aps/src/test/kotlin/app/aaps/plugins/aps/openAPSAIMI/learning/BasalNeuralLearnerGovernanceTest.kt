package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class BasalNeuralLearnerGovernanceTest {

    private lateinit var learner: BasalNeuralLearner
    private val mockFile = mockk<File>(relaxed = true)

    @BeforeEach
    fun setup() {
        val context = mockk<Context>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val storage = mockk<AimiStorageHelper>(relaxed = true)
        val log = mockk<AAPSLogger>(relaxed = true)
        every { mockFile.exists() } returns false
        every { storage.getAimiFile(any()) } returns mockFile
        every { preferences.get(any<DoublePreferenceKey>()) } answers { firstArg<DoubleKey>().defaultValue }
        learner = BasalNeuralLearner(context, preferences, storage, log)
        learner.resetGovernanceStateForTesting()
    }

    @Test
    fun `hysteresis keeps HOLD when hypo rate is between exit and enter thresholds`() {
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()
        val snap1 = learner.getGovernanceSnapshot()
        assertThat(snap1.action).isEqualTo(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE)
        assertThat(snap1.hypoHoldLatched).isFalse()

        // 9/60 = 15% hypo — below 20% enter, above 12% exit → must stay HOLD latched
        val mixed = List(51) { 120.0 } + List(9) { 75.0 }
        learner.replaceGovernanceSamplesForTesting(mixed)
        learner.evaluateGovernance()
        val snap2 = learner.getGovernanceSnapshot()
        assertThat(snap2.action).isEqualTo(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE)
        assertThat(snap2.hypoHoldLatched).isTrue()

        learner.replaceGovernanceSamplesForTesting(List(60) { 120.0 })
        learner.evaluateGovernance()
        assertThat(learner.getGovernanceSnapshot().action).isEqualTo(BasalNeuralLearner.GovernanceAction.KEEP)
        assertThat(learner.getGovernanceSnapshot().hypoHoldLatched).isFalse()
    }

    @Test
    fun `severe hypo in window uses stricter basal floor than rate-only hypo pressure`() {
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()
        assertThat(learner.getGovernanceSnapshot().activeBasalFloor).isEqualTo(0.85)

        learner.replaceGovernanceSamplesForTesting(listOf(65.0) + List(59) { 120.0 })
        learner.evaluateGovernance()
        assertThat(learner.getGovernanceSnapshot().activeBasalFloor).isEqualTo(0.88)
    }

    @Test
    fun `noisy hypo samples reduce governance hypo rate versus raw fraction`() {
        val lows = List(15) { 75.0 }
        val highs = List(45) { 120.0 }
        val bg = lows + highs
        val noises = List(15) { 3.0 } + List(45) { 0.0 }
        learner.replaceGovernanceSamplesForTesting(bg, sensorNoises = noises)
        learner.evaluateGovernance()
        val snap = learner.getGovernanceSnapshot()
        assertThat(snap.hypoRate).isWithin(0.001).of(0.25)
        assertThat(snap.hypoRateGovernance).isLessThan(snap.hypoRate)
        assertThat(snap.hypoRateGovernance).isLessThan(0.20)
        assertThat(snap.action).isEqualTo(BasalNeuralLearner.GovernanceAction.KEEP)
        assertThat(snap.meanGovernanceWeight).isLessThan(1.0)
    }

    @Test
    fun `short horizon min pred above hypo band lowers adjusted hypo rate and can exit HOLD pressure`() {
        val bg = List(15) { 75.0 } + List(45) { 120.0 }
        learner.replaceGovernanceSamplesForTesting(bg)
        learner.evaluateGovernance()
        val withoutPred = learner.getGovernanceSnapshot()
        assertThat(withoutPred.hypoGovernanceAdjusted).isWithin(0.001).of(withoutPred.hypoRateGovernance)
        assertThat(withoutPred.anticipationRelief).isWithin(0.001).of(0.0)
        assertThat(withoutPred.action).isEqualTo(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE)

        val preds =
            List(42) { Double.NaN } + List(18) { 130.0 }
        learner.replaceGovernanceSamplesForTesting(bg, shortMinPredBgs = preds)
        learner.evaluateGovernance()
        val withPred = learner.getGovernanceSnapshot()
        assertThat(withPred.anticipationRelief).isGreaterThan(0.8)
        assertThat(withPred.hypoGovernanceAdjusted).isLessThan(withPred.hypoRateGovernance)
        assertThat(withPred.action).isEqualTo(BasalNeuralLearner.GovernanceAction.KEEP)
    }
}
