package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The 24h Harmonia summary the Profile Advisor renders.
 *
 * The fixture lines copy the real writer: the root `timestamp` and `adjustments` of
 * `DetermineBasalAIMI2.buildDecisionJson`, the `harmonia_basal_first` and `harmonia_smb` blocks of
 * `app.aaps.plugins.aps.openAPSAIMI.recursive.UnfoldExporter.toJsonObject`, and the
 * `harmonia_production` block of `HarmoniaProductionDecision.toJsonObject`.
 */
class HarmoniaRuntimeHistoryReaderTest {

    @TempDir
    lateinit var tempDir: File

    private val nowMs = 1_800_000_000_000L
    private val oneHourMs = 60L * 60L * 1000L

    @Test
    fun a_missing_file_gives_null() {
        // Nothing was ever written under this storage's "AIMI_Decisions.jsonl" - the reader resolves
        // the path itself now, so "missing" means an empty directory rather than a differently named file.
        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(
            storage = storageFor(tempDir),
            nowMs = nowMs,
        )
        assertThat(summary).isNull()
    }

    @Test
    fun ticks_older_than_the_window_give_an_empty_summary() {
        val storage = writeLines(
            listOf(
                harmoniaLine(nowMs - 30 * oneHourMs, productionMode = "APPLIED"),
                harmoniaLine(nowMs - 29 * oneHourMs, productionMode = "APPLIED"),
            ),
        )

        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(storage = storage, nowMs = nowMs)

        assertThat(summary).isNotNull()
        assertThat(summary!!.tickCount).isEqualTo(0)
        assertThat(summary.notEnoughData).isTrue()
        assertThat(summary.dominantStatus).isNull()
        assertThat(summary.demandStats).isNull()
    }

    @Test
    fun a_full_window_is_counted_per_status() {
        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(storage = writeLines(eightTickWindow()), nowMs = nowMs)

        assertThat(summary).isNotNull()
        assertThat(summary!!.tickCount).isEqualTo(8)
        assertThat(summary.notEnoughData).isFalse()
        assertThat(summary.dominantStatus).isEqualTo(HarmoniaRuntimeTickStatus.NATIVE_APPLIED)
        assertThat(summary.nativeAppliedCount).isEqualTo(5)
        assertThat(summary.nativeReadyCount).isEqualTo(0)
        assertThat(summary.nativeBlockedCount).isEqualTo(2)
        assertThat(summary.t3cPriorityCount).isEqualTo(1)
        assertThat(summary.dominantBlocker).isEqualTo("hard_safety_block")
    }

    @Test
    fun smb_modulation_is_counted_on_its_own() {
        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(storage = writeLines(eightTickWindow()), nowMs = nowMs)!!

        assertThat(summary.smbAppliedCount).isEqualTo(5)
        assertThat(summary.smbReadyCount).isEqualTo(5)
        assertThat(summary.smbBlockedCount).isEqualTo(2)
        assertThat(summary.smbDemandStats!!.count).isEqualTo(5)
        assertThat(summary.smbDemandStats!!.average).isWithin(TOLERANCE).of(0.30)
    }

    @Test
    fun rate_statistics_skip_the_ticks_that_applied_nothing() {
        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(storage = writeLines(eightTickWindow()), nowMs = nowMs)!!

        assertThat(summary.demandStats!!.count).isEqualTo(8)
        assertThat(summary.demandStats!!.min).isWithin(TOLERANCE).of(0.90)
        assertThat(summary.demandStats!!.max).isWithin(TOLERANCE).of(1.50)
        assertThat(summary.appliedRateStats!!.count).isEqualTo(5)
        assertThat(summary.appliedRateStats!!.average).isWithin(TOLERANCE).of(1.50)
    }

    @Test
    fun a_broken_line_in_the_middle_is_skipped() {
        val lines = eightTickWindow().toMutableList()
        lines.add(4, "{\"timestamp\": broken")
        lines.add(5, """{"timestamp":${nowMs - 4 * oneHourMs},"reason":"no adjustments here"}""")

        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(storage = writeLines(lines), nowMs = nowMs)!!

        assertThat(summary.tickCount).isEqualTo(8)
        assertThat(summary.nativeAppliedCount).isEqualTo(5)
    }

    // ----- fixture -----

