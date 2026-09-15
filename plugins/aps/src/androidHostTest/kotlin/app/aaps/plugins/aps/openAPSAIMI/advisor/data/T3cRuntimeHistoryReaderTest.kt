package app.aaps.plugins.aps.openAPSAIMI.advisor.data

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The 24h T3C summary the Profile Advisor renders.
 *
 * The fixture lines copy the real writer: the root `timestamp` and `adjustments` that
 * `DetermineBasalAIMI2` writes, the `recursive_belief` block of `UnfoldExporter.toJsonObject`, and
 * the four-field `t3c_runtime_ownership` block `DetermineBasalAIMI2` writes by hand.
 */
class T3cRuntimeHistoryReaderTest {

    @TempDir
    lateinit var tempDir: File

    private val nowMs = 1_800_000_000_000L
    private val oneHourMs = 60L * 60L * 1000L

    @Test
    fun a_missing_file_gives_null() {
        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(
            file = File(tempDir, "no_such_file.jsonl"),
            nowMs = nowMs,
        )
        assertThat(summary).isNull()
    }

    @Test
    fun ticks_older_than_the_window_give_an_empty_summary() {
        val file = writeLines(
            listOf(
                t3cLine(nowMs - 30 * oneHourMs, mode = "NATIVE_APPLIED", reason = APPLIED_REASON),
                t3cLine(nowMs - 29 * oneHourMs, mode = "NATIVE_APPLIED", reason = APPLIED_REASON),
                t3cLine(nowMs - 28 * oneHourMs, mode = "NATIVE_APPLIED", reason = APPLIED_REASON),
            ),
        )

        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = file, nowMs = nowMs)

