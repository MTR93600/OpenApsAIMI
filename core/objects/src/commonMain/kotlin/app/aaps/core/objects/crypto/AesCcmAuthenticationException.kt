package app.aaps.core.objects.crypto

/**
 * AES-CCM rejected a ciphertext. The plaintext is not returned, not even in part.
 */
class AesCcmAuthenticationException(message: String) : IllegalStateException(message)
