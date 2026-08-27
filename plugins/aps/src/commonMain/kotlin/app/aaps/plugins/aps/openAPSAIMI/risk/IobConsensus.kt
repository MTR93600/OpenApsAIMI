package app.aaps.plugins.aps.openAPSAIMI.risk

/** Numeric clamp on prediction curve points — not a clinical hypo prediction by itself. */
object AimiRiskConstants {
    const val NUMERIC_FLOOR_MGDL: Double = 39.0
    const val NUMERIC_CEILING_MGDL: Double = 401.0
}

enum class AimiRiskPhase {
    /** After first advanced predictions + sanity — safety / Autodrive. */
    EARLY,
    /** After PKPD refresh + IOB consensus — SMB hypo guard authoritative. */
    DECISION,
}

enum class IobDecisionSource {
    AAPS_ALIGNED,
    PKPD_WHEN_AAPS_NEGATIVE,
    AAPS_DEFAULT,
}

data class IobConsensusResult(
    val decisionIobUnits: Double,
    val source: IobDecisionSource,
    val aapsIobUnits: Double,
    val pkpdIobUnits: Double?,
    val deltaUnits: Double,
)

object IobConsensus {

    const val AGREEMENT_EPSILON_UNITS: Double = 0.20
    const val PKPD_MIN_POSITIVE_UNITS: Double = 0.10

    fun resolve(aapsIobUnits: Double, pkpdIobUnits: Double?): IobConsensusResult {
        val pkpd = pkpdIobUnits?.takeIf { it.isFinite() }
        val delta = if (pkpd != null) pkpd - aapsIobUnits else 0.0
        val source =
            when {
                pkpd == null -> IobDecisionSource.AAPS_DEFAULT
                kotlin.math.abs(delta) < AGREEMENT_EPSILON_UNITS -> IobDecisionSource.AAPS_ALIGNED
                aapsIobUnits < 0.0 && pkpd > PKPD_MIN_POSITIVE_UNITS ->
                    IobDecisionSource.PKPD_WHEN_AAPS_NEGATIVE
                else -> IobDecisionSource.AAPS_DEFAULT
            }
        val decision =
            when (source) {
                IobDecisionSource.PKPD_WHEN_AAPS_NEGATIVE -> pkpd!!
                else -> aapsIobUnits
            }
        return IobConsensusResult(
            decisionIobUnits = decision,
            source = source,
            aapsIobUnits = aapsIobUnits,
            pkpdIobUnits = pkpd,
            deltaUnits = delta,
        )
    }
}
