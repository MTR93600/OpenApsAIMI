package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Proves the viewer reads the compact daily file and correctly aggregates the rich event stream for one day:
 * tree/Harmonia mode distribution, hormonal + safety labels, and structural-integrity coverage.
 */
class HormonitorReaderTest {

    @TempDir
    lateinit var dir: File

    private val baseTs = 1_700_000_000_000L
    private fun dayOf(ts: Long) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ts))

    private fun event(ts: Long, mode: String, physio: String, gate: String, thyroid: String): String =
        JSONObject().apply {
            put("timestamp", ts)
            put("schema_version", "1.4.0")
            put("physio_state", physio)
            put("physio_confidence", 0.8)
            put("safety_gate", gate)
            put("thyroid_status", thyroid)
            put("physio_snapshot_valid_flag", true)
            put("smb_factor", 1.0)
            put(
                "patient_story",
                JSONObject().apply {
                    put("patient_mode", mode)
                    put("patient_mode_confidence", 0.9)
                    put("patient_narrative", "narrative for $mode")
                    put("patient_reason_codes", JSONArray().apply { put("CAUSAL_$mode") })
                },
            )
        }.toString()

    @Test
    fun `reads day summary and aggregates event detail`() = runBlocking {
        val day = dayOf(baseTs)
        File(dir, "AIMI_HORMONITOR_daily_outcomes_v1.jsonl").writeText(
            JSONObject().apply {
                put("day_local", day)
                put("generated_at", "2026-01-01T00:00:00Z")
                put("schema_version", "1.4.0")
                put("tir_in_range_pct", 82.0)
                put("tdd_24h_total_u", 45.0)
                put("decision_count_total", 3)
                put("decision_count_smb", 1)
                put("source_reliability_score", 0.9)
            }.toString() + "\n",
        )
        File(dir, "AIMI_HORMONITOR_event_stream_v1.jsonl").writeText(
            listOf(
                event(baseTs, "DAWN_ENDOGENOUS", "MALE_CIRCADIAN_HORMONAL", "SafetyPass", "EUTHYROID"),
                event(baseTs + 300_000, "DAWN_ENDOGENOUS", "RESTING", "SafetyLGS_T2", "EUTHYROID"),
                event(baseTs + 600_000, "MEAL", "RESTING", "SafetyPass", "EUTHYROID"),
            ).joinToString("\n"),
        )

        val reader = HormonitorReader(listOf(dir))

        val days = reader.readDays()
        assertThat(days).hasSize(1)
        assertThat(days[0].dayLocal).isEqualTo(day)
        assertThat(days[0].decisionSmb).isEqualTo(1)
        assertThat(days[0].tirInRangePct).isEqualTo(82.0)

        val detail = reader.readDayDetail(day)
        assertThat(detail).isNotNull()
        assertThat(detail!!.eventCount).isEqualTo(3)
        // Tree/Harmonia: DAWN_ENDOGENOUS is dominant (2/3).
        assertThat(detail.treeHarmonia.patientModes.first().label).isEqualTo("DAWN_ENDOGENOUS")
        assertThat(detail.treeHarmonia.patientModes.first().count).isEqualTo(2)
        assertThat(detail.treeHarmonia.reasonCodes.map { it.label }).contains("CAUSAL_DAWN_ENDOGENOUS")
        assertThat(detail.treeHarmonia.narrativeSamples).isNotEmpty()
        // Hormonal + safety labels present.
        assertThat(detail.hormonal.thyroidStatuses.first().label).isEqualTo("EUTHYROID")
        assertThat(detail.safety.safetyGates.map { it.label }).containsExactly("SafetyPass", "SafetyLGS_T2")
        // Integrity coverage.
        assertThat(detail.integrity.recordCount).isEqualTo(3)
        assertThat(detail.integrity.patientStoryCoverage).isWithin(0.001).of(1.0)
        assertThat(detail.integrity.physioSnapshotCoverage).isWithin(0.001).of(1.0)
    }

    @Test
    fun `returns null detail for a day with no events`() = runBlocking {
        File(dir, "AIMI_HORMONITOR_event_stream_v1.jsonl").writeText(
            event(baseTs, "MEAL", "RESTING", "SafetyPass", "EUTHYROID"),
        )
        val reader = HormonitorReader(listOf(dir))
        assertThat(reader.readDayDetail("1999-01-01")).isNull()
    }
}
