package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.max
import kotlin.math.min

/**
 * Top-down credibility propagation — AIMI_RECURSIVE_BELIEF.md §6.2.
 *
 * cred(parent leaf) = min(cred, max(childAvgCred · (1 − tension(parent,child))))
 */
object CredibilityCascade {

    private val PARENT_CHILD = listOf(
        480 to 180,
        180 to 60,
        60 to 15,
    )

    fun apply(scales: List<BeliefScaleNode>, tensions: List<ScaleTension>): List<BeliefScaleNode> {
        val byTau = scales.associateBy { it.horizonMinutes }.toMutableMap()
        for ((parentTau, childTau) in PARENT_CHILD) {
            val parent = byTau[parentTau] ?: continue
            val child = byTau[childTau] ?: continue
            val tension = tensions.firstOrNull {
                it.parentTauMin == parentTau && it.childTauMin == childTau
            }?.magnitude ?: 0.0
            val childAvgCred = child.leaves.map { it.credibility }.average().takeIf { !it.isNaN() } ?: 0.0
            val cap = max(0.0, childAvgCred * (1.0 - tension.coerceIn(0.0, 1.0)))
            val updatedLeaves = parent.leaves.map { leaf ->
                leaf.copy(credibility = min(leaf.credibility, cap.coerceIn(0.0, 1.0)))
            }
            byTau[parentTau] = parent.copy(leaves = updatedLeaves)
        }
        return scales.map { byTau[it.horizonMinutes] ?: it }
    }
}
