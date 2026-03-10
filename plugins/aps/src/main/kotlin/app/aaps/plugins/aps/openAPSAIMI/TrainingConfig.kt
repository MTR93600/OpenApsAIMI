package app.aaps.plugins.aps.openAPSAIMI

data class TrainingConfig(
    var learningRate: Double = 0.001,
    val beta1: Double = 0.9,
    val beta2: Double = 0.999,
    val epsilon: Double = 1e-8,
    var patience: Int = 10,
    var batchSize: Int = 32,
    var weightDecay: Double = 0.01,
    var epochs: Int = 1000,
    var useBatchNorm: Boolean = true,
    var useDropout: Boolean = true,
    var dropoutRate: Double = 0.5,
    var leakyReluAlpha: Double = 0.01
)