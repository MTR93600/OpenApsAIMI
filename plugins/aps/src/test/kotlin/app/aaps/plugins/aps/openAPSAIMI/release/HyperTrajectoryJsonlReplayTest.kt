package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replay-style checks on synthetic JSONL-shaped decision rows (Thomas 12:11–13:01 pattern).
 */
class HyperTrajectoryJsonlReplayTest {

    data class ReplayRow(
        val bg: Double,
        val delta: Double,
        val bestT: Double,
        val floorT: Double,
        val v3Smb: Double,
        val iob: Double,
        val maxIob: Double = 20.0,
        val dwellMin: Int = 10,
    )

    private fun rowToHtr(row: ReplayRow): HyperTrajectoryReleaseResult =
        HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = row.bg,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = row.delta,
                shortAvgDeltaMgdlPer5 = row.delta * 0.9,
                combinedDeltaMgdlPer5 = row.delta,
                floorTerminalMgdl = row.floorT,
                bestTerminalMgdl = row.bestT,
                tdd24hU = 55.0,
                iobU = row.iob,
                maxIobU = row.maxIob,
                maxSmbEffectiveU = 5.0,
                v3SmbU = row.v3Smb,
                dwellAboveHighBgMinutes = row.dwellMin,
                trajectoryType = TrajectoryType.TIGHT_SPIRAL,
                minPredictedBgMgdl = row.bg - 90.0,
            ),
        )

    private fun parseSyntheticLine(json: String): ReplayRow {
        val o = JSONObject(json)
        val scen = o.getJSONObject("adjustments").getJSONObject("scenario_projection")
        return ReplayRow(
            bg = o.getDouble("bg"),
            delta = o.getDouble("delta"),
            bestT = scen.getDouble("best_terminal_mgdl"),
            floorT = scen.getDouble("floor_terminal_mgdl"),
            v3Smb = o.optJSONObject("outcome")?.optDouble("v3_smb_before_u") ?: 0.5,
            iob = o.optDouble("iob", 5.0),
        )
    }

    @Test
    fun rise_window_majority_active() {
        val rows = listOf(
            ReplayRow(152.0, 23.0, 401.0, 39.0, 0.88, 2.5, dwellMin = 5),
            ReplayRow(178.0, 20.0, 401.0, 80.0, 0.55, 5.0),
            ReplayRow(202.0, 18.0, 401.0, 120.0, 0.45, 7.0),
            ReplayRow(226.0, 20.0, 401.0, 147.0, 0.50, 8.77),
        )
        val activeCount = rows.count { rowToHtr(it).active }
        assertTrue(activeCount >= (rows.size * 0.8).toInt(), "expected ≥80% HTR active, got $activeCount/${rows.size}")
    }

    @Test
    fun lifted_smb_exceeds_v3_on_established_tick() {
        val result = rowToHtr(ReplayRow(226.0, 20.0, 401.0, 147.0, 0.50, 8.77))
        assertTrue(result.active)
        assertTrue(result.v3SmbAfterU >= 0.9, "floor was ${result.smbFloorU}")
    }

    @Test
    fun synthetic_jsonl_line_parses_and_replays() {
        val line = """
            {"bg":152,"delta":23,"iob":2.5,"adjustments":{"scenario_projection":{"best_terminal_mgdl":401,"floor_terminal_mgdl":39}},"outcome":{"v3_smb_before_u":0.88}}
        """.trimIndent()
        val row = parseSyntheticLine(line)
        val htr = rowToHtr(row)
        assertTrue(htr.tier == HyperSeverityTier.ANTICIPATORY || htr.tier == HyperSeverityTier.EMERGING)
        assertTrue(htr.active)
    }

    @Test
    fun prolonged_plateau_tick_stays_active() {
        val result = rowToHtr(
            ReplayRow(
                bg = 256.0,
                delta = 0.5,
                bestT = 228.0,
                floorT = 200.0,
                v3Smb = 0.0,
                iob = 10.0,
                dwellMin = 90,
            ),
        )
        assertEquals(HyperSeverityTier.ESTABLISHED, result.tier)
        assertTrue(result.active)
        assertTrue(result.smbFloorU >= 0.7, "floor was ${result.smbFloorU}")
    }

    @Test
    fun night_disables_release() {
        val row = ReplayRow(226.0, 20.0, 401.0, 147.0, 0.50, 8.77)
        val result = HyperTrajectoryReleaseEvaluator.evaluate(
            HyperTrajectoryReleaseEvaluator.Input(
                enabled = true,
                bgMgdl = row.bg,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = row.delta,
                shortAvgDeltaMgdlPer5 = row.delta,
                combinedDeltaMgdlPer5 = row.delta,
                floorTerminalMgdl = row.floorT,
                bestTerminalMgdl = row.bestT,
                tdd24hU = 55.0,
                iobU = row.iob,
                maxIobU = row.maxIob,
                maxSmbEffectiveU = 5.0,
                v3SmbU = row.v3Smb,
                dwellAboveHighBgMinutes = row.dwellMin,
                trajectoryType = TrajectoryType.TIGHT_SPIRAL,
                minPredictedBgMgdl = 130.0,
                isNight = true,
            ),
        )
        assertTrue(!result.active)
    }
}
