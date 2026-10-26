package app.aaps.plugins.aps.openAPSAIMI

/** The TFLite interpreter on `Documents/AAPS/ml/modelUAM.tflite`, see [AimiModelHandler]. */
internal actual fun aimiPlatformUamPredictor(): AimiUamHandler.AimiUamPredictor? = AimiModelHandler.predictor
