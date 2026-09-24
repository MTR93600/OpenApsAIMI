package app.aaps.plugins.aps.openAPSAIMI.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IobConsensusTest {

    @Test
    fun alignedEnginesTrustAaps() {
        val result = IobConsensus.resolve(aapsIobUnits = 1.2, pkpdIobUnits = 1.25)
        assertEquals(IobDecisionSource.AAPS_ALIGNED, result.source)
        assertEquals(1.2, result.decisionIobUnits, 0.001)
    }

    @Test
    fun negativeAapsWithPositivePkpdTrustsPkpd() {
        val result = IobConsensus.resolve(aapsIobUnits = -0.30, pkpdIobUnits = 0.45)
        assertEquals(IobDecisionSource.PKPD_WHEN_AAPS_NEGATIVE, result.source)
        assertEquals(0.45, result.decisionIobUnits, 0.001)
    }

    @Test
    fun negativeAapsWithLowPkpdKeepsAaps() {
        val result = IobConsensus.resolve(aapsIobUnits = -0.30, pkpdIobUnits = 0.05)
        assertEquals(IobDecisionSource.AAPS_DEFAULT, result.source)
        assertEquals(-0.30, result.decisionIobUnits, 0.001)
    }

    @Test
    fun missingPkpdUsesAaps() {
        val result = IobConsensus.resolve(aapsIobUnits = 0.8, pkpdIobUnits = null)
        assertEquals(IobDecisionSource.AAPS_DEFAULT, result.source)
        assertEquals(0.8, result.decisionIobUnits, 0.001)
    }
}
