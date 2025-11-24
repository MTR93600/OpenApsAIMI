package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AdaptivePkPdEstimatorTest {

    @Test
    fun `test initial parameters`() {
        val estimator = AdaptivePkPdEstimator()
        val params = estimator.params()
        assertEquals(4.0, params.diaHrs, 0.0)
        assertEquals(75.0, params.peakMin, 0.0)
    }

    @Test
    fun `test update conditions not met`() {
        val estimator = AdaptivePkPdEstimator()
        val initialParams = estimator.params()

        // Window too small
        estimator.update(1000, 100.0, -5.0, 1.0, 0.0, 10, false)
        assertEquals(initialParams, estimator.params())

        // IOB too low
        estimator.update(1000, 100.0, -5.0, 0.1, 0.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Carbs active
        estimator.update(1000, 100.0, -5.0, 1.0, 10.0, 60, false)
        assertEquals(initialParams, estimator.params())

        // Exercise
        estimator.update(1000, 100.0, -5.0, 1.0, 0.0, 60, true)
        assertEquals(initialParams, estimator.params())
    }

    @Test
    fun `test update changes parameters`() {
        val estimator = AdaptivePkPdEstimator()
        val initialParams = estimator.params()

        // Valid update
        // delta = -5.0 (drop of 5 mg/dl)
        // expected drop calculation depends on actionAt and ISF
        // Let's assume it produces some error and updates parameters
        estimator.update(1000, 100.0, -5.0, 1.0, 0.0, 60, false)
        
        val newParams = estimator.params()
        // It's hard to predict exact values without mocking kernel/ISF provider, 
        // but we can check if it's different or same if error was 0 (unlikely with these numbers)
        // Actually, with default ISF 45, actionAt(60) ~ something positive.
        // expectedDrop ~ action * 1.0 * 45 * 5/60.
        // If delta is -5, drop is 5.
        // If expected != 5, params should change.
        
        // Note: IsfTddProvider is a singleton/object, so we can set it.
        IsfTddProvider.set(45.0)
        
        // We can't easily assert inequality because the change might be small or zero if perfect match.
        // But let's try a case where we expect a change.
        // If actual drop is huge (-20), and expected is small, it should update.
        
        estimator.update(2000, 100.0, -20.0, 1.0, 0.0, 60, false)
        assertNotEquals(initialParams, estimator.params())
    }
}
