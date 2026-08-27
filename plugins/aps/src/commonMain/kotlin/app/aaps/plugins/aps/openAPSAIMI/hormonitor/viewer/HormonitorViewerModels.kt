package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

/**
 * Read-only view models for the in-app Hormonitor study viewer.
 *
 * Two tiers, mirroring the exporter ([AimiHormonitorStudyExporterMTR]):
 *  - [HormonitorDaySummary]  ← `AIMI_HORMONITOR_daily_outcomes_v1.jsonl` (1 cumulative record / day, kept latest).
 *  - [HormonitorDayDetail]   ← aggregated from `AIMI_HORMONITOR_event_stream_v1.jsonl` (1 record / loop) for one day.
 *
 * The detail is where the "proof of meaning" lives: how the tree/Harmonia + physio + hormonal context were
 * actually deployed across a day (mode distribution, reason codes, physio/hormonal state, safety gating).
 */

/** min / mean / max over a numeric field, with the number of samples that carried it. */
data class StatRange(
    val min: Double,
    val mean: Double,
    val max: Double,
    val count: Int,
) {
    companion object {
        fun of(values: List<Double>): StatRange? {
            val v = values.filter { it.isFinite() }
            if (v.isEmpty()) return null
            return StatRange(v.min(), v.average(), v.max(), v.size)
        }
    }
}

/** A named frequency, e.g. patient_mode "DAWN_ENDOGENOUS" seen 42 times (share 0.31). */
data class LabelCount(val label: String, val count: Int, val share: Double)

/** Turns a raw label→count map into a share-sorted list (descending). Blank/NULL labels dropped. */
internal fun Map<String, Int>.toLabelCounts(total: Int): List<LabelCount> {
    if (total <= 0) return emptyList()
    return entries
        .filter { it.key.isNotBlank() && !it.key.equals("null", ignoreCase = true) }
        .sortedByDescending { it.value }
        .map { LabelCount(it.key, it.value, it.value.toDouble() / total) }
}

/** One day, from the compact daily_outcomes file. Used for the day list / navigation. */
data class HormonitorDaySummary(
    val dayLocal: String,
    val schemaVersion: String?,
    val tirLowPct: Double?,
    val tirInRangePct: Double?,
    val tirAbovePct: Double?,
    val tdd24hU: Double?,
    val decisionTotal: Int,
    val decisionSmb: Int,
    val decisionSuspend: Int,
    val decisionTbrUp: Int,
    val decisionTbrDown: Int,
    val decisionNone: Int,
    val decisionVeto: Int,
    val sourceReliabilityScore: Double?,
    val sourceStale: Boolean?,
    val sourceOrigin: String?,
)

/** Hormonal deployment across the day. */
data class HormonitorHormonalAgg(
    val cyclePhases: List<LabelCount>,
    val cycleDayRange: StatRange?,
    val cycleTrackingModes: List<LabelCount>,
    val contraceptiveTypes: List<LabelCount>,
    val thyroidStatuses: List<LabelCount>,
    val inflammationStatuses: List<LabelCount>,
    val wcycleBasalMult: StatRange?,
    val wcycleSmbMult: StatRange?,
    val wcycleIsfMult: StatRange?,
)

/** Physio deployment across the day. */
data class HormonitorPhysioAgg(
    val physioStates: List<LabelCount>,
    val activityStates: List<LabelCount>,
    val meanConfidence: Double?,
    val meanDataQuality: Double?,
    val isfFactor: StatRange?,
    val basalFactor: StatRange?,
    val smbFactor: StatRange?,
    val reactivityFactor: StatRange?,
    val steps15m: StatRange?,
    val hrNowBpm: StatRange?,
    val sleepEfficiency: StatRange?,
)

/** Physiological tree + Harmonia + patient-mode deployment — the core "does it make sense" evidence. */
data class HormonitorTreeHarmoniaAgg(
    val patientModes: List<LabelCount>,
    val strategyHints: List<LabelCount>,
    val reasonCodes: List<LabelCount>,
    val finalDecisions: List<LabelCount>,
    val meanModeConfidence: Double?,
    val vetoCount: Int,
    val vetoReasons: List<LabelCount>,
    /** A few distinct patient narratives, as human-readable evidence. */
    val narrativeSamples: List<String>,
)

/** Safety-gate deployment across the day. */
data class HormonitorSafetyAgg(
    val safetyGates: List<LabelCount>,
    val safetyPhases: List<LabelCount>,
    val predictiveHypoSuppressedCount: Int,
    val compositeMinMgdl: StatRange?,
)

/** Structural integrity — the "proof the data is well-formed". */
data class HormonitorIntegrityAgg(
    val recordCount: Int,
    val malformedLineCount: Int,
    val schemaVersions: List<String>,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?,
    /** Share of records that carried a non-null patient_story block (schema ≥ 1.2.0). */
    val patientStoryCoverage: Double,
    /** Share of records that carried a valid physio snapshot flag. */
    val physioSnapshotCoverage: Double,
)

/** Full rich detail for one day, aggregated from the event stream. */
data class HormonitorDayDetail(
    val dayLocal: String,
    val eventCount: Int,
    val hormonal: HormonitorHormonalAgg,
    val physio: HormonitorPhysioAgg,
    val treeHarmonia: HormonitorTreeHarmoniaAgg,
    val safety: HormonitorSafetyAgg,
    val integrity: HormonitorIntegrityAgg,
)
