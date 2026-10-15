package app.aaps.plugins.aps.openAPSAIMI.physio.gate

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.physio.GateInput
import app.aaps.plugins.aps.openAPSAIMI.physio.KernelType
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CosineTrajectoryGateTest {

    private lateinit var gate: CosineTrajectoryGate
    private lateinit var prefs: Preferences
    private lateinit var logger: AAPSLogger

    @BeforeEach
    fun setup() {
        prefs = mock()
        logger = mock()
        configureDefaultPrefs()
        gate = CosineTrajectoryGate(prefs, logger)
    }

    private fun configureDefaultPrefs() {
        whenever(prefs.get(BooleanKey.AimiCosineGateEnabled)).thenReturn(true)
        whenever(prefs.get(DoubleKey.AimiCosineGateAlpha)).thenReturn(2.0)
        whenever(prefs.get(DoubleKey.AimiCosineGateMinDataQuality)).thenReturn(0.3)
        whenever(prefs.get(DoubleKey.AimiCosineGateMinSensitivity)).thenReturn(0.7)
        whenever(prefs.get(DoubleKey.AimiCosineGateMaxSensitivity)).thenReturn(1.3)
        whenever(prefs.get(IntKey.AimiCosineGateMaxPeakShift)).thenReturn(15)
    }

    @Test
    fun `test Neutral Output when Disabled`() {
        whenever(prefs.get(BooleanKey.AimiCosineGateEnabled)).thenReturn(false)
        val input = createInput()
        val result = gate.compute(input)
        assertEquals(1.0, result.effectiveSensitivityMultiplier, 0.01)
        assertEquals(0, result.peakTimeShiftMinutes)
        assertTrue(result.debug.contains("Disabled"))
    }

    @Test
    fun `test REST Kernel match`() {
        val input = createInput(
            delta = 0.0,
            steps = 0,
            physioState = PhysioStateMTR.OPTIMAL
        )
        val result = gate.compute(input)

        assertEquals(1.0, result.effectiveSensitivityMultiplier, 0.05)
        assertEquals(0, result.peakTimeShiftMinutes)
        assertEquals(KernelType.REST, result.dominantKernel)
    }

    @Test
    fun `test STRESS Kernel match`() {
        val input = createInput(
            delta = 3.0,
            steps = 0,
            physioState = PhysioStateMTR.STRESS_DETECTED,
            hr = 100
        )
        val result = gate.compute(input)

        assertEquals(KernelType.STRESS, result.dominantKernel)
        assertTrue(result.effectiveSensitivityMultiplier < 0.95, "Sens < 1.0 for stress")
        assertTrue(result.peakTimeShiftMinutes > 5, "Shift > 0 for stress")
    }

    @Test
    fun `test ACTIVITY Kernel match`() {
        val input = createInput(
            delta = -5.0,
            steps = 1500,
            physioState = PhysioStateMTR.OPTIMAL
        )
        val result = gate.compute(input)

        assertEquals(KernelType.ACTIVITY, result.dominantKernel)
        assertTrue(result.effectiveSensitivityMultiplier > 1.1, "Sens > 1.0 for activity")
    }

    @Test
    fun `test Data Quality Fallback`() {
        val input = createInput(dataQuality = 0.1)
        val result = gate.compute(input)

        assertEquals(1.0, result.effectiveSensitivityMultiplier, 0.01)
        assertTrue(result.debug.contains("Low Quality"))
    }

    private fun createInput(
        delta: Double = 0.0,
        steps: Int = 0,
        physioState: PhysioStateMTR = PhysioStateMTR.OPTIMAL,
        hr: Int = 60,
        dataQuality: Double = 1.0
    ): GateInput {
        return GateInput(
            bgCurrent = 120.0,
            bgDelta = delta,
            iob = 0.0,
            cob = 0.0,
            stepCount15m = steps,
            hrCurrent = hr,
            hrvCurrent = 50.0,
            sleepState = false,
            physioState = physioState,
            dataQuality = dataQuality
        )
    }
}
