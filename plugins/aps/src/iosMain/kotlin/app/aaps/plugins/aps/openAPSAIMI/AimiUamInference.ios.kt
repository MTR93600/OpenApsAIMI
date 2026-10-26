package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.IosUamPredictor

/** TensorFlow Lite C on `Documents/AAPS/ml/modelUAM.tflite`, see [IosUamPredictor]. */
internal actual fun aimiPlatformUamPredictor(): AimiUamHandler.AimiUamPredictor? = IosUamPredictor
