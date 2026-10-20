package app.aaps.pump.medtrum.encryption

/**
 * Returns [size] bytes from the operating system secure random source.
 *
 * The pump session token is built from these bytes, so a plain `kotlin.random.Random` must never
 * be used here. Each target uses the secure source of its platform and fails loudly if it cannot.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
