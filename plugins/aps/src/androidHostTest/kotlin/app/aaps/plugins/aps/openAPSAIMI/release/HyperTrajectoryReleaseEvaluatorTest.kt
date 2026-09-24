package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HyperTrajectoryReleaseEvaluatorTest {

    private fun thomas1251Input(v3Smb: Double) = HyperTrajectoryReleaseEvaluator.Input(
        enabled = true,
        bgMgdl = 226.0,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = 20.0,
        shortAvgDeltaMgdlPer5 = 18.0,
        combinedDeltaMgdlPer5 = 20.0,
        floorTerminalMgdl = 147.0,
        bestTerminalMgdl = 401.0,
        tdd24hU = 55.0,
        iobU = 8.77,
        maxIobU = 20.0,
        maxSmbEffectiveU = 5.0,
        v3SmbU = v3Smb,
        dwellAboveHighBgMinutes = 15,
        trajectoryType = TrajectoryType.TIGHT_SPIRAL,
        minPredictedBgMgdl = 136.0,
    )

    @Test
    fun thomas1251_lifts_v3_from_half_unit() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(thomas1251Input(v3Smb = 0.50))
        assertTrue(result.active)
        assertEquals(HyperSeverityTier.ESTABLISHED, result.tier)
        assertTrue(result.smbFloorU >= 1.0)
        assertTrue(result.v3SmbAfterU >= result.v3SmbBeforeU + 0.4)
    }

    @Test
    fun thomas1226_anticipatory_at_bg152() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = 152.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 23.0,
                shortAvgDeltaMgdlPer5 = 20.0,
                combinedDeltaMgdlPer5 = 23.0,
                floorTerminalMgdl = 39.0,
                bestTerminalMgdl = 401.0,
                tdd24hU = 55.0,
                iobU = 2.5,
                maxIobU = 20.0,
                maxSmbEffectiveU = 5.0,
                v3SmbU = 0.88,
                dwellAboveHighBgMinutes = 5,
                trajectoryType = TrajectoryType.OPEN_DIVERGING,
                minPredictedBgMgdl = 125.0,
            ),
        )
        assertTrue(result.tier == HyperSeverityTier.ANTICIPATORY || result.tier == HyperSeverityTier.EMERGING)
        assertTrue(result.smbFloorU >= 0.85)
    }

    @Test
    fun hypo_incoherent_minPred_flagged_at_bg243() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(
            thomas1251Input(v3Smb = 0.0).copy(
                bgMgdl = 243.0,
                minPredictedBgMgdl = 39.0,
                deltaMgdlPer5 = -7.0,
                shortAvgDeltaMgdlPer5 = -6.0,
                combinedDeltaMgdlPer5 = -7.0,
            ),
        )
        assertTrue(result.hypoMinPredIgnored)
    }

    @Test
    fun plateau_deep_when_projection_no_longer_leads_bg() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = 253.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 2.0,
                shortAvgDeltaMgdlPer5 = 2.0,
                combinedDeltaMgdlPer5 = 2.0,
                floorTerminalMgdl = 180.0,
                bestTerminalMgdl = 226.0,
                tdd24hU = 55.0,
                iobU = 10.0,
                maxIobU = 20.0,
                maxSmbEffectiveU = 5.0,
                v3SmbU = 0.3,
                dwellAboveHighBgMinutes = 45,
                trajectoryType = TrajectoryType.TIGHT_SPIRAL,
                minPredictedBgMgdl = 200.0,
            ),
        )
        assertEquals(HyperSeverityTier.DEEP, result.tier)
    }

    @Test
    fun kfc_afternoon_plateau_sustain_lifts_above_throttled_deep() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = 256.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 0.5,
                shortAvgDeltaMgdlPer5 = 0.4,
                combinedDeltaMgdlPer5 = 0.5,
                floorTerminalMgdl = 200.0,
                bestTerminalMgdl = 228.0,
                tdd24hU = 55.0,
                iobU = 10.0,
                maxIobU = 20.0,
                maxSmbEffectiveU = 5.0,
                v3SmbU = 0.0,
                dwellAboveHighBgMinutes = 90,
                trajectoryType = TrajectoryType.TIGHT_SPIRAL,
                minPredictedBgMgdl = 200.0,
                aggressive = true,
            ),
        )
        assertEquals(HyperSeverityTier.ESTABLISHED, result.tier)
        assertTrue(result.active)
        assertTrue(result.smbFloorU >= 0.75, "floor was ${result.smbFloorU}")
        assertTrue(result.reason.contains("plateauSustain"))
    }

    @Test
    fun aggressive_raises_floor() {
        val normal = HyperTrajectoryReleaseEvaluator.evaluate(thomas1251Input(v3Smb = 0.5))
        val base = thomas1251Input(v3Smb = 0.5)
        val aggressive = HyperTrajectoryReleaseEvaluator.evaluate(base.copy(aggressive = true))
        assertTrue(aggressive.smbFloorU >= normal.smbFloorU * 1.1)
    }

    @Test
    fun master_switch_off() {
        val result = HyperTrajectoryReleaseEvaluator.evaluate(thomas1251Input(v3Smb = 0.5).copy(enabled = false))
        assertEquals(false, result.active)
        assertEquals(0.5, result.v3SmbAfterU, 0.01)
    }
}