    /** Five applied ticks, then two blocked ones, then one where T3C kept priority. */
    private fun eightTickWindow(): List<String> = buildList {
        repeat(5) { index ->
            add(harmoniaLine(nowMs - (20 - index) * oneHourMs, productionMode = "APPLIED"))
        }
        repeat(2) { index ->
            add(
                harmoniaLine(
                    timestampMs = nowMs - (14 - index) * oneHourMs,
                    productionMode = "BLOCKED",
                    eligible = false,
                    boundedRateUph = 0.90,
                    appliedRateUph = null,
                    dominantBlocker = "hard_safety_block",
                    smbEligible = false,
                    smbApplied = false,
                    smbDemandAfterU = 0.0,
                    smbBlocker = "post_hypo_block",
                ),
            )
        }
        add(
            harmoniaLine(
                timestampMs = nowMs - 12 * oneHourMs,
                productionMode = "SKIPPED",
                basalFirstChannel = "T3C_BASAL_FIRST",
                boundedRateUph = 1.20,
                appliedRateUph = null,
                smbEligible = false,
                smbApplied = false,
                smbDemandAfterU = 0.0,
            ),
        )
    }

    /** Writes the fixture as the real "AIMI_Decisions.jsonl" the reader now resolves by itself. */
    private fun writeLines(lines: List<String>): AimiStorage {
        File(tempDir, "AIMI_Decisions.jsonl").writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        return storageFor(tempDir)
    }

    /** An [AimiStorage] whose AIMI directory is [dir] - the mocked helper is never asked anything else. */
    private fun storageFor(dir: File): AimiStorage {
        val helper = mock<AimiStorageHelper>()
        whenever(helper.getAimiFile("AIMI_Decisions.jsonl")).thenReturn(File(dir, "AIMI_Decisions.jsonl"))
        return AndroidAimiStorage(helper)
    }

    private fun harmoniaLine(
        timestampMs: Long,
        productionMode: String,
        basalFirstChannel: String = "HARMONIA_PRODUCTION_BASAL_FIRST",
        active: Boolean = true,
        eligible: Boolean = true,
        boundedRateUph: Double? = 1.50,
        appliedRateUph: Double? = 1.50,
        dominantBlocker: String? = null,
        smbEligible: Boolean = true,
        smbApplied: Boolean = true,
        smbDemandAfterU: Double = 0.30,
        smbBlocker: String? = null,
    ): String {
        val selected = productionMode == "APPLIED"
        return """{"timestamp":$timestampMs,"adjustments":{"recursive_belief":{"version":1,""" +
            """"shadow_only":false,"authority_applied":$selected,"paradoxes":[],""" +
            """"resolution":{"smb_demand_u":0.4,"release_authority":"SOFT",""" +
            """"basal_first_channel":"$basalFirstChannel","harmonia_basal_first":{""" +
            """"active":$active,"eligible":$eligible,"source_action":"BASAL_FIRST","branch":"rise",""" +
            """"basal_demand_rate_uph":${jsonNumber(boundedRateUph)},"bounded_rate_uph":${jsonNumber(boundedRateUph)},""" +
            """"max_basal_cap_uph":3.0,"meal_conflict":false,"post_hypo_block":false,""" +
            """"exercise_block":false,"hard_safety_block":false,"dominant_blocker":${jsonString(dominantBlocker)},""" +
            """"reason_codes":[],"selected_for_production":$selected,""" +
            """"applied_rate_uph":${jsonNumber(appliedRateUph)},"applied_duration_min":30,"runtime_blocker":null},""" +
            """"harmonia_smb":{"active":$active,"eligible":$smbEligible,"source_action":"SMB",""" +
            """"branch":"rise","simulated_smb_u":0.35,"bounded_smb_u":0.30,"max_smb_cap_u":1.0,""" +
            """"demand_before_u":0.40,"demand_after_u":$smbDemandAfterU,"meal_conflict":false,""" +
            """"post_hypo_block":false,"exercise_block":false,"hard_safety_block":false,""" +
            """"dominant_blocker":${jsonString(smbBlocker)},"reason_codes":[],""" +
            """"applied_to_rbt_demand":$smbApplied,"reduces_rbt_demand":false,""" +
            """"authority_mode":"SOFT","adds_smb_authority":true,"insulin_intent":"PROTECTIVE"}}},""" +
            """"harmonia_production":{"timestamp":$timestampMs,"version":1,"mode":"$productionMode",""" +
            """"selected_for_production":$selected,"requested_rate_uph":${jsonNumber(boundedRateUph)},""" +
            """"bounded_rate_uph":${jsonNumber(boundedRateUph)},"applied_rate_uph":${jsonNumber(appliedRateUph)},""" +
            """"applied_matches_request":$selected,"applied_duration_min":30,"runtime_blocker":null,""" +
            """"safety_blockers":[],"source_action":"BASAL_FIRST","branch":"rise","reason":"harmonia",""" +
            """"basal_first_only":true,"adds_smb_authority":false,"applies_to_pump":$selected,""" +
            """"source":"harmonia_production_branch_v1"}}}"""
    }

    private fun jsonNumber(value: Double?): String = value?.toString() ?: "null"

    private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private companion object {

        /** The fixture values come back through a JSON parse, so compare rates with a tolerance. */
        const val TOLERANCE = 1e-9
    }
}
