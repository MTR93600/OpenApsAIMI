package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * The step and heart-rate part of the DIA adjustment ("Bio-Sync"), as a pure rule.
 *
 * - STRESS: a high heart rate (over 95 bpm) with few steps (under 100 in 5 min) makes the DIA
 *   [STRESS_FACTOR] times longer.
 * - FLOW: many steps (over 350 in 5 min) make the DIA [FLOW_FACTOR] times shorter, unless the
 *   activity state is already INTENSE (that state has its own factor).
 *
 * `steps == null` means there is no step data. Then neither factor applies: without data we can
 * claim neither a still person nor an active one. Before, unknown steps were read as 0, so a phone
 * with no step source and a heart rate over 95 always got the longer DIA.
 */
internal object BioSyncDiaRule {

    const val STRESS_FACTOR = 1.2f
    const val FLOW_FACTOR = 0.90f

    enum class Kind { NONE, STRESS, FLOW }

    fun decide(steps: Int?, heartRate: Int?, activityIsIntense: Boolean): Kind {
        if (steps == null) return Kind.NONE
        val hr = heartRate ?: 0
        return when {
            hr > 95 && steps < 100          -> Kind.STRESS
            steps > 350 && !activityIsIntense -> Kind.FLOW
            else                            -> Kind.NONE
        }
    }
}
