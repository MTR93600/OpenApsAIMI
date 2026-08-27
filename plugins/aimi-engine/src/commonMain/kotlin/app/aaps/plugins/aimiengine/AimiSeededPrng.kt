package app.aaps.plugins.aimiengine

import kotlin.random.Random

/**
 * Seeded PRNG for AIMI tests and later model noise.
 *
 * The engine must not call [Random.Default]. A replay must pass the same seed.
 */
class AimiSeededPrng(seed: Long) {
    private val random: Random = Random(seed)

    fun nextDouble(): Double = random.nextDouble()

    fun nextLong(): Long = random.nextLong()
}
