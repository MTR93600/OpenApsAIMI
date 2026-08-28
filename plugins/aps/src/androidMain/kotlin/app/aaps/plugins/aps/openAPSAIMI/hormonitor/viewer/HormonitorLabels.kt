package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import java.util.Locale

/**
 * Humanizes raw enum-ish labels (patient_mode, safety_gate, physio_state, …) for DISPLAY IN THE VIEWER ONLY.
 * The exported study files keep their raw codes untouched — this is a presentation layer.
 *
 * Known codes get a curated English/French rendering; anything unknown falls back to a generic prettifier
 * (snake/CAPS/camel → readable words). Language follows the current device locale (French vs. everything else).
 */
object HormonitorLabels {

    private data class L(val en: String, val fr: String)

    fun humanize(raw: String): String {
        val key = raw.trim()
        if (key.isEmpty()) return raw
        val french = Locale.getDefault().language.equals("fr", ignoreCase = true)
        (MAP[key] ?: MAP[key.uppercase(Locale.US)])?.let { return if (french) it.fr else it.en }
        return prettify(key)
    }

    private val MAP: Map<String, L> = mapOf(
        // Patient modes
        "DAWN_ENDOGENOUS" to L("Dawn (endogenous)", "Aube (endogène)"),
        "MEAL" to L("Meal", "Repas"),
        "FAST_MEAL" to L("Fast meal", "Repas rapide"),
        "MEAL_UNDECLARED" to L("Undeclared meal", "Repas non déclaré"),
        "PROTECTIVE" to L("Protective", "Protecteur"),
        "RESISTANCE_PROBABLE" to L("Probable resistance", "Résistance probable"),
        // Physio / behavioural states
        "RESTING" to L("Resting", "Repos"),
        "MALE_CIRCADIAN_HORMONAL" to L("Circadian / hormonal (male)", "Circadien / hormonal (homme)"),
        "INTER_WAVE" to L("Between meal waves", "Inter-vagues (repas)"),
        "FIRST_WAVE" to L("First meal wave", "Première vague (repas)"),
        "SECOND_WAVE" to L("Second meal wave", "Deuxième vague (repas)"),
        "LATE_FAT" to L("Late fat/protein", "Gras/protéines tardifs"),
        // Activity states
        "IDLE" to L("Idle", "Inactif"),
        "ACTIVE" to L("Active", "Actif"),
        "SLEEPING" to L("Sleeping", "Sommeil"),
        "Stress/Activity" to L("Stress / activity", "Stress / activité"),
        // Safety gates / phases
        "SafetyPass" to L("Safety: pass", "Sécurité : OK"),
        "SafetyLGS_T2" to L("Safety: LGS (T2)", "Sécurité : LGS (T2)"),
        "EARLY" to L("Early", "Précoce"),
        "LATE" to L("Late", "Tardif"),
        // Hormonal / metabolic statuses
        "EUTHYROID" to L("Euthyroid", "Euthyroïdie"),
        "HYPOTHYROID" to L("Hypothyroid", "Hypothyroïdie"),
        "HYPERTHYROID" to L("Hyperthyroid", "Hyperthyroïdie"),
        "NONE" to L("None", "Aucun"),
        // Final loop decisions
        "smb" to L("SMB", "SMB"),
        "tbr_up" to L("Basal up", "Basale ↑"),
        "tbr_down" to L("Basal down", "Basale ↓"),
        "suspend" to L("Suspend", "Suspension"),
        "none" to L("No change", "Sans changement"),
        // Strategy hints
        "BASAL_BRIDGE" to L("Basal bridge", "Pont basal"),
        "BASAL_FIRST" to L("Basal first", "Basale d'abord"),
        // Common reason codes
        "FALSE_MEAL_SUPPRESS" to L("False-meal suppression", "Suppression faux-repas"),
        "PROTECTIVE_PREEMPT" to L("Protective preempt", "Préemption protectrice"),
        "CAUSAL_DAWN_ENDOGENOUS" to L("Causal: dawn (endogenous)", "Causal : aube (endogène)"),
    )

    /** Generic fallback: split on `_`, `/`, and camelCase boundaries → sentence-cased words. */
    private fun prettify(raw: String): String {
        val spaced = raw
            .replace('/', ' ')
            .replace('_', ' ')
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        if (spaced.isEmpty()) return raw
        val lower = spaced.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
