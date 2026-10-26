package app.aaps.plugins.aps.openAPSAIMI

/** The desktop JVM has no TFLite runtime: the engine keeps the model-absent path (0f). */
internal actual fun aimiPlatformUamPredictor(): AimiUamHandler.AimiUamPredictor? = null
