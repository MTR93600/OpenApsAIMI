package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAssetReader

interface OrefRiskScorer : AutoCloseable {

    fun predictHypoProba(featuresRowMajor: Array<FloatArray>): FloatArray
    fun predictHyperProba(featuresRowMajor: Array<FloatArray>): FloatArray
    fun predictBgChange(featuresRowMajor: Array<FloatArray>): FloatArray
}

interface OrefRiskScorerFactory {

    val runtimeAvailable: Boolean

    fun assetModelsPresent(assets: AimiAssetReader): Boolean
    fun tryCreate(assets: AimiAssetReader): OrefRiskScorer?
}

internal object UnavailableOrefRiskScorerFactory : OrefRiskScorerFactory {

    override val runtimeAvailable: Boolean = false

    override fun assetModelsPresent(assets: AimiAssetReader): Boolean = false

    override fun tryCreate(assets: AimiAssetReader): OrefRiskScorer? = null
}

internal expect fun orefRiskScorerFactory(): OrefRiskScorerFactory
