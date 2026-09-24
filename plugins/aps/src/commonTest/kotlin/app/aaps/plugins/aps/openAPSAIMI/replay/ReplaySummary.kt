package app.aaps.plugins.aps.openAPSAIMI.replay

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.roundToInt

/**
 * Aggregate view of one replayed day. This is the unit of comparison for every behaviour-changing
 * ADR: run the summary before and after a change and diff it.
 *
 * The glucose bands are descriptive only. Nothing here simulates glucose — a replay reports what
 * was decided and how much insulin was involved, never what blood glucose a different decision
 * would have produced.
 */
data class ReplaySummary(
    val ticks: Int,
    val totalSmbU: Double,
    val smbByOwner: Map<String, Double>,
    val smbByTier: Map<String, Double>,
    /** SMB delivered by Autodrive V3 on ticks the engine itself classified `REBOUND_GUARD`. */
    val autodriveSmbAtReboundGuardU: Double,
    val timeInRangePercent: Double,
    val timeBelow70Percent: Double,
    val timeAbove180Percent: Double,
    val meanBgMgdl: Double,
    val maxIobU: Double,
) {

    fun format(label: String): String = buildString {
        appendLine("$label: $ticks ticks")
        appendLine("  SMB total                     ${aimiFmt2(totalSmbU)} U")
        appendLine("  by owner                      " + smbByOwner.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${aimiFmt2(it.value)}U" })
        appendLine("  by tier                       " + smbByTier.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key}=${aimiFmt2(it.value)}U" })
        appendLine("  Autodrive at REBOUND_GUARD    ${aimiFmt2(autodriveSmbAtReboundGuardU)} U")
        appendLine("  time in range 70-180          ${timeInRangePercent.roundToInt()} %")
        appendLine("  time below 70                 ${aimiFmt1(timeBelow70Percent)} %")
        appendLine("  time above 180                ${aimiFmt1(timeAbove180Percent)} %")
        appendLine("  mean BG                       ${meanBgMgdl.roundToInt()} mg/dL")
        append("  max IOB                       ${aimiFmt2(maxIobU)} U")
    }

    companion object {

        private const val OWNER_AUTODRIVE = "AutodriveV3"
        private const val TIER_REBOUND_GUARD = "REBOUND_GUARD"

        fun of(ticks: List<ReplayTick>): ReplaySummary {
            require(ticks.isNotEmpty()) { "Cannot summarise an empty replay" }
            val bgs = ticks.mapNotNull { it.bgMgdl }
            val dosing = ticks.filter { it.smbU > 0.0 }
            return ReplaySummary(
                ticks = ticks.size,
                totalSmbU = ticks.sumOf { it.smbU },
                smbByOwner = dosing.groupBy { it.originOwner ?: "UNKNOWN" }
                    .mapValues { (_, group) -> group.sumOf { it.smbU } },
                smbByTier = dosing.groupBy { it.correctionAggressionTier ?: "UNKNOWN" }
                    .mapValues { (_, group) -> group.sumOf { it.smbU } },
                autodriveSmbAtReboundGuardU = dosing
                    .filter { it.originOwner == OWNER_AUTODRIVE && it.correctionAggressionTier == TIER_REBOUND_GUARD }
                    .sumOf { it.smbU },
                timeInRangePercent = bgs.percentWhere { it in 70.0..180.0 },
                timeBelow70Percent = bgs.percentWhere { it < 70.0 },
                timeAbove180Percent = bgs.percentWhere { it > 180.0 },
                meanBgMgdl = if (bgs.isEmpty()) 0.0 else bgs.average(),
                maxIobU = ticks.mapNotNull { it.iobU }.maxOrNull() ?: 0.0,
            )
        }

        private fun List<Double>.percentWhere(predicate: (Double) -> Boolean): Double =
            if (isEmpty()) 0.0 else count(predicate) * 100.0 / size
    }
}