        assertThat(summary).isNotNull()
        assertThat(summary!!.tickCount).isEqualTo(0)
        assertThat(summary.notEnoughData).isTrue()
        assertThat(summary.dominantStatus).isNull()
        assertThat(summary.familyObservations).isEmpty()
    }

    @Test
    fun a_full_window_is_counted_per_status() {
        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = writeLines(tenTickWindow()), nowMs = nowMs)

        assertThat(summary).isNotNull()
        assertThat(summary!!.tickCount).isEqualTo(10)
        assertThat(summary.notEnoughData).isFalse()
        assertThat(summary.dominantStatus).isEqualTo(T3cRuntimeTickStatus.NATIVE_APPLIED)
        assertThat(summary.nativeAppliedCount).isEqualTo(6)
        assertThat(summary.nativeBlockedCount).isEqualTo(2)
        assertThat(summary.legacyFallbackCount).isEqualTo(2)
        assertThat(summary.safetyTerminalCount).isEqualTo(0)
    }

    @Test
    fun rate_statistics_skip_the_ticks_that_applied_nothing() {
        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = writeLines(tenTickWindow()), nowMs = nowMs)!!

        // Every tick carries a bounded demand, only the six applied ones carry an applied rate.
        assertThat(summary.demandStats!!.count).isEqualTo(10)
        assertThat(summary.demandStats!!.min).isWithin(TOLERANCE).of(0.80)
        assertThat(summary.demandStats!!.max).isWithin(TOLERANCE).of(1.10)
        assertThat(summary.appliedRateStats!!.count).isEqualTo(6)
        assertThat(summary.appliedRateStats!!.average).isWithin(TOLERANCE).of(1.10)
    }

    @Test
    fun the_ownership_change_at_the_end_of_the_window_is_reported() {
        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = writeLines(tenTickWindow()), nowMs = nowMs)!!

        assertThat(summary.transitionCount).isEqualTo(1)
        assertThat(summary.dominantTransition).isEqualTo(
            T3cOwnershipTransition(
                from = T3cRuntimeOwnershipCategory.NATIVE,
                to = T3cRuntimeOwnershipCategory.LEGACY,
                count = 1,
            ),
        )
    }

    @Test
    fun family_signals_are_ordered_by_weight() {
        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = writeLines(tenTickWindow()), nowMs = nowMs)!!

        assertThat(summary.familyObservations.map { it.family })
            .containsExactly(
                T3cAdvisorObservationFamily.MEAL_CAPTURE,
                T3cAdvisorObservationFamily.NATIVE_RBT,
                T3cAdvisorObservationFamily.AUTONOMY,
            ).inOrder()
        val weights = summary.familyObservations.map { it.weight }
        assertThat(weights).isEqualTo(weights.sortedDescending())
    }

    @Test
    fun a_broken_line_in_the_middle_is_skipped() {
        val lines = tenTickWindow().toMutableList()
        lines.add(5, "{\"timestamp\": broken")
        // A well formed line of another kind must be skipped just as quietly.
        lines.add(6, """{"timestamp":${nowMs - 5 * oneHourMs},"reason":"no adjustments here"}""")

        val summary = T3cRuntimeHistoryReader.summarizeLast24Hours(file = writeLines(lines), nowMs = nowMs)!!

        assertThat(summary.tickCount).isEqualTo(10)
        assertThat(summary.nativeAppliedCount).isEqualTo(6)
    }

    // ----- fixture -----

    /** Six applied ticks, then two blocked ones, then two legacy fallbacks, all inside the window. */
    private fun tenTickWindow(): List<String> = buildList {
        repeat(6) { index ->
            add(t3cLine(nowMs - (20 - index) * oneHourMs, mode = "NATIVE_APPLIED", reason = APPLIED_REASON))
        }
        repeat(2) { index ->
            add(
                t3cLine(
                    timestampMs = nowMs - (14 - index) * oneHourMs,
                    mode = "NATIVE_BLOCKED",
                    reason = "t3c_runtime_blocked",
                    eligible = false,
                    boundedRateUph = 0.80,
                    appliedRateUph = null,
                    runtimeBlocker = "meal_conflict",
                    nativeOwnerActive = true,
                    legacyFallbackAllowed = false,
                ),
            )
        }
        repeat(2) { index ->
            add(
                t3cLine(
                    timestampMs = nowMs - (12 - index) * oneHourMs,
                    mode = "LEGACY_FALLBACK",
                    reason = "legacy_bypass",
                    eligible = false,
                    boundedRateUph = 0.80,
                    appliedRateUph = null,
                    nativeOwnerActive = false,
                    legacyFallbackAllowed = true,
                ),
            )
        }
    }

    private fun writeLines(lines: List<String>): File =
        File(tempDir, "AIMI_Decisions.jsonl").apply { writeText(lines.joinToString(separator = "\n", postfix = "\n")) }

    private fun t3cLine(
        timestampMs: Long,
        mode: String,
        reason: String,
        active: Boolean = true,
        eligible: Boolean = true,
        boundedRateUph: Double? = 1.10,
        appliedRateUph: Double? = 1.10,
        runtimeBlocker: String? = null,
        nativeOwnerActive: Boolean = true,
        legacyFallbackAllowed: Boolean = false,
    ): String {
        val authorityApplied = mode == "NATIVE_APPLIED"
        return """{"timestamp":$timestampMs,"adjustments":{"recursive_belief":{"version":1,""" +
            """"shadow_only":false,"authority_applied":$authorityApplied,"paradoxes":[],""" +
            """"resolution":{"smb_demand_u":0.4,"release_authority":"SOFT",""" +
            """"basal_first_channel":"T3C_BASAL_FIRST","t3c_basal_first":{""" +
            """"active":$active,"eligible":$eligible,"basal_demand_rate_uph":${jsonNumber(boundedRateUph)},""" +
            """"bounded_rate_uph":${jsonNumber(boundedRateUph)},"max_basal_cap_uph":3.0,""" +
            """"anticipation_strength":0.5,"meal_conflict":false,"post_hypo_block":false,""" +
            """"exercise_block":false,"hard_safety_block":false,"dominant_blocker":null,""" +
            """"reason_codes":[],"selected_for_production":$authorityApplied,""" +
            """"historical_bypass_neutralized":false,"applied_rate_uph":${jsonNumber(appliedRateUph)},""" +
            """"applied_duration_min":30,"runtime_blocker":${jsonString(runtimeBlocker)}}}},""" +
            """"t3c_runtime_ownership":{"mode":"$mode","native_owner_active":$nativeOwnerActive,""" +
            """"legacy_fallback_allowed":$legacyFallbackAllowed,"reason":"$reason"}}}"""
    }

    private fun jsonNumber(value: Double?): String = value?.toString() ?: "null"

    private fun jsonString(value: String?): String = value?.let { "\"$it\"" } ?: "null"

    private companion object {

        const val APPLIED_REASON = "t3c_basal_first_applied"

        /** The fixture values come back through a JSON parse, so compare rates with a tolerance. */
        const val TOLERANCE = 1e-9
    }
}
