package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecursiveBeliefJsonlReplayTest {

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

    @BeforeEach
    fun resetMemory() {
        RecursiveBeliefMemory.clearForTests()
    }

    private fun rowToRbt(row: ReplayRow): RecursiveBeliefSnapshot {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = row.v3Smb,
            bestTerminal = row.bestT,
            bg = row.bg,
            delta = row.delta,
            iob = row.iob,
            maxIob = row.maxIob,
            floorTerminal = row.floorT,
            dwellMin = row.dwellMin,
            replaceHtrRelease = true,
        )
        val scales = RecursiveBeliefEngine.build(ctx, nowMs = 1_780_321_706_128L, waveletEnabled = false)
        return RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
    }

    @Test
    fun rise_window_majority_has_release_authority() {
        val rows = listOf(
            ReplayRow(152.0, 23.0, 401.0, 39.0, 0.88, 2.5, dwellMin = 5),
            ReplayRow(178.0, 20.0, 401.0, 80.0, 0.55, 5.0),
            ReplayRow(202.0, 18.0, 401.0, 120.0, 0.45, 7.0),
            ReplayRow(226.0, 20.0, 401.0, 147.0, 0.50, 8.77),
        )
        val activeCount = rows.count {
            rowToRbt(it).resolutions.releaseAuthority != ReleaseAuthority.NONE
        }
        assertThat(activeCount).isAtLeast((rows.size * 0.75).toInt())
    }

    @Test
    fun jsonl_export_contains_recursive_belief_section() {
        val snapshot = rowToRbt(ReplayRow(226.0, 20.0, 401.0, 147.0, 0.50, 8.77))
        val export = UnfoldExporter.toExport(
            snapshot = snapshot,
            shadowOnly = false,
            authorityApplied = true,
            authorityGate = RecursiveBeliefAuthorityGate.Decision(
                requestedAuthority = ReleaseAuthority.HARD,
                maxAllowedAuthority = ReleaseAuthority.SOFT,
                effectiveAuthority = ReleaseAuthority.SOFT,
                readinessScore = 0.64,
                liftBlend = 0.68,
                reasonCodes = listOf("MEAL_SUPPRESS"),
            ),
        )
        val json = UnfoldExporter.toJsonObject(export)
        assertThat(json.getInt("version")).isEqualTo(1)
        assertThat(json.getJSONArray("scales").length()).isEqualTo(4)
        assertThat(json.getJSONObject("resolution").getDouble("smb_demand_u")).isGreaterThan(0.0)
        assertThat(json.getJSONObject("authority_gate").getString("effective_authority")).isEqualTo("SOFT")
    }

    @Test
    fun floor_vs_reality_paradox_on_minpred_39_pattern() {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 0.88,
            bestTerminal = 401.0,
            floorTerminal = 39.0,
            bg = 178.0,
            delta = 23.0,
            hypoMinPredIgnored = false,
        )
        val scales = RecursiveBeliefEngine.build(ctx, nowMs = 1_780_321_706_128L)
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
        assertThat(snapshot.paradoxes.map { it.id }).contains(BeliefParadoxId.FLOOR_VS_REALITY)
    }
}
