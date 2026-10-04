package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * The physio calls [recordSmbActionType] makes: `setSmbActionType`, then
 * `getLastDecisionTrace()?.finalLoopDecisionType`, then maybe `setFinalLoopDecisionType`.
 * The Android shell delegates each one to `physioAdapter`.
 */
internal interface AimiSmbActionType {
    fun setSmbActionType(decisionType: String)
    fun finalLoopDecisionType(): String?
    fun setFinalLoopDecisionType(decisionType: String)
}

internal fun recordSmbActionType(action: AimiSmbActionType, decisionType: String) {
    action.setSmbActionType(decisionType)
    val currentFinal = action.finalLoopDecisionType()
    if (decisionType != "none" || currentFinal.isNullOrBlank() || currentFinal == "pending") {
        action.setFinalLoopDecisionType(decisionType)
    }
}
