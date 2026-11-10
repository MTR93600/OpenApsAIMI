package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HarmoniaRuntimeHistoryReaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun readLatestTick_prefersNewestHarmoniaExport() {
        val file = File(tempDir, "AIMI_Decisions.jsonl")
        file.writeText(
            listOf(
                harmoniaTick(
                    timestamp = 1000L,
                    basalFirstChannel = "NONE",
                    active = true,
                    eligible = false,
                    blocker = "POST_HYPO",
                    productionMode = "BLOCKED",
                ),
                harmoniaTick(
                    timestamp = 2000L,
                    basalFirstChannel = "HARMONIA_PRODUCTION_BASAL_FIRST",
                    active = true,
                    eligible = true,
                    selected = true,
                    appliedRateUph = 1.65,
                    appliedDurationMin = 30,
                    productionMode = "APPLIED",
                ),
            ).joinToString("\n"),
        )

        val tick = HarmoniaRuntimeHistoryReader.readLatestTick(file)

        assertThat(tick?.status).isEqualTo(HarmoniaRuntimeTickStatus.NATIVE_APPLIED)
        assertThat(tick?.selectedForProduction).isTrue()
        assertThat(tick?.appliedRateUph).isEqualTo(1.65)
        assertThat(tick?.addsSmbAuthority).isFalse()
        assertThat(tick?.blocker).isNull()
        assertThat(tick?.smbAppliedToRbtDemand).isTrue()
        assertThat(tick?.smbDemandAfterU).isEqualTo(1.2)
    }

    @Test
    fun summarizeLast24Hours_countsHarmoniaRbtOutcomes() {
        val file = File(tempDir, "AIMI_Decisions.jsonl")
        file.writeText(
            listOf(
                harmoniaTick(1L, "HARMONIA_PRODUCTION_BASAL_FIRST", active = true, eligible = true, productionMode = "READY"),
                harmoniaTick(2L, "HARMONIA_PRODUCTION_BASAL_FIRST", active = true, eligible = true, selected = true, appliedRateUph = 1.5, productionMode = "APPLIED"),
                harmoniaTick(3L, "NONE", active = true, eligible = false, blocker = "MEAL_CONFLICT", productionMode = "BLOCKED"),
                harmoniaTick(4L, "T3C_BASAL_FIRST", active = true, eligible = true),
                harmoniaTick(5L, "T3C_BASAL_FIRST", active = true, eligible = true),
                harmoniaTick(6L, "NONE", active = true, eligible = false, blocker = "MEAL_CONFLICT", productionMode = "BLOCKED"),
            ).joinToString("\n"),
        )

        val summary = HarmoniaRuntimeHistoryReader.summarizeLast24Hours(file, nowMs = 10_000L)

        assertThat(summary?.notEnoughData).isFalse()
        assertThat(summary?.nativeAppliedCount).isEqualTo(1)
        assertThat(summary?.nativeReadyCount).isEqualTo(1)
        assertThat(summary?.nativeBlockedCount).isEqualTo(2)
        assertThat(summary?.t3cPriorityCount).isEqualTo(2)
        assertThat(summary?.smbAppliedCount).isEqualTo(1)
        assertThat(summary?.smbReadyCount).isEqualTo(1)
        assertThat(summary?.smbDemandStats?.count).isEqualTo(6)
        assertThat(summary?.dominantBlocker).isEqualTo("MEAL_CONFLICT")
        assertThat(summary?.demandStats?.count).isEqualTo(6)
    }

    private fun harmoniaTick(
        timestamp: Long,
        basalFirstChannel: String,
        active: Boolean,
        eligible: Boolean,
        selected: Boolean = false,
        blocker: String? = null,
        appliedRateUph: Double? = null,
        appliedDurationMin: Int? = null,
        productionMode: String? = null,
        harmoniaSmbApplied: Boolean = selected,
    ): String {
        val harmonia = JSONObject().apply {
            put("active", active)
            put("eligible", eligible)
            put("source_action", "BASAL_FIRST")
            put("branch", "RESISTANCE_PROBABLE")
            put("basal_demand_rate_uph", 1.8)
            put("bounded_rate_uph", 1.6)
            put("max_basal_cap_uph", 5.0)
            put("meal_conflict", blocker == "MEAL_CONFLICT")
            put("post_hypo_block", blocker == "POST_HYPO")
            put("exercise_block", false)
            put("hard_safety_block", false)
            put("dominant_blocker", blocker ?: JSONObject.NULL)
            put("selected_for_production", selected)
            put("applied_rate_uph", appliedRateUph ?: JSONObject.NULL)
            put("applied_duration_min", appliedDurationMin ?: JSONObject.NULL)
            put("runtime_blocker", blocker ?: JSONObject.NULL)
        }
        val production = productionMode?.let { mode ->
            JSONObject().apply {
                put("mode", mode)
                put("selected_for_production", selected)
                put("requested_rate_uph", 1.8)
                put("bounded_rate_uph", 1.6)
                put("applied_rate_uph", appliedRateUph ?: JSONObject.NULL)
                put("applied_duration_min", appliedDurationMin ?: JSONObject.NULL)
                put("runtime_blocker", blocker ?: JSONObject.NULL)
                put("safety_blockers", org.json.JSONArray())
                put("source_action", "BASAL_FIRST")
                put("branch", "RESISTANCE_PROBABLE")
                put("reason", blocker ?: "harmonia_ready")
                put("adds_smb_authority", false)
            }
        }
        val harmoniaSmb = JSONObject().apply {
            put("active", active)
            put("eligible", harmoniaSmbApplied)
            put("source_action", "MEAL_SUPPORT")
            put("branch", "RESISTANCE_PROBABLE")
            put("simulated_smb_u", 1.2)
            put("bounded_smb_u", 1.2)
            put("max_smb_cap_u", 3.0)
            put("demand_before_u", 0.8)
            put("demand_after_u", 1.2)
            put("meal_conflict", false)
            put("post_hypo_block", blocker == "POST_HYPO")
            put("exercise_block", false)
            put("hard_safety_block", false)
            put("dominant_blocker", if (harmoniaSmbApplied) JSONObject.NULL else blocker ?: JSONObject.NULL)
            put("applied_to_rbt_demand", harmoniaSmbApplied)
            put("reduces_rbt_demand", false)
        }
        return JSONObject().apply {
            put("timestamp", timestamp)
            put(
                "adjustments",
                JSONObject().apply {
                    put(
                        "recursive_belief",
                        JSONObject().apply {
                            put("authority_applied", selected)
                            put("shadow_only", false)
                            put(
                                "resolution",
                                JSONObject().apply {
                                    put("basal_first_channel", basalFirstChannel)
                                    put("harmonia_basal_first", harmonia)
                                    put("harmonia_smb", harmoniaSmb)
                                },
                            )
                        },
                    )
                    production?.let { put("harmonia_production", it) }
                },
            )
        }.toString()
    }
}
