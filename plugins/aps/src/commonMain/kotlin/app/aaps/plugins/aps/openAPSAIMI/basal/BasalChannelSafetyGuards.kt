package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.min

/**
 * 🛡️ Garde-fous du canal basal automatique (lot 3), gouvernés par
 * `BooleanKey.OApsAIMIBasalChannelSafetyGuards` (défaut `false` → comportement historique).
 *
 * Deux fuites d'autorité laissaient le canal basal automatique doser alors que le canal SMB avait été
 * délibérément retenu :
 *
 * 1. **Le mutex basal-first ne pose que la question « un SMB a-t-il été demandé ? »**. Un SMB mis à zéro
 *    par une règle de sécurité satisfait donc la condition et *déverrouille* les canaux T3C/Harmonia
 *    basal-first, au lieu de les bloquer — ce qui inverse l'intention de la protection.
 * 2. **Quand ces canaux possèdent le taux, le multiplicateur adaptatif était forcé à `1.0`**, jetant la
 *    réduction protectrice des learners : le seul amortisseur qui liait encore.
 *
 * Logique pure et sans état, pour être caractérisable en test unitaire indépendamment du moteur.
 */
object BasalChannelSafetyGuards {

    /**
     * `true` quand le SMB du tick a été mis à zéro par une **règle de sécurité**, et non simplement
     * « pas demandé ».
     *
     * @param criticalSafetyZeroed `isCriticalSafetyCondition` a zéroé le SMB (BG sous la cible, chute
     *   rapide, protection minPredBG…).
     * @param contextSuppressSmb le contexte actif (p.ex. `HypoRecovery`) supprime le SMB.
     */
    fun smbZeroedBySafety(criticalSafetyZeroed: Boolean, contextSuppressSmb: Boolean): Boolean =
        criticalSafetyZeroed || contextSuppressSmb

    /**
     * `true` quand un canal basal-first doit être bloqué parce que le SMB a été retenu pour sécurité.
     *
     * **Les modes repas manuels sont exclus** : quand un mode est déclaré, l'utilisateur a explicitement
     * demandé que la basale du mode soit appliquée sur toute sa durée, et les garde-fous ne doivent pas
     * s'y opposer.
     *
     * Volontairement **indépendant** du mode Basal-First : celui-ci route vers le basal de façon
     * délibérée, ce n'est pas un refus de sécurité et il ne doit pas être bloqué ici.
     */
    fun shouldBlockBasalFirst(
        guardsEnabled: Boolean,
        criticalSafetyZeroed: Boolean,
        contextSuppressSmb: Boolean,
        mealModeActive: Boolean,
    ): Boolean = guardsEnabled &&
        !mealModeActive &&
        smbZeroedBySafety(criticalSafetyZeroed, contextSuppressSmb)

    /**
     * Multiplicateur adaptatif à conserver quand un plan basal-first possède le taux.
     *
     * Ne garde que les **réductions** : les amplifications au-dessus de 1.0 restent écartées, exactement
     * comme dans le comportement historique. Le taux résultant est donc toujours **inférieur ou égal** à
     * celui d'aujourd'hui — jamais supérieur.
     *
     * **Neutre (1.0) pendant un mode repas manuel** : la basale du mode doit être appliquée telle que
     * configurée, sans amortissement par les learners.
     */
    fun basalFirstAdaptiveMultiplier(
        guardsEnabled: Boolean,
        adaptiveMult: Double,
        mealModeActive: Boolean,
    ): Double = when {
        !guardsEnabled           -> 1.0
        mealModeActive           -> 1.0
        !adaptiveMult.isFinite() -> 1.0
        adaptiveMult <= 0.0      -> 1.0
        else                     -> min(adaptiveMult, 1.0)
    }
}
