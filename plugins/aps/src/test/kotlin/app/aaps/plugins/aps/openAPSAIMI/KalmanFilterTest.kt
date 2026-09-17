package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
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
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        
        // Mock TDD
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 50.0
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
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 55.0

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        // Raw = (1800 / (55 * ln(250/75 + 1))) * 0.2 = 4.46, below MIN_ISF, so today it clamps to 5.0.
        val isf = calculator.calculateISF(250.0, 0.0, 0.0)

        assertTrue(isf >= 16.0, "ISF was $isf, which is below the physiological floor")
    }

    /**
     * B2: when the caller knows the profile ISF, the floor follows it.
     * min(30, 1800/55) * 0.5 = 15.0.
     */
    @Test
    fun `the floor is relative to the profile when the profile is known`() {
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 55.0

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        val isf = calculator.calculateISF(250.0, 0.0, 0.0, profileIsfMgdl = 30.0)

        assertEquals(15.0, isf, 0.2)
    }

    /**
     * B3: safety invariant. The floor must stay under what the `IsfAdjustmentEngine` blend produces
     * before its rate limiter (at least 0.58 x profile ISF), so in steady state
     * `max(kalmanFastIsf, isfAdj)` in the plugin keeps discarding the Kalman value. It is only a
     * steady-state property: the engine rate-limits against its own previous anchor, so just after
     * the profile ISF steps up at a block boundary `isfAdj` can sit below the floor for about an
     * hour and a half. At BG 250 the raw value is far under the floor, so the returned value is the
     * floor itself.
     */
    @Test
    fun `the floor stays under the IsfAdjustmentEngine blend in steady state`() {
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 55.0
        val profileIsf = 30.0

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        val floorAtSaturation = calculator.calculateISF(250.0, 0.0, 0.0, profileIsfMgdl = profileIsf)

        assertTrue(floorAtSaturation <= 0.5 * profileIsf, "floor was $floorAtSaturation")
        assertTrue(floorAtSaturation < 0.58 * profileIsf, "floor was $floorAtSaturation")
    }

    /**
     * B4: an unusable TDD falls back to the absolute floor, so nothing changes for that case.
     * Without the guard, 1800 / 0.0 would be infinite and the floor would push every value to
     * MAX_ISF = 300.
     */
    @Test
    fun `an unusable TDD falls back to the absolute floor`() {
        val tddCalculator = mockk<TddCalculator>(relaxed = true)
        val preferences = mockk<Preferences>(relaxed = true)
        val logger = mockk<AAPSLogger>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMITDD7) } returns 0.0

        val calculator = KalmanISFCalculator(tddCalculator, preferences, logger)

        // safeTDD falls back to 1.0, so raw = (1800 / ln(250/75 + 1)) * 0.2 = 245.5, and the filter
        // moves from 15.0 to about 223.3. The floor of 5.0 does not bind, as before the change.
        val isf = calculator.calculateISF(250.0, 0.0, 0.0)

        assertEquals(223.3, isf, 1.0)
        assertTrue(isf < 300.0, "the floor must not have been pushed to MAX_ISF, isf was $isf")
    }

    // ---- a total daily dose of zero may not be cached -------------------------------------------

    /**
     * Found by a flaky failure of `the floor is relative to the profile when the profile is known`,
     * which returned 223.29 instead of 15.0 once in a full-suite run and never again.
     *
     * `refreshTddAsync` writes whatever `averageTDD(...)?.data?.totalAmount` gives straight into the
     * cache, and a relaxed mock — or a real calculator with no history yet — hands back 0.0. The
     * effective dose is then `0.2*0 + 0.4*0 + 0.4*0 = 0`, and the sensitivity it produces is off by
     * more than a factor of ten. A dose of zero is missing data, not a measurement.
     */
    @Test
    fun `a total daily dose of zero or less is not usable`() {
        assertFalse(KalmanISFCalculator.isUsableTdd(0.0))
        assertFalse(KalmanISFCalculator.isUsableTdd(-5.0))
    }

    @Test
    fun `a total daily dose that is not a number is not usable`() {
        assertFalse(KalmanISFCalculator.isUsableTdd(null))
        assertFalse(KalmanISFCalculator.isUsableTdd(Double.NaN))
        assertFalse(KalmanISFCalculator.isUsableTdd(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `an implausibly small total daily dose is not usable`() {
        assertFalse(KalmanISFCalculator.isUsableTdd(KalmanISFCalculator.MIN_USABLE_TDD_U - 0.01))
    }

    @Test
    fun `a real total daily dose is usable`() {
        assertTrue(KalmanISFCalculator.isUsableTdd(KalmanISFCalculator.MIN_USABLE_TDD_U))
        assertTrue(KalmanISFCalculator.isUsableTdd(55.0))
    }
}
