package app.aaps.plugins.aps.openAPSAIMI.physio.gate

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.physio.GateInput
import app.aaps.plugins.aps.openAPSAIMI.physio.KernelType
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioStateMTR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import app.aaps.core.keys.interfaces.Preferences

class CosineTrajectoryGateTest {

    private lateinit var gate: CosineTrajectoryGate
    private lateinit var mockPrefs: Preferences
    private lateinit var mockLogger: AAPSLogger

    @Before
    fun setup() {
        mockPrefs = mock()
        mockLogger = mock()
        gate = CosineTrajectoryGate(mockPrefs, mockLogger)
        
        // Defaults
        whenever(mockPrefs.get(BooleanKey.AimiCosineGateEnabled)).thenReturn(true)
        whenever(mockPrefs.get(DoubleKey.AimiCosineGateAlpha)).thenReturn(2.0)
        whenever(mockPrefs.get(DoubleKey.AimiCosineGateMinDataQuality)).thenReturn(0.3)
        whenever(mockPrefs.get(DoubleKey.AimiCosineGateMinSensitivity)).thenReturn(0.7)
        whenever(mockPrefs.get(DoubleKey.AimiCosineGateMaxSensitivity)).thenReturn(1.3)
        whenever(mockPrefs.get(IntKey.AimiCosineGateMaxPeakShift)).thenReturn(15)
    }

    @Test
    fun `test Neutral Output when Disabled`() {
        whenever(mockPrefs.get(BooleanKey.AimiCosineGateEnabled)).thenReturn(false)
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
        
        // Near 1.0 / 0
        assertEquals(1.0, result.effectiveSensitivityMultiplier, 0.05)
        assertEquals(0, result.peakTimeShiftMinutes)
        assertEquals(KernelType.REST, result.dominantKernel)
    }

    @Test
    fun `test STRESS Kernel match`() {
        // Delta +3.0 (norm 0.3), Steps 0, StressDetected (norm 1.0)
        val input = createInput(
            delta = 3.0,
            steps = 0,
            physioState = PhysioStateMTR.STRESS_DETECTED,
            hr = 100
        )
        val result = gate.compute(input)
        
        // STRESS base: Sens 0.8, Shift 10
        assertEquals(KernelType.STRESS, result.dominantKernel)
        assertTrue("Sens < 1.0 for stress", result.effectiveSensitivityMultiplier < 0.95)
        assertTrue("Shift > 0 for stress", result.peakTimeShiftMinutes > 5)
    }

    @Test
    fun `test ACTIVITY Kernel match`() {
        // Delta -5.0 (norm -0.5), Steps 1500 (norm 1.0), Activity Detected by steps
        val input = createInput(
            delta = -5.0,
            steps = 1500,
            physioState = PhysioStateMTR.OPTIMAL
        )
        val result = gate.compute(input)
       
        // ACTIVITY base: Sens 1.3, Shift 0
        assertEquals(KernelType.ACTIVITY, result.dominantKernel)
        assertTrue("Sens > 1.0 for activity", result.effectiveSensitivityMultiplier > 1.1)
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
