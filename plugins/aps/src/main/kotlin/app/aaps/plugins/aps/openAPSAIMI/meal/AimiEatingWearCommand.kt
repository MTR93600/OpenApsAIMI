package app.aaps.plugins.aps.openAPSAIMI.meal

internal enum class AimiEatingWearCommand { START, STOP, STATUS }

internal fun aimiEatingWearCommand(action: String?): AimiEatingWearCommand? = when (action) {
    AimiEatingWearReceiver.ACTION_START -> AimiEatingWearCommand.START
    AimiEatingWearReceiver.ACTION_STOP -> AimiEatingWearCommand.STOP
    AimiEatingWearReceiver.ACTION_STATUS -> AimiEatingWearCommand.STATUS
    else -> null
}
