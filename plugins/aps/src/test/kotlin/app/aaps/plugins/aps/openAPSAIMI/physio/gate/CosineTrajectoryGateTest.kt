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
import java.util.prefs.Preferences

class CosineTrajectoryGateTest {

    private lateinit var gate: CosineTrajectoryGate
    private lateinit var mockSp: MockSP
    private lateinit var mockLogger: MockLogger

    @Before
    fun setup() {
        mockSp = MockSP()
        mockLogger = MockLogger()
        gate = CosineTrajectoryGate(mockSp, mockLogger)

        // Defaults
        mockSp.setBoolean(BooleanKey.AimiCosineGateEnabled, true)
        mockSp.setDouble(DoubleKey.AimiCosineGateAlpha, 2.0)
        mockSp.setDouble(DoubleKey.AimiCosineGateMinDataQuality, 0.3)
        mockSp.setDouble(DoubleKey.AimiCosineGateMinSensitivity, 0.7)
        mockSp.setDouble(DoubleKey.AimiCosineGateMaxSensitivity, 1.3)
        mockSp.setInt(IntKey.AimiCosineGateMaxPeakShift, 15)
    }

    @Test
    fun `test Neutral Output when Disabled`() {
        mockSp.setBoolean(BooleanKey.AimiCosineGateEnabled, false)
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
        // Delta -5.0 (norm -0.5), Steps 1500 (norm 1.0), ActivityDetected
        val input = createInput(
            delta = -5.0,
            steps = 1500,
            physioState = PhysioStateMTR.ACTIVITY_DETECTED
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

    // --- Minimal Stub for SP ---
    class MockSP : SP {
        private val bools = mutableMapOf<String, Boolean>()
        private val doubles = mutableMapOf<String, Double>()
        private val ints = mutableMapOf<String, Int>()

        fun setBoolean(key: BooleanPreferenceKey, value: Boolean) { bools[key.key] = value }
        fun setDouble(key: DoublePreferenceKey, value: Double) { doubles[key.key] = value }
        fun setInt(key: IntPreferenceKey, value: Int) { ints[key.key] = value }

        // Needed by CosineTrajectoryGate
        override fun getBoolean(key: BooleanPreferenceKey): Boolean = bools[key.key] ?: false
        override fun getDouble(key: DoublePreferenceKey, defaultValue: Double): Double = doubles[key.key] ?: defaultValue
        override fun getInt(key: IntPreferenceKey, defaultValue: Int): Int = ints[key.key] ?: defaultValue

        // Unused stubs to satisfy interface
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = bools[key] ?: defaultValue
        override fun getInt(key: String, defaultValue: Int): Int = ints[key] ?: defaultValue
        override fun getDouble(key: String, defaultValue: Double): Double = doubles[key] ?: defaultValue
        override fun getString(key: String, defaultValue: String): String = ""
        override fun getLong(key: String, defaultValue: Long): Long = 0L
        override fun contains(key: String): Boolean = false
        override fun getLong(key: String, defaultValue: Int): Long = 0L
        // Add others if compilation fails, assume minimal SP interface here for "Mock"
    }

    class MockLogger : AAPSLogger {
        override fun debug(tag: String, msg: String) { println("DEBUG: $msg") }
        override fun info(tag: String, msg: String) { println("INFO: $msg") }
        override fun warn(tag: String, msg: String) { println("WARN: $msg") }
        override fun error(tag: String, msg: String) { println("ERROR: $msg") }
        override fun error(tag: String, msg: String, t: Throwable) { println("ERROR: $msg") }
    }
}
