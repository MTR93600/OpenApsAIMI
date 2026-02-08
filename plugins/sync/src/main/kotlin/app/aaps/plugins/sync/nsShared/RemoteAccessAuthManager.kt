package app.aaps.plugins.sync.nsShared

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages access password for Remote Control menu.
 * Password is hashed and stored in EncryptedSharedPreferences (never in code).
 */
@Singleton
class RemoteAccessAuthManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val PREFS_NAME = "remote_access_auth"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_SALT = "salt"
        private const val KEY_UNLOCKED_UNTIL = "unlocked_until"
        private const val SESSION_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }
    
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    /**
     * Check if access password is configured.
     */
    fun isPasswordConfigured(): Boolean {
        return encryptedPrefs.contains(KEY_PASSWORD_HASH)
    }
    
    /**
     * Configure the access password (admin only - first setup).
     */
    fun configurePassword(password: String) {
        require(password.length >= 6) {
            "Password must be at least 6 characters"
        }
        
        // Generate unique salt
        val salt = ByteArray(32).apply {
            SecureRandom().nextBytes(this)
        }
        
        val hash = hashPassword(password, salt)
        
        encryptedPrefs.edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .apply()
            
        aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Password configured successfully")
    }
    
    /**
     * Verify access password.
     */
    fun verifyPassword(inputPassword: String): Boolean {
        if (!isPasswordConfigured()) {
            aapsLogger.warn(LTag.NSCLIENT, "[RemoteAccess] No password configured")
            return false
        }
        
        val storedHash = encryptedPrefs.getString(KEY_PASSWORD_HASH, null) ?: return false
        val saltBase64 = encryptedPrefs.getString(KEY_SALT, null) ?: return false
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        
        val inputHash = hashPassword(inputPassword, salt)
        
        val isValid = inputHash == storedHash
        
        if (isValid) {
            // Mark as unlocked for session duration
            val unlockedUntil = System.currentTimeMillis() + SESSION_DURATION_MS
            encryptedPrefs.edit()
                .putLong(KEY_UNLOCKED_UNTIL, unlockedUntil)
                .apply()
                
            aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Password verified - session started")
        } else {
            aapsLogger.warn(LTag.NSCLIENT, "[RemoteAccess] Invalid password attempt")
        }
        
        return isValid
    }
    
    /**
     * Check if currently unlocked (session active).
     */
    fun isUnlocked(): Boolean {
        val unlockedUntil = encryptedPrefs.getLong(KEY_UNLOCKED_UNTIL, 0L)
        return System.currentTimeMillis() < unlockedUntil
    }
    
    /**
     * Lock immediately (invalidate session).
     */
    fun lock() {
        encryptedPrefs.edit()
            .putLong(KEY_UNLOCKED_UNTIL, 0L)
            .apply()
            
        aapsLogger.info(LTag.NSCLIENT, "[RemoteAccess] Session locked")
    }
    
    /**
     * Change password.
     */
    fun changePassword(oldPassword: String, newPassword: String): Boolean {
        if (!verifyPassword(oldPassword)) {
            return false
        }
        
        configurePassword(newPassword)
        return true
    }
    
    /**
     * Hash password with salt using SHA-256.
     */
    private fun hashPassword(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val saltedPassword = password.toByteArray(Charsets.UTF_8) + salt
        val hash = digest.digest(saltedPassword)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
