package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Implémente l’override HighBG agressif mais “safe”.
 * Renvoie la (nouvelleDose, overrideUsed, newIntervalSmb)
 */
object HighBgOverride {

    data class Result(
        val dose: Double,
        val overrideUsed: Boolean,
        val newInterval: Int?
    )

    /**
     * @param hypoGuard seuil hypo courant
     * @param maxSmb borne SMB
     * @param pumpStep pas minimum pompe (ex: 0.05U)
     */
    fun apply(
        bg: Double,
        delta: Double,
        predictedBg: Double,
        eventualBg: Double,
        hypoGuard: Double,
        iob: Double,
        maxSmb: Double,
        currentDose: Double,
        pumpStep: Double
    ): Result {
        val highBgOverride =
            (bg >= 180.0 || (bg >= 150.0 && delta >= 1.5)) &&
                !isBelowHypoThreshold(
                    bgNow = bg,
                    predicted = predictedBg,
                    eventual = eventualBg,
                    hypo = hypoGuard,
                    delta = delta
                ) &&
                iob < maxSmb

        if (!highBgOverride) return Result(currentDose, false, null)

        var out = currentDose
        if (out < pumpStep) out = pumpStep
        if (out > maxSmb) out = maxSmb
        // force cadence agressive (interval = 0)
        return Result(out, true, 0)
    }

    private fun isBelowHypoThreshold(
        bgNow: Double, predicted: Double, eventual: Double, hypo: Double, delta: Double
    ): Boolean {
        // même gardiennage que ta méthode actuelle
        val minPred = minOf(bgNow, predicted, eventual)
        return minPred <= hypo
    }
}
