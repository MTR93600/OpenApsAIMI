package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BasalDecisionEngineTest {

    private val context: Context = mockk(relaxed = true)
    private val aimiAdaptiveBasal: AIMIAdaptiveBasal = mockk(relaxed = true)
    private val basalPlanner: BasalPlanner = mockk(relaxed = true)
    private val engine = BasalDecisionEngine(context, aimiAdaptiveBasal, basalPlanner)

    @Test
    fun `test interpolateBasalFactor`() {
        // < 80 -> 0.5
        assertEquals(0.5, engine.interpolateBasalFactor(70.0), 0.01)
        // 100 -> 2.0
        assertEquals(2.0, engine.interpolateBasalFactor(100.0), 0.01)
        // 120 -> 2.0 (start of next segment)
        assertEquals(2.0, engine.interpolateBasalFactor(120.0), 0.01)
        // 180 -> 5.0
        assertEquals(5.0, engine.interpolateBasalFactor(180.0), 0.01)
        // > 180 -> 5.0
        assertEquals(5.0, engine.interpolateBasalFactor(200.0), 0.01)
    }

    @Test
    fun `test smoothBasalRate`() {
        // weightRecent = 0.6, weightPrevious = 0.4
        // weightedTdd = 50*0.6 + 40*0.4 = 30 + 16 = 46
        // ratio = 46 / 50 = 0.92
        // adjusted = 1.0 * 0.92 = 0.92
        val result = engine.smoothBasalRate(50f, 40f, 1.0f)
        assertEquals(0.92f, result, 0.01f)
    }

    @Test
    fun `test computeFinalBasalRate`() {
        // smoothBasal = 0.92
        // interpolate(100) -> 2.0 * 0.8 (lower range weight) = 1.6?
        // Wait, interpolate logic:
        // xdata < 80 -> 0.5
        // ...
        // if xdata > 100 -> newVal * higherBasalRangeWeight (1.5)
        // else -> newVal * lowerBasalRangeWeight (0.8)
        
        // At 100:
        // interpolate returns 2.0 (from polyY)
        // 100 is not > 100, so * 0.8 -> 1.6
        
        // final = 0.92 * 1.6 = 1.472
        
        val result = engine.computeFinalBasalRate(100.0, 50f, 40f, 1.0f)
        assertEquals(1.472, result, 0.01)
    }
}
