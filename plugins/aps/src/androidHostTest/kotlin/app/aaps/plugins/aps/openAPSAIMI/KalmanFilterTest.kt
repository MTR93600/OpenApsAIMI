package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KalmanFilterTest {

    @Test
    fun `test KalmanFilter update`() {
        val filter = KalmanFilter(
            stateEstimate = 10.0,
            estimationError = 5.0,
            processVariance = 1.0,
            measurementVariance = 1.0
        )
        // Prediction: 10.0
        // Pred Error: 5 + 1 = 6
        // Gain: 6 / (6 + 1) = 6/7 approx 0.857
        // Update: 10 + 0.857 * (20 - 10) = 18.57
        val newValue = filter.update(20.0)
        assertEquals(18.57, newValue, 0.1)
    }

    @Test
    fun `test KalmanISFCalculator`() {
        val tddCalculator = mock<TddCalculator>()
        val preferences = mock<Preferences>()
        val logger = mock<AAPSLogger>()
        
        // Mock TDD
        whenever(preferences.get(DoubleKey.OApsAIMITDD7)).thenReturn(50.0)
        // Mock TDD calculator to return null so it falls back to prefs or just mock it to return something
        // The code calls tddCalculator.averageTDD(...)
        // Let's just rely on the fallback to TDD7P which is 50.0
        
        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)
        
        // Test with BG 100 (factor 0.9)
        // TDD 50.
        // Raw ISF = (1800 / (50 * ln(100/75 + 1))) * 0.9
        // ln(1.33 + 1) = ln(2.33) approx 0.84
        // 1800 / (50 * 0.84) = 1800 / 42 = 42.8
        // 42.8 * 0.9 = 38.5
        
        val isf = calculator.calculateISF(100.0, 0.0, 0.0)
        
        // Initial state is 15.0.
        // It will move towards 38.5 but not reach it immediately due to filter.
        assertTrue(isf > 15.0)
        assertTrue(isf < 40.0)
    }

    /**
     * B1: at high glucose the raw formula falls under MIN_ISF and the result saturates on the
     * absolute clamp. 5.0 mg/dL/U is four times below the lowest value ever measured for this
     * patient (18.7, see `ISF/DynamicSensitivityPolicy`), so it is a clamp artefact, not a
     * sensitivity.
     */
    @Test
    fun `ISF at high glucose stays above the physiological floor instead of saturating on 5`() {
        val tddCalculator = mock<TddCalculator>()
        val preferences = mock<Preferences>()
        val logger = mock<AAPSLogger>()
        whenever(preferences.get(DoubleKey.OApsAIMITDD7)).thenReturn(55.0)

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        // Raw = (1800 / (55 * ln(250/75 + 1))) * 0.2 = 4.46, below MIN_ISF, so today it clamps to 5.0.
        val isf = calculator.calculateISF(250.0, 0.0, 0.0)

        assertTrue(isf >= 16.0, "ISF was $isf, which is below the physiological floor")
    }



    /**
     * B4: an unusable TDD falls back to the absolute floor, so nothing changes for that case.
     * Without the guard, 1800 / 0.0 would be infinite and the floor would push every value to
     * MAX_ISF = 300.
     */
    @Test
    fun `an unusable TDD falls back to the absolute floor`() {
        val tddCalculator = mock<TddCalculator>()
        val preferences = mock<Preferences>()
        val logger = mock<AAPSLogger>()
        whenever(preferences.get(DoubleKey.OApsAIMITDD7)).thenReturn(0.0)

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        // safeTDD falls back to 1.0, so raw = (1800 / ln(250/75 + 1)) * 0.2 = 245.5, and the filter
        // moves from 15.0 to about 223.3. The floor of 5.0 does not bind, as before the change.
        val isf = calculator.calculateISF(250.0, 0.0, 0.0)

        assertEquals(223.3, isf, 1.0)
        assertTrue(isf < 300.0, "the floor must not have been pushed to MAX_ISF, isf was $isf")
    }
}
