package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * Trois états possibles après un épisode BG < 70 :
 *   None              → aucune hypo récente  → flux SMB normal
 *   ReboundSuspected  → hypo récente, pas de repas détecté → SMB=0, TBR bridge
 *   MealConfirmed     → hypo récente MAIS repas confirmé   → SMB cappé 50%
 *
 * Décision du tick, partagée avec la coquille. L'écriture de `lastHypoBelow70At`
 * reste dans `classifyPostHypoState`.
 */
internal sealed class PostHypoState {
    object None : PostHypoState()
    data class ReboundSuspected(val sinceMs: Long) : PostHypoState()
    data class MealConfirmed(val sinceMs: Long) : PostHypoState()
}
