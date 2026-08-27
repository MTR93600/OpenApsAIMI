package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.min

/**
 * 🔒 Invariants terminaux du canal basal (lot 2), gouvernés par
 * `BooleanKey.OApsAIMIBasalTerminalInvariants` (défaut `false` → comportement historique).
 *
 * ## Pourquoi un point terminal
 *
 * Les protections du canal basal étaient posées **en amont** de multiplicateurs qui peuvent élever le
 * taux d'un facteur 10 : le plafond `capBasalRateForCorrectionAggression` s'applique avant
 * `setTempBasal`, dans lequel `DynamicBasalController.calculateDynamicRate` multiplie par `[0 … 10]`,
 * puis `AdaptiveBasal`, puis l'ampli endocrine. Un plafond placé avant un multiplicateur ne borne pas la
 * valeur finale — c'est ainsi qu'un « bridge post-hypo » calculé à 1,10 U/h ressortait à 5,25 U/h.
 *
 * Ajouter une règle de plus en amont reproduirait le défaut. Ces invariants s'évaluent donc **après le
 * dernier multiplicateur et juste avant l'écriture de `rT.rate`**, là où plus rien ne peut les écraser.
 *
 * ## Contrat
 *
 * - **Réduction seule.** Chaque invariant est un `coerceAtMost` : le taux retourné est toujours
 *   inférieur ou égal au taux entrant. Aucun invariant ne peut créer ou augmenter une dose.
 * - **Modes repas exclus.** Quand un mode manuel est déclaré, l'utilisateur a demandé que la basale du
 *   mode s'applique sur toute sa durée ; les invariants s'effacent.
 * - **Traçable.** [Result.trace] nomme l'invariant qui a effectivement lié, pour que l'effet soit
 *   mesurable dans `AIMI_Decisions.jsonl` au lieu d'être déduit d'une narration en texte libre.
 */
object BasalTerminalInvariants {

    /** Nom de l'invariant qui a borné le taux, ou `null` si aucun n'a lié. */
    data class Result(
        val rateUph: Double,
        val boundBy: String?,
        val trace: String,
    )

    data class Input(
        val enabled: Boolean,
        val rateUph: Double,
        val profileBasalUph: Double,
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val eventualBgMgdl: Double?,
        val deltaMgdl5m: Double,
        val iobU: Double,
        val mealModeActive: Boolean,
        val postHypoActive: Boolean,
    )

    /**
     * Invariant 1 — **prédiction sous la cible**. La règle oref classique « `eventualBG < target` ⇒ pas
     * d'insuline » existe côté SMB mais n'a jamais existé côté basal. Observé en production : glycémie 104
     * pour une cible 115, eventual dosant 91, et une TBR de 5,00 U/h sur un profil de 0,5–0,6 U/h.
     * Quand la glycémie **et** la prédiction sont sous la cible, le taux ne dépasse pas le basal profil.
     */
    private fun belowTargetCap(i: Input): Double? {
        val eventual = i.eventualBgMgdl ?: return null
        return if (i.bgMgdl < i.targetBgMgdl && eventual < i.targetBgMgdl) i.profileBasalUph else null
    }

    /**
     * Invariant 2 — **verrou post-hypoglycémique**. `postHypoRecoveryActive` neutralise le SMB mais ne
     * bornait le TBR que via un plafond T3C. Observé : re-corrections à 4–5 U/h dix à vingt-cinq minutes
     * après un nadir. Tant que l'autorité post-hypo est active, le taux reste au basal profil.
     */
    private fun postHypoCap(i: Input): Double? =
        if (i.postHypoActive) i.profileBasalUph else null

    /**
     * Invariant 3 — **IOB négatif sans montée franche**. Un IOB net négatif signifie que la délivrance
     * récente est restée sous le profil ; il ne justifie pas à lui seul une escalade. Sans montée
     * confirmée (`delta > 0`), le taux ne dépasse pas le basal profil.
     */
    private fun negativeIobCap(i: Input): Double? =
        if (i.iobU < 0.0 && i.deltaMgdl5m <= 0.0) i.profileBasalUph else null

    fun resolve(i: Input): Result {
        if (!i.enabled || i.mealModeActive || !i.rateUph.isFinite() || i.rateUph <= 0.0) {
            return Result(i.rateUph, null, if (i.mealModeActive) "meal_mode_exempt" else "off")
        }
        if (i.profileBasalUph <= 0.0 || !i.profileBasalUph.isFinite()) {
            return Result(i.rateUph, null, "no_profile_basal")
        }

        val caps = listOf(
            "below_target" to belowTargetCap(i),
            "post_hypo" to postHypoCap(i),
            "negative_iob" to negativeIobCap(i),
        ).mapNotNull { (name, cap) -> cap?.let { name to it } }

        if (caps.isEmpty()) return Result(i.rateUph, null, "no_invariant")

        val (name, cap) = caps.minByOrNull { it.second }!!
        val out = min(i.rateUph, cap)
        val bound = out < i.rateUph
        val trace = buildString {
            append("in=%.2f".format(i.rateUph))
            caps.forEach { (n, c) -> append(" $n=%.2f".format(c)) }
            append(" out=%.2f".format(out))
            if (bound) append(" boundBy=$name")
        }
        return Result(out, if (bound) name else null, trace)
    }
}
