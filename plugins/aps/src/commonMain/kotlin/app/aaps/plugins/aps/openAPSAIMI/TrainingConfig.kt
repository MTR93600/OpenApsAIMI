package app.aaps.plugins.aps.openAPSAIMI

/**
 * Training settings of [AimiNeuralNetwork].
 *
 * @param useBatchNorm layer normalization on the hidden layer. Off by default: the inputs are already z-scored by
 *   `AimiNeuralNetwork.setInputNormalization`, an 8 unit layer gains nothing from it, and it forces the hidden vector
 *   to zero mean, which pins the output of the network to its output bias. The code path is kept so the choice stays
 *   reversible.
 * @param dropoutRate share of hidden units dropped per sample during training. Kept low: the hidden layer has about
 *   8 units, so a high rate removes most of the capacity of the model on every sample.
 * @param weightDecay decoupled AdamW decay. The network applies it as `learningRate * weightDecay * w`, once per
 *   mini batch.
 * @param randomSeed seed of every random draw of the network: the start weights, the epoch shuffle and the dropout
 *   mask. The default value is fixed, so training gives the same model twice for the same data. A caller or a test
 *   can set its own seed. The value is read when `AimiNeuralNetwork` is built and again at the start of every
 *   training run, so changing it later has no effect on a run already going.
 */
data class TrainingConfig(
    var learningRate: Double = 0.001,
    val beta1: Double = 0.9,
    val beta2: Double = 0.999,
    val epsilon: Double = 1e-8,
    var patience: Int = 10,
    var batchSize: Int = 32,
    var weightDecay: Double = 0.01,
    var epochs: Int = 1000,
    var useBatchNorm: Boolean = false,
    var useDropout: Boolean = true,
    var dropoutRate: Double = 0.1,
    var leakyReluAlpha: Double = 0.01,
    var randomSeed: Long = DEFAULT_RANDOM_SEED
)

/**
 * Default training seed. Any fixed value works; this one is only a date, kept stable so a model trained today and a
 * model trained from the same data tomorrow come out the same.
 */
const val DEFAULT_RANDOM_SEED: Long = 20260822L
