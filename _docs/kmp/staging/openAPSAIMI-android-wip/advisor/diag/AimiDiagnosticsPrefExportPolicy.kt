package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import java.util.Locale

/**
 * Règles d’export diagnostic (support ZIP) : **aucune** valeur secrète dans le rapport texte.
 * Tests unitaires sans Android.
 */
object AimiDiagnosticsPrefExportPolicy {

    fun isSecretPreferenceKey(key: String): Boolean {
        val k = key.lowercase(Locale.US)
        if (k.contains("password")) return true
        if (k.contains("secret")) return true
        if (k.contains("apikey") || k.contains("api_key")) return true
        if (k.contains("authorization")) return true
        if (k.contains("credential")) return true
        if (k.contains("private_key")) return true
        if (k.contains("token")) return true
        if (k.contains("_key")) return true
        if (k.contains("_pin") || k.contains("remote_control_pin")) return true
        return false
    }

    fun isLikelySecretStringValue(value: String): Boolean {
        val v = value.trim()
        if (v.length < 16) return false
        return v.startsWith("sk-") ||
            v.startsWith("sk_") ||
            v.startsWith("AIza") ||
            v.startsWith("eyJ") ||
            v.startsWith("xox")
    }

    fun formatExportValue(key: String, raw: Any?): String {
        if (isSecretPreferenceKey(key)) return "***REDACTED***"
        val s = raw?.toString() ?: return "null"
        if (isLikelySecretStringValue(s)) return "***REDACTED***"
        return s
    }
}
