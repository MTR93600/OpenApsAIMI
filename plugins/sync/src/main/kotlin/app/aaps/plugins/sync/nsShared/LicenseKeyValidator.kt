package app.aaps.plugins.sync.nsShared

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Premium License Key Validator for Remote Control access.
 * 
 * Security:
 * - License key hash is hardcoded (not plaintext)
 * - User-entered key is hashed and compared
 * - Valid license stored in EncryptedSharedPreferences
 */
@Singleton
class LicenseKeyValidator @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val PREFS_NAME = "remote_license"
        private const val KEY_LICENSE_VALIDATED = "license_validated"
        
        /**
         * Expected license key hash (SHA-256).
         * ⚠️ NEVER store the actual license key here!
         * 
         * License Key: AIMI-PRO-2024-Rx9$Km7#Qp2!Vn5*Zw8@Ht4
         * Hash: pkpdBmv/xoRi3yQ5uyBhBu6bxxepwZfpwwzthNHAZek=
         */
        private const val EXPECTED_LICENSE_HASH = "pkpdBmv/xoRi3yQ5uyBhBu6bxxepwZfpwwzthNHAZek="
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
     * Check if a valid license is already activated.
     */
    fun isLicenseActivated(): Boolean {
        val activated = encryptedPrefs.getBoolean(KEY_LICENSE_VALIDATED, false)
        aapsLogger.debug(LTag.NSCLIENT, "[License] Is activated: $activated")
        return activated
    }
    
    /**
     * Validate license key entered by user.
     * 
     * @param licenseKey User-entered license key
     * @return true if valid, false otherwise
     */
    fun validateLicenseKey(licenseKey: String): Boolean {
        val normalizedKey = licenseKey.trim()
        
        if (normalizedKey.isEmpty()) {
            aapsLogger.warn(LTag.NSCLIENT, "[License] Empty key provided")
            return false
        }
        
        // Hash the user input
        val inputHash = hashLicenseKey(normalizedKey)
        
        // Compare with expected hash
        val isValid = inputHash == EXPECTED_LICENSE_HASH
        
        if (isValid) {
            // Store validation in encrypted prefs
            encryptedPrefs.edit()
                .putBoolean(KEY_LICENSE_VALIDATED, true)
                .apply()
                
            aapsLogger.info(LTag.NSCLIENT, "[License] ✅ Valid license activated")
        } else {
            aapsLogger.warn(LTag.NSCLIENT, "[License] ❌ Invalid license key attempted")
        }
        
        return isValid
    }
    
    /**
     * Revoke license (admin reset).
     */
    fun revokeLicense() {
        encryptedPrefs.edit()
            .putBoolean(KEY_LICENSE_VALIDATED, false)
            .apply()
            
        aapsLogger.info(LTag.NSCLIENT, "[License] License revoked")
    }
    
    /**
     * Hash license key using SHA-256.
     */
    private fun hashLicenseKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
